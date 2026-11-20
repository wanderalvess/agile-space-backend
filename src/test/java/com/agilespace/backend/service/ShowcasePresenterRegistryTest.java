package com.agilespace.backend.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class ShowcasePresenterRegistryTest {

    /** Relógio que anda só quando o teste manda. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-17T12:00:00Z");

        void advanceMinutes(long m) {
            now = now.plusSeconds(m * 60);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void publishReplacesAndClearRemoves() {
        ShowcasePresenterRegistry registry = new ShowcasePresenterRegistry(60, new MutableClock());

        registry.publish("s1", "ana", "Ana", "c1", "card");
        registry.publish("s1", "bia", "Bia", "c2", "summary");

        assertEquals("bia", registry.current("s1").orElseThrow().userId());
        assertEquals("c2", registry.current("s1").orElseThrow().cardId());
        assertTrue(registry.current("outra").isEmpty());
        assertTrue(registry.clear("s1"));
        assertFalse(registry.clear("s1"));
        assertTrue(registry.current("s1").isEmpty());
    }

    @Test
    void publicationExpiresWithoutRenewalAndRenewalKeepsItAlive() {
        MutableClock clock = new MutableClock();
        ShowcasePresenterRegistry registry = new ShowcasePresenterRegistry(60, clock);

        registry.publish("s1", "ana", "Ana", "c1", null);
        clock.advanceMinutes(45);
        assertTrue(registry.current("s1").isPresent());
        registry.publish("s1", "ana", "Ana", "c2", null); // renovou
        clock.advanceMinutes(45);
        assertTrue(registry.current("s1").isPresent(), "90 min depois da primeira, mas só 45 depois da renovação");
        clock.advanceMinutes(30);
        assertTrue(registry.current("s1").isEmpty(), "75 min sem renovar passa dos 60");
        assertEquals(0, registry.size(), "a leitura descarta a vencida");
    }

    @Test
    void clearingAnExpiredPublicationReportsNothingWasActive() {
        MutableClock clock = new MutableClock();
        ShowcasePresenterRegistry registry = new ShowcasePresenterRegistry(10, clock);
        registry.publish("s1", "ana", "Ana", null, null);
        clock.advanceMinutes(11);

        assertFalse(registry.clear("s1"));
    }

    @Test
    void publishingPurgesOtherExpiredRoomsSoMemoryDoesNotGrow() {
        MutableClock clock = new MutableClock();
        ShowcasePresenterRegistry registry = new ShowcasePresenterRegistry(10, clock);
        for (int i = 0; i < 50; i++) {
            registry.publish("velha-" + i, "u", "U", null, null);
        }
        clock.advanceMinutes(11);

        registry.publish("nova", "u", "U", null, null);

        assertEquals(1, registry.size());
    }

    @Test
    void invalidTtlFallsBackToTheDefault() {
        ShowcasePresenterRegistry registry = new ShowcasePresenterRegistry(0, Clock.systemDefaultZone());

        registry.publish("s1", "ana", "Ana", null, null);

        assertTrue(registry.current("s1").isPresent());
    }

    @Test
    void nullSessionIsHarmless() {
        ShowcasePresenterRegistry registry = new ShowcasePresenterRegistry(60, new MutableClock());

        assertTrue(registry.current(null).isEmpty());
        assertFalse(registry.clear(null));
    }
}
