package com.agilespace.backend.websocket;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class PresenceRegistryTest {

    private static final PresenceModule M = PresenceModule.RETRO;

    @Test
    void firstSessionOfUserPutsThemOnline() {
        PresenceRegistry r = new PresenceRegistry();

        PresenceRegistry.Change c = r.join(M, "room", "ana", "s1", null);

        assertTrue(c.changed());
        assertEquals(List.of("ana"), c.snapshot().online());
        assertEquals(1, c.snapshot().total());
        assertEquals(List.of("ana"), r.snapshot(M, "room").online());
    }

    @Test
    void secondTabIsTheSamePresenceAndChangesNothing() {
        PresenceRegistry r = new PresenceRegistry();
        PresenceRegistry.Change first = r.join(M, "room", "ana", "s1", null);

        PresenceRegistry.Change second = r.join(M, "room", "ana", "s2", null);

        assertFalse(second.changed());
        assertEquals(List.of("ana"), second.snapshot().online());
        assertEquals(first.snapshot().version(), second.snapshot().version());
        assertEquals(2, r.sessionCount(M));
    }

    @Test
    void userStaysOnlineUntilTheLastSessionLeaves() {
        PresenceRegistry r = new PresenceRegistry();
        r.join(M, "room", "ana", "s1", null);
        r.join(M, "room", "ana", "s2", null);
        r.join(M, "room", "bia", "s3", null);

        PresenceRegistry.Change one = r.leave(M, "room", "ana", "s1");
        assertFalse(one.changed());
        assertEquals(List.of("ana", "bia"), r.snapshot(M, "room").online());

        PresenceRegistry.Change last = r.leave(M, "room", "ana", "s2");
        assertTrue(last.changed());
        assertEquals(List.of("bia"), last.snapshot().online());
        assertEquals(1, r.sessionCount(M));
    }

    @Test
    void emptyRoomIsRemovedFromMemory() {
        PresenceRegistry r = new PresenceRegistry();
        r.join(M, "room", "ana", "s1", null);
        r.join(PresenceModule.POKER, "room", "ana", "s9", null);
        assertEquals(2, r.roomCount());

        PresenceRegistry.Change c = r.leave(M, "room", "ana", "s1");

        assertTrue(c.changed());
        assertEquals(List.of(), c.snapshot().online());
        assertEquals(1, r.roomCount());
        assertEquals(List.of(), r.snapshot(M, "room").online());
        r.leave(PresenceModule.POKER, "room", "ana", "s9");
        assertEquals(0, r.roomCount());
        assertEquals(0, r.sessionCount(M));
        assertEquals(0, r.sessionCount(PresenceModule.POKER));
    }

    @Test
    void modulesAndRoomsAreIndependent() {
        PresenceRegistry r = new PresenceRegistry();
        r.join(PresenceModule.POKER, "x", "ana", "s1", null);
        r.join(PresenceModule.RETRO, "x", "bia", "s2", null);
        r.join(PresenceModule.RETRO, "y", "caio", "s3", null);

        assertEquals(List.of("ana"), r.snapshot(PresenceModule.POKER, "x").online());
        assertEquals(List.of("bia"), r.snapshot(PresenceModule.RETRO, "x").online());
        assertEquals(List.of("caio"), r.snapshot(PresenceModule.RETRO, "y").online());
    }

    @Test
    void joiningAndLeavingTwiceIsIdempotent() {
        PresenceRegistry r = new PresenceRegistry();
        r.join(M, "room", "ana", "s1", null);
        PresenceRegistry.Change again = r.join(M, "room", "ana", "s1", null);
        assertFalse(again.changed());
        assertEquals(1, r.sessionCount(M));

        assertTrue(r.leave(M, "room", "ana", "s1").changed());
        PresenceRegistry.Change twice = r.leave(M, "room", "ana", "s1");
        assertFalse(twice.changed());
        assertEquals(0, r.sessionCount(M));
        assertFalse(r.leave(M, "never", "ana", "s1").changed());
        assertEquals(0, r.roomCount());
    }

    @Test
    void leaveOfUnknownSessionOfKnownUserKeepsThem() {
        PresenceRegistry r = new PresenceRegistry();
        r.join(M, "room", "ana", "s1", null);

        assertFalse(r.leave(M, "room", "ana", "other").changed());
        assertFalse(r.leave(M, "room", "bia", "s1").changed());
        assertEquals(List.of("ana"), r.snapshot(M, "room").online());
        assertEquals(1, r.sessionCount(M));
    }

    @Test
    void onlineListKeepsJoinOrderAndVersionOnlyGrows() {
        PresenceRegistry r = new PresenceRegistry();
        long v1 = r.join(M, "room", "caio", "s1", null).snapshot().version();
        long v2 = r.join(M, "room", "ana", "s2", null).snapshot().version();
        long v3 = r.join(M, "room", "bia", "s3", null).snapshot().version();
        long v4 = r.leave(M, "room", "ana", "s2").snapshot().version();

        assertEquals(List.of("caio", "bia"), r.snapshot(M, "room").online());
        assertTrue(v1 < v2 && v2 < v3 && v3 < v4);
    }

    @Test
    void blankInputsAreIgnored() {
        PresenceRegistry r = new PresenceRegistry();
        assertFalse(r.join(M, "room", null, "s1", null).changed());
        assertFalse(r.join(M, "room", " ", "s1", null).changed());
        assertFalse(r.join(M, "", "ana", "s1", null).changed());
        assertFalse(r.join(M, "room", "ana", null, null).changed());
        assertFalse(r.join(null, "room", "ana", "s1", null).changed());
        assertEquals(0, r.roomCount());
    }

    @Test
    void sweepRemovesDeadSessionsAndReportsOnlyRoomsWhereSomeoneLeft() {
        PresenceRegistry r = new PresenceRegistry();
        AtomicBoolean anaAlive = new AtomicBoolean(true);
        AtomicBoolean biaTabAlive = new AtomicBoolean(true);
        r.join(M, "room-a", "ana", "s1", anaAlive::get);
        r.join(M, "room-a", "bia", "s2", () -> true);
        r.join(M, "room-a", "bia", "s3", biaTabAlive::get);
        r.join(M, "room-b", "caio", "s4", () -> true);

        assertTrue(r.sweepDeadSessions().isEmpty());

        biaTabAlive.set(false);
        assertTrue(r.sweepDeadSessions().isEmpty(), "bia ainda tem outra aba: a lista não mudou");
        assertEquals(3, r.sessionCount(M));

        anaAlive.set(false);
        List<PresenceRegistry.Change> changes = r.sweepDeadSessions();

        assertEquals(1, changes.size());
        assertEquals("room-a", changes.get(0).snapshot().roomId());
        assertEquals(List.of("bia"), changes.get(0).snapshot().online());
        assertEquals(2, r.sessionCount(M));
    }

    @Test
    void sweepDropsRoomWhenEveryoneIsDeadAndTreatsFailingProbeAsDead() {
        PresenceRegistry r = new PresenceRegistry();
        r.join(M, "room", "ana", "s1", () -> false);
        r.join(M, "room", "bia", "s2", () -> {
            throw new IllegalStateException("socket quebrado");
        });

        List<PresenceRegistry.Change> changes = r.sweepDeadSessions();

        assertEquals(1, changes.size());
        assertEquals(List.of(), changes.get(0).snapshot().online());
        assertEquals(0, r.roomCount());
        assertEquals(0, r.sessionCount(M));
    }

    @Test
    void gaugeReportsOpenSessionsPerModule() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PresenceRegistry r = new PresenceRegistry(meters);

        r.join(PresenceModule.POKER, "a", "ana", "s1", null);
        r.join(PresenceModule.POKER, "a", "ana", "s2", null);
        r.join(PresenceModule.HEALTH_CHECK, "b", "bia", "s3", null);

        assertEquals(2.0, meters.get("presence.sessions").tag("module", "poker").gauge().value());
        assertEquals(1.0, meters.get("presence.sessions").tag("module", "health-check").gauge().value());
        assertEquals(0.0, meters.get("presence.sessions").tag("module", "retro").gauge().value());
        r.leave(PresenceModule.POKER, "a", "ana", "s1");
        assertEquals(1.0, meters.get("presence.sessions").tag("module", "poker").gauge().value());
    }

    @Test
    void concurrentJoinsAndLeavesLeaveNothingBehind() throws Exception {
        PresenceRegistry r = new PresenceRegistry();
        int threads = 16;
        int perThread = 400;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            futures.add(pool.submit(() -> {
                Random rnd = new Random(id);
                start.await();
                for (int i = 0; i < perThread; i++) {
                    String room = "room-" + rnd.nextInt(3);
                    String user = "user-" + rnd.nextInt(5);
                    String session = "t" + id + "-" + i;
                    PresenceRegistry.Change joined = r.join(M, room, user, session, null);
                    assertTrue(joined.snapshot().online().contains(user));
                    r.leave(M, room, user, session);
                }
                return null;
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(0, r.roomCount(), "toda sala esvaziada tem de sair do mapa");
        assertEquals(0, r.sessionCount(M));
        for (int i = 0; i < 3; i++) assertEquals(List.of(), r.snapshot(M, "room-" + i).online());
    }

    @Test
    void concurrentSessionsOfTheSameUserKeepThemOnlineUntilAllLeave() throws Exception {
        PresenceRegistry r = new PresenceRegistry();
        int tabs = 50;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<Boolean>> joins = new ArrayList<>();
        for (int i = 0; i < tabs; i++) {
            final int n = i;
            joins.add(pool.submit(() -> {
                start.await();
                return r.join(M, "room", "ana", "s" + n, null).changed();
            }));
        }
        start.countDown();
        int changedCount = 0;
        for (java.util.concurrent.Future<Boolean> f : joins) if (f.get(10, TimeUnit.SECONDS)) changedCount++;

        assertEquals(1, changedCount, "só a primeira aba muda a lista");
        assertEquals(tabs, r.sessionCount(M));

        List<java.util.concurrent.Future<Boolean>> leaves = new ArrayList<>();
        for (int i = 0; i < tabs; i++) {
            final int n = i;
            leaves.add(pool.submit(() -> r.leave(M, "room", "ana", "s" + n).changed()));
        }
        changedCount = 0;
        for (java.util.concurrent.Future<Boolean> f : leaves) if (f.get(10, TimeUnit.SECONDS)) changedCount++;
        pool.shutdown();

        assertEquals(1, changedCount, "só a última saída muda a lista");
        assertEquals(0, r.roomCount());
    }
}
