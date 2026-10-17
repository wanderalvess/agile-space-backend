package com.agilespace.backend.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Limpeza periódica de sessões zumbis do {@link PresenceRegistry} (conexão que o servidor já deu como fechada mas cujo
 * {@code afterConnectionClosed} não chegou) e aviso às salas afetadas. O caminho normal de saída é o fechamento/erro da
 * conexão no handler; isto é só a rede de segurança.
 */
@Component
@Slf4j
public class PresenceSweeper {

    private final PresenceRegistry registry;
    private final Map<PresenceModule, PresenceBroadcaster> broadcasters = new EnumMap<>(PresenceModule.class);

    public PresenceSweeper(PresenceRegistry registry, List<PresenceBroadcaster> handlers) {
        this.registry = registry;
        for (PresenceBroadcaster b : handlers) {
            broadcasters.put(b.presenceModule(), b);
        }
    }

    @Scheduled(fixedDelayString = "${app.presence.sweep-interval-ms:30000}",
            initialDelayString = "${app.presence.sweep-interval-ms:30000}")
    public void sweep() {
        try {
            for (PresenceRegistry.Change change : registry.sweepDeadSessions()) {
                PresenceRegistry.Snapshot snap = change.snapshot();
                PresenceBroadcaster b = broadcasters.get(snap.module());
                if (b != null) b.broadcastPresence(snap.roomId(), snap);
            }
        } catch (RuntimeException e) {
            log.warn("Presence sweep failed: {}", e.toString());
        }
    }
}
