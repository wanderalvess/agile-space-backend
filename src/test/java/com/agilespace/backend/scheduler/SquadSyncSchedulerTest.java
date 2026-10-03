package com.agilespace.backend.scheduler;

import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.repository.SquadRepository;
import com.agilespace.backend.service.SquadSyncGuard;
import com.agilespace.backend.service.SquadSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class SquadSyncSchedulerTest {

    @Mock private SquadRepository squadRepository;
    @Mock private SquadSyncService squadSyncService;
    @Mock private SquadSyncGuard syncGuard;

    private SquadSyncScheduler scheduler;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        scheduler = new SquadSyncScheduler(squadRepository, squadSyncService, syncGuard);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        lenient().when(syncGuard.tryAcquire(anyString())).thenReturn(true);
    }

    private Squad squad(String id, String lastSyncAt, Integer reconcileIntervalHours) {
        return Squad.builder().id(id).syncOwnerUserId("owner-" + id)
                .lastSyncAt(lastSyncAt).reconcileIntervalHours(reconcileIntervalHours).build();
    }

    @Test
    public void doesNothingWhenGloballyDisabled() {
        ReflectionTestUtils.setField(scheduler, "enabled", false);
        when(squadRepository.findBySyncOwnerUserIdIsNotNull()).thenReturn(List.of(squad("SQ1", null, null)));

        scheduler.syncDueSquads();

        verifyNoInteractions(squadSyncService);
    }

    @Test
    public void syncsSquadThatHasNeverSynced() {
        when(squadRepository.findBySyncOwnerUserIdIsNotNull()).thenReturn(List.of(squad("SQ1", null, null)));

        scheduler.syncDueSquads();

        verify(squadSyncService).syncSquad("SQ1", "owner-SQ1", false);
        verify(syncGuard).release("SQ1");
    }

    @Test
    public void skipsSquadSyncedRecentlyWithinItsReconcileInterval() {
        String recentSync = Instant.now().minusSeconds(3600).toString(); // 1h atrás, intervalo default 6h
        when(squadRepository.findBySyncOwnerUserIdIsNotNull()).thenReturn(List.of(squad("SQ1", recentSync, null)));

        scheduler.syncDueSquads();

        verifyNoInteractions(squadSyncService);
    }

    @Test
    public void syncsSquadPastItsOwnCustomReconcileInterval() {
        // intervalo custom de 1h, última sync há 2h — está vencido mesmo sendo recente
        // pelo default de 6h.
        String twoHoursAgo = Instant.now().minusSeconds(7200).toString();
        when(squadRepository.findBySyncOwnerUserIdIsNotNull()).thenReturn(List.of(squad("SQ1", twoHoursAgo, 1)));

        scheduler.syncDueSquads();

        verify(squadSyncService).syncSquad("SQ1", "owner-SQ1", false);
    }

    @Test
    public void skipsSquadAlreadyInFlight() {
        when(squadRepository.findBySyncOwnerUserIdIsNotNull()).thenReturn(List.of(squad("SQ1", null, null)));
        when(syncGuard.tryAcquire("SQ1")).thenReturn(false);

        scheduler.syncDueSquads();

        verifyNoInteractions(squadSyncService);
        verify(syncGuard, never()).release(anyString());
    }

    @Test
    public void oneSquadFailingDoesNotBlockTheOthers() {
        when(squadRepository.findBySyncOwnerUserIdIsNotNull()).thenReturn(List.of(
                squad("SQ1", null, null), squad("SQ2", null, null)));
        doThrow(new RuntimeException("Jira indisponível")).when(squadSyncService).syncSquad(eq("SQ1"), anyString(), eq(false));

        scheduler.syncDueSquads();

        verify(squadSyncService).syncSquad("SQ2", "owner-SQ2", false);
        verify(syncGuard).release("SQ1");
        verify(syncGuard).release("SQ2");
    }
}
