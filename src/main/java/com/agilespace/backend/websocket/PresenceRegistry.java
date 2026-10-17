package com.agilespace.backend.websocket;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Quem está online em cada sala: {@code (módulo, sala) -> userId -> sessões WebSocket abertas}. Em memória, por processo
 * (ver docs/presence-api.md para a limitação de múltiplas instâncias).
 *
 * <p>Um usuário com várias abas é UMA presença: fica online enquanto tiver pelo menos uma sessão. O userId vem sempre do
 * JWT validado no handshake; este registro não conhece nem guarda mais nada sobre a pessoa. Registrar a mesma sessão duas
 * vezes, ou removê-la duas vezes, é idempotente. Sala sem ninguém sai do mapa (sem vazamento de memória).
 *
 * <p>Thread-safe: cada sala é um valor imutável trocado atomicamente por {@code ConcurrentHashMap.compute}; as leituras
 * não bloqueiam.
 */
@Component
@Slf4j
public class PresenceRegistry {

    /** Estado da sala num instante. {@code version} só cresce dentro do processo, para o cliente ordenar eventos. */
    public record Snapshot(PresenceModule module, String roomId, List<String> online, long version) {
        public int total() {
            return online.size();
        }
    }

    /** {@code changed} = a lista de userIds online mudou (entrou o primeiro / saiu o último do usuário). */
    public record Change(boolean changed, Snapshot snapshot) {
    }

    private record RoomKey(PresenceModule module, String roomId) {
    }

    /** userId (em ordem de entrada) -> sessionId -> "ainda está viva?". Imutável depois de construído. */
    private record Room(Map<String, Map<String, BooleanSupplier>> users, long version) {
    }

    private final Map<RoomKey, Room> rooms = new ConcurrentHashMap<>();
    private final AtomicLong versionSeq = new AtomicLong();
    private final Map<PresenceModule, AtomicInteger> sessionCounts = new EnumMap<>(PresenceModule.class);

    public PresenceRegistry() {
        this((MeterRegistry) null);
    }

    @Autowired
    public PresenceRegistry(ObjectProvider<MeterRegistry> meterRegistry) {
        this(meterRegistry == null ? null : meterRegistry.getIfAvailable());
    }

    public PresenceRegistry(MeterRegistry meterRegistry) {
        for (PresenceModule m : PresenceModule.values()) {
            AtomicInteger count = new AtomicInteger();
            sessionCounts.put(m, count);
            if (meterRegistry != null) {
                Gauge.builder("presence.sessions", count, AtomicInteger::get)
                        .description("Conexões WebSocket de sala abertas, por módulo")
                        .tag("module", m.path())
                        .register(meterRegistry);
            }
        }
    }

    /**
     * Registra uma sessão do usuário na sala. {@code alive} (opcional) diz se a conexão ainda está aberta, para a
     * varredura de zumbis; {@code null} = nunca expira por varredura.
     */
    public Change join(PresenceModule module, String roomId, String userId, String sessionId, BooleanSupplier alive) {
        if (module == null || isBlank(roomId) || isBlank(userId) || isBlank(sessionId)) {
            return new Change(false, empty(module, roomId));
        }
        BooleanSupplier liveness = alive != null ? alive : () -> true;
        boolean[] changed = {false};
        Snapshot[] result = new Snapshot[1];
        rooms.compute(new RoomKey(module, roomId), (key, old) -> {
            Map<String, Map<String, BooleanSupplier>> users =
                    old == null ? new LinkedHashMap<>() : new LinkedHashMap<>(old.users());
            Map<String, BooleanSupplier> sessions = users.get(userId);
            if (sessions != null && sessions.containsKey(sessionId)) {
                result[0] = snapshotOf(key, old);
                return old;
            }
            Map<String, BooleanSupplier> updated = sessions == null ? new LinkedHashMap<>() : new LinkedHashMap<>(sessions);
            updated.put(sessionId, liveness);
            users.put(userId, Collections.unmodifiableMap(updated));
            changed[0] = sessions == null || sessions.isEmpty();
            long version = changed[0] || old == null ? versionSeq.incrementAndGet() : old.version();
            sessionCounts.get(module).incrementAndGet();
            Room next = new Room(Collections.unmodifiableMap(users), version);
            result[0] = snapshotOf(key, next);
            return next;
        });
        log.debug("Presence join module={} room={} changed={} online={}", module.path(), roomId, changed[0], result[0].total());
        return new Change(changed[0], result[0]);
    }

    /** Remove a sessão. Se era a última do usuário, ele sai da lista; se era a última da sala, a sala é descartada. */
    public Change leave(PresenceModule module, String roomId, String userId, String sessionId) {
        if (module == null || isBlank(roomId) || isBlank(userId) || isBlank(sessionId)) {
            return new Change(false, empty(module, roomId));
        }
        boolean[] changed = {false};
        Snapshot[] result = new Snapshot[1];
        rooms.compute(new RoomKey(module, roomId), (key, old) -> {
            if (old == null) {
                result[0] = empty(module, roomId);
                return null;
            }
            Map<String, BooleanSupplier> sessions = old.users().get(userId);
            if (sessions == null || !sessions.containsKey(sessionId)) {
                result[0] = snapshotOf(key, old);
                return old;
            }
            Room next = without(old, userId, sessionId, changed);
            sessionCounts.get(module).decrementAndGet();
            result[0] = next == null
                    ? new Snapshot(module, roomId, List.of(), versionSeq.incrementAndGet())
                    : snapshotOf(key, next);
            return next;
        });
        log.debug("Presence leave module={} room={} changed={} online={}", module.path(), roomId, changed[0], result[0].total());
        return new Change(changed[0], result[0]);
    }

    /**
     * Descarta sessões que o servidor já considera fechadas mas cujo {@code afterConnectionClosed} nunca chegou.
     * Devolve uma {@link Change} por sala em que alguém saiu da lista, para o chamador avisar a sala.
     */
    public List<Change> sweepDeadSessions() {
        List<Change> changes = new ArrayList<>();
        for (RoomKey key : new ArrayList<>(rooms.keySet())) {
            boolean[] changed = {false};
            Snapshot[] result = new Snapshot[1];
            rooms.computeIfPresent(key, (k, room) -> {
                Room current = room;
                outer:
                for (Map.Entry<String, Map<String, BooleanSupplier>> user : room.users().entrySet()) {
                    for (Map.Entry<String, BooleanSupplier> s : user.getValue().entrySet()) {
                        if (!isDead(s.getValue())) continue;
                        boolean[] userGone = {false};
                        current = without(current, user.getKey(), s.getKey(), userGone);
                        sessionCounts.get(k.module()).decrementAndGet();
                        changed[0] |= userGone[0];
                        if (current == null) break outer;
                    }
                }
                result[0] = current == null
                        ? new Snapshot(k.module(), k.roomId(), List.of(), versionSeq.incrementAndGet())
                        : snapshotOf(k, current);
                return current;
            });
            if (changed[0] && result[0] != null) {
                changes.add(new Change(true, result[0]));
                log.debug("Presence sweep module={} room={} online={}", key.module().path(), key.roomId(), result[0].total());
            }
        }
        return changes;
    }

    /** Estado atual da sala (lista vazia e versão 0 se não há ninguém). */
    public Snapshot snapshot(PresenceModule module, String roomId) {
        if (module == null || roomId == null) return empty(module, roomId);
        RoomKey key = new RoomKey(module, roomId);
        Room room = rooms.get(key);
        return room == null ? empty(module, roomId) : snapshotOf(key, room);
    }

    /** Conexões abertas do módulo (a mesma leitura da métrica {@code presence.sessions}). */
    public int sessionCount(PresenceModule module) {
        return sessionCounts.get(module).get();
    }

    /** Salas com alguém conectado (para testes e diagnóstico). */
    public int roomCount() {
        return rooms.size();
    }

    private static boolean isDead(BooleanSupplier alive) {
        try {
            return !alive.getAsBoolean();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Sala sem a sessão; {@code null} se ficou vazia. {@code userGone[0]} = o usuário também saiu da lista. */
    private Room without(Room room, String userId, String sessionId, boolean[] userGone) {
        Map<String, Map<String, BooleanSupplier>> users = new LinkedHashMap<>(room.users());
        Map<String, BooleanSupplier> sessions = new LinkedHashMap<>(users.get(userId));
        sessions.remove(sessionId);
        if (sessions.isEmpty()) {
            users.remove(userId);
            userGone[0] = true;
        } else {
            users.put(userId, Collections.unmodifiableMap(sessions));
        }
        if (users.isEmpty()) return null;
        long version = userGone[0] ? versionSeq.incrementAndGet() : room.version();
        return new Room(Collections.unmodifiableMap(users), version);
    }

    private static Snapshot snapshotOf(RoomKey key, Room room) {
        return new Snapshot(key.module(), key.roomId(), List.copyOf(room.users().keySet()), room.version());
    }

    private static Snapshot empty(PresenceModule module, String roomId) {
        return new Snapshot(module, roomId, List.of(), 0L);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
