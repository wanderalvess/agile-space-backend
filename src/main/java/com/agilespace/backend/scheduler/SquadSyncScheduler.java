package com.agilespace.backend.scheduler;

import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.repository.SquadRepository;
import com.agilespace.backend.service.SquadSyncGuard;
import com.agilespace.backend.service.SquadSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sync automático pros squads que optaram por isso (Squad.syncOwnerUserId configurado) —
 * sem isso, o squad só atualiza quando alguém clica em "Sincronizar". Desligado por padrão
 * via app.squad.scheduled-sync.enabled (ver plano de unificação Squad Pulse + jiradash,
 * Fase 4). Cada squad roda isolado (try/catch) — uma JQL ruim ou PAT expirado de um squad
 * não deve travar a rodada dos demais.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SquadSyncScheduler {

    private static final int RECONCILE_INTERVAL_DEFAULT_HOURS = 6;
    // Squad que falhou (token expirado, JQL ruim) não é tentada de novo a cada tick de 15 min: sem isso a
    // mesma falha se repetia 96 vezes por dia, em cada squad com problema.
    private static final Duration FAILURE_BACKOFF = Duration.ofHours(1);

    private final Map<String, Instant> lastFailureAt = new ConcurrentHashMap<>();

    private final SquadRepository squadRepository;
    private final SquadSyncService squadSyncService;
    private final SquadSyncGuard syncGuard;

    @Value("${app.squad.scheduled-sync.enabled:false}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${app.squad.scheduled-sync.fixed-delay-ms:900000}")
    public void syncDueSquads() {
        if (!enabled) return;

        List<Squad> candidates = squadRepository.findBySyncOwnerUserIdIsNotNull();
        for (Squad squad : candidates) {
            if (!isDue(squad)) continue;
            Instant failedAt = lastFailureAt.get(squad.getId());
            if (failedAt != null && Duration.between(failedAt, Instant.now()).compareTo(FAILURE_BACKOFF) < 0) continue;
            if (!syncGuard.tryAcquire(squad.getId())) {
                log.info("[squad-scheduler] pulando {} — já há uma sincronização em andamento (manual ou tick anterior).", squad.getId());
                continue;
            }
            try {
                log.info("[squad-scheduler] sincronizando {} (owner={})", squad.getId(), squad.getSyncOwnerUserId());
                squadSyncService.syncSquad(squad.getId(), squad.getSyncOwnerUserId(), false);
                lastFailureAt.remove(squad.getId());
            } catch (Exception e) {
                lastFailureAt.put(squad.getId(), Instant.now());
                log.error("[squad-scheduler] falha ao sincronizar {}: {}", squad.getId(), e.getMessage());
            } finally {
                syncGuard.release(squad.getId());
            }
        }
    }

    private boolean isDue(Squad squad) {
        if (squad.getLastSyncAt() == null || squad.getLastSyncAt().isBlank()) return true;
        Instant lastSync = parseInstant(squad.getLastSyncAt());
        if (lastSync == null) return true;
        int hours = squad.getReconcileIntervalHours() != null ? squad.getReconcileIntervalHours() : RECONCILE_INTERVAL_DEFAULT_HOURS;
        return Duration.between(lastSync, Instant.now()).toMillis() > hours * 3_600_000L;
    }

    private Instant parseInstant(String iso) {
        try {
            return Instant.parse(iso);
        } catch (Exception ignored) {
            // formato inesperado — cai pro parser mais tolerante abaixo
        }
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }
}
