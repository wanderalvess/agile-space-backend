package com.agilespace.backend.service;

import com.agilespace.backend.dto.MembershipDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Cache dos vínculos (pmr + projetos) por usuário, com TTL curto (app.membership.cache-ttl-seconds, padrão 60; 0 desliga).
 * Mesmo desenho do {@code UserSessionGuard}: memória do processo, limite de entradas, evicção explícita.
 *
 * <p>Só guarda o que vem de project_member_roles e projects. A preferência de squad ativa (users.default_project_id) é
 * lida do usuário a cada chamada, então trocar de squad não precisa invalidar nada. A entrada leva a "impressão" da
 * conta (id, e-mail, conta do Jira): se algum deles mudou, a entrada é ignorada.
 *
 * <p>Evicção: o {@link MembershipCacheEvictionListener} chama {@link #evictAllOnWrite()} em TODA gravação de
 * ProjectMemberRole ou ProjectConfig (JPA). Ele limpa na hora (a própria transação enxerga o que gravou) e de novo no
 * fim da transação (commit ou rollback). Enquanto a transação tem gravação pendente, ela não lê nem popula o cache,
 * para não publicar dado ainda não confirmado. Com mais de uma instância da API a evicção vale só no processo que
 * gravou; o TTL limita o atraso nas demais.
 */
@Component
public class MembershipCache {

    static final int MAX_ENTRIES = 5_000;
    private static final Object PENDING_KEY = new Object();

    public record Snapshot(List<MembershipDto> memberships, boolean transversalLeader) { }

    private record Entry(String fingerprint, Snapshot snapshot, long loadedAt) { }

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    /** Relógio injetável para testar expiração sem dormir. */
    volatile LongSupplier clock = System::currentTimeMillis;

    public MembershipCache(@Value("${app.membership.cache-ttl-seconds:60}") long ttlSeconds) {
        this.ttlMillis = Math.max(0L, ttlSeconds) * 1000L;
    }

    public Optional<Snapshot> get(String userId, String fingerprint) {
        if (userId == null || ttlMillis == 0 || bypass()) {
            return Optional.empty();
        }
        Entry e = entries.get(userId);
        if (e != null && e.fingerprint().equals(fingerprint) && clock.getAsLong() - e.loadedAt() < ttlMillis) {
            hits.incrementAndGet();
            return Optional.of(e.snapshot());
        }
        misses.incrementAndGet();
        return Optional.empty();
    }

    public void put(String userId, String fingerprint, Snapshot snapshot) {
        if (userId == null || ttlMillis == 0 || bypass()) {
            return;
        }
        if (entries.size() >= MAX_ENTRIES) {
            entries.clear();
        }
        entries.put(userId, new Entry(fingerprint, snapshot, clock.getAsLong()));
    }

    public void evict(String userId) {
        if (userId != null) {
            entries.remove(userId);
        }
    }

    public void evictAll() {
        entries.clear();
    }

    /**
     * Chamado a cada gravação de pmr/projeto: limpa agora e de novo quando a transação terminar. Sem transação ativa,
     * limpa só agora.
     */
    public void evictAllOnWrite() {
        entries.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive() && !TransactionSynchronizationManager.hasResource(PENDING_KEY)) {
            TransactionSynchronizationManager.bindResource(PENDING_KEY, Boolean.TRUE);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(PENDING_KEY);
                    entries.clear();
                }
            });
        }
    }

    private static boolean bypass() {
        return TransactionSynchronizationManager.isSynchronizationActive() && TransactionSynchronizationManager.hasResource(PENDING_KEY);
    }

    public int size() {
        return entries.size();
    }

    public long hits() {
        return hits.get();
    }

    public long misses() {
        return misses.get();
    }
}
