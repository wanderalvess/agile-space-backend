package com.agilespace.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SquadSyncGuardTest {

    @Test
    public void tryAcquire_succeedsOnceThenBlocksUntilReleased() {
        SquadSyncGuard guard = new SquadSyncGuard();

        assertTrue(guard.tryAcquire("SQ1"));
        assertFalse(guard.tryAcquire("SQ1"));

        guard.release("SQ1");
        assertTrue(guard.tryAcquire("SQ1"));
    }

    @Test
    public void tryAcquire_isIndependentPerSquad() {
        SquadSyncGuard guard = new SquadSyncGuard();

        assertTrue(guard.tryAcquire("SQ1"));
        assertTrue(guard.tryAcquire("SQ2"));
    }
}
