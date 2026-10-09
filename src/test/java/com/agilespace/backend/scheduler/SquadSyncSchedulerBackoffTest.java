package com.agilespace.backend.scheduler;

import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.repository.SquadRepository;
import com.agilespace.backend.service.SquadSyncGuard;
import com.agilespace.backend.service.SquadSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("SquadSyncScheduler - espera depois de uma falha")
public class SquadSyncSchedulerBackoffTest {

    @Mock private SquadRepository squadRepository;
    @Mock private SquadSyncService squadSyncService;
    @Mock private SquadSyncGuard syncGuard;

    private SquadSyncScheduler scheduler;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        scheduler = new SquadSyncScheduler(squadRepository, squadSyncService, syncGuard);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        when(syncGuard.tryAcquire(anyString())).thenReturn(true);
        when(squadRepository.findBySyncOwnerUserIdIsNotNull()).thenReturn(
                List.of(Squad.builder().id("SQ1").syncOwnerUserId("owner").build()));
    }

    @Test
    @DisplayName("Squad que falhou não é tentada de novo no tick seguinte")
    void failedSquadIsNotRetriedOnNextTick() {
        doThrow(new RuntimeException("token expirado")).when(squadSyncService).syncSquad("SQ1", "owner", false);

        scheduler.syncDueSquads();
        scheduler.syncDueSquads();

        verify(squadSyncService, times(1)).syncSquad("SQ1", "owner", false);
    }

    @Test
    @DisplayName("Squad que sincronizou com sucesso continua elegível no tick seguinte")
    void successfulSquadStaysEligible() {
        scheduler.syncDueSquads();
        scheduler.syncDueSquads();

        verify(squadSyncService, times(2)).syncSquad("SQ1", "owner", false);
    }
}
