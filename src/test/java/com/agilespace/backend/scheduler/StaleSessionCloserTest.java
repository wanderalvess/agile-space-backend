package com.agilespace.backend.scheduler;

import com.agilespace.backend.domain.PokerRoom;
import com.agilespace.backend.domain.ShowcaseSession;
import com.agilespace.backend.repository.PokerRoomRepository;
import com.agilespace.backend.repository.PokerRoundRepository;
import com.agilespace.backend.repository.PokerVoteRepository;
import com.agilespace.backend.repository.ShowcaseSessionRepository;
import com.agilespace.backend.service.ShowcaseSessionService;
import com.agilespace.backend.websocket.PokerWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StaleSessionCloser - encerra na virada do dia a sessão ao vivo que ficou aberta")
class StaleSessionCloserTest {

    // 03/11/2026 15:00 em São Paulo (UTC-3): o dia começou às 03:00Z.
    private static final Instant NOW = Instant.parse("2026-11-03T18:00:00Z");
    private static final String TODAY_START = "2026-11-03T03:00:00Z";

    @Mock private PokerRoomRepository roomRepository;
    @Mock private PokerVoteRepository voteRepository;
    @Mock private PokerRoundRepository roundRepository;
    @Mock private PokerWebSocketHandler pokerSocket;
    @Mock private ShowcaseSessionRepository showcaseRepository;
    @Mock private ShowcaseSessionService showcaseService;
    @Mock private PlatformTransactionManager txManager;

    private StaleSessionCloser closer;

    @BeforeEach
    void setup() {
        lenient().when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        closer = new StaleSessionCloser(roomRepository, voteRepository, roundRepository, pokerSocket,
                showcaseRepository, showcaseService, new TransactionTemplate(txManager));
        ReflectionTestUtils.setField(closer, "zone", "America/Sao_Paulo");
    }

    private static PokerRoom room(String id, String startedAt) {
        PokerRoom r = new PokerRoom();
        r.setId(id);
        r.setSessionStartedAt(startedAt);
        return r;
    }

    @Test
    @DisplayName("o dia começa à meia-noite do fuso configurado, não do UTC")
    void startOfDay() {
        assertEquals(Instant.parse(TODAY_START), closer.startOfDay(NOW));
        // 01:00 UTC de 04/11 ainda é 22:00 de 03/11 em São Paulo: o dia não virou.
        assertEquals(Instant.parse(TODAY_START), closer.startOfDay(Instant.parse("2026-11-04T01:00:00Z")));
    }

    @Test
    @DisplayName("sala iniciada ontem e sem atividade hoje é encerrada com o instante da última atividade")
    void closesYesterdaysRoom() {
        PokerRoom stale = room("r1", "2026-11-02T14:00:00.000Z");
        when(roomRepository.findStartedAndOpen(any(Pageable.class))).thenReturn(List.of(stale));
        when(voteRepository.latestTimestamp("r1")).thenReturn("2026-11-02T15:30:00.000Z");
        when(roundRepository.latestTimestamp("r1")).thenReturn("2026-11-02T15:45:00.000Z");
        when(roomRepository.findByIdForUpdate("r1")).thenReturn(Optional.of(stale));
        when(roomRepository.saveAndFlush(stale)).thenReturn(stale);

        assertEquals(1, closer.closeStale(NOW));

        assertEquals("2026-11-02T15:45:00.000Z", stale.getSessionEndedAt());
        verify(pokerSocket).broadcastEvent("r1", "ROOM_UPDATED", stale);
    }

    @Test
    @DisplayName("sala com voto hoje não é encerrada, mesmo tendo começado ontem")
    void keepsRoomWithActivityToday() {
        PokerRoom live = room("r2", "2026-11-02T23:50:00.000Z");
        when(roomRepository.findStartedAndOpen(any(Pageable.class))).thenReturn(List.of(live));
        when(voteRepository.latestTimestamp("r2")).thenReturn("2026-11-03T12:00:00.000Z");

        assertEquals(0, closer.closeStale(NOW));

        assertNull(live.getSessionEndedAt());
        verify(roomRepository, never()).findByIdForUpdate(any());
        verifyNoInteractions(pokerSocket);
    }

    @Test
    @DisplayName("sala iniciada hoje, sem votos ainda, continua aberta")
    void keepsRoomStartedToday() {
        PokerRoom today = room("r3", "2026-11-03T13:00:00.000Z");
        when(roomRepository.findStartedAndOpen(any(Pageable.class))).thenReturn(List.of(today));

        assertEquals(0, closer.closeStale(NOW));

        assertNull(today.getSessionEndedAt());
    }

    @Test
    @DisplayName("sala encerrada por outro no meio do caminho não é regravada nem avisada")
    void skipsRoomEndedMeanwhile() {
        PokerRoom stale = room("r4", "2026-11-01T14:00:00.000Z");
        PokerRoom lockedCopy = room("r4", "2026-11-01T14:00:00.000Z");
        lockedCopy.setSessionEndedAt("2026-11-01T16:00:00.000Z");
        when(roomRepository.findStartedAndOpen(any(Pageable.class))).thenReturn(List.of(stale));
        when(roomRepository.findByIdForUpdate("r4")).thenReturn(Optional.of(lockedCopy));

        assertEquals(0, closer.closeStale(NOW));

        verify(roomRepository, never()).saveAndFlush(any());
        verifyNoInteractions(pokerSocket);
    }

    @Test
    @DisplayName("uma sala com erro não impede as demais de serem encerradas")
    void oneFailureDoesNotStopTheRest() {
        PokerRoom bad = room("bad", "2026-11-01T14:00:00.000Z");
        PokerRoom good = room("good", "2026-11-01T15:00:00.000Z");
        when(roomRepository.findStartedAndOpen(any(Pageable.class))).thenReturn(List.of(bad, good));
        when(roomRepository.findByIdForUpdate("bad")).thenThrow(new IllegalStateException("boom"));
        when(roomRepository.findByIdForUpdate("good")).thenReturn(Optional.of(good));
        when(roomRepository.saveAndFlush(good)).thenReturn(good);

        assertEquals(1, closer.closeStale(NOW));

        assertEquals("2026-11-01T15:00:00.000Z", good.getSessionEndedAt());
    }

    @Test
    @DisplayName("Review ativa parada desde ontem é encerrada")
    void closesIdleReview() {
        ShowcaseSession s = new ShowcaseSession();
        s.setId("s1");
        when(roomRepository.findStartedAndOpen(any(Pageable.class))).thenReturn(List.of());
        when(showcaseRepository.findActiveIdleBefore(any(LocalDateTime.class), any(Pageable.class))).thenReturn(List.of(s));
        when(showcaseService.closeAbandoned(eq("s1"), any(LocalDateTime.class))).thenReturn(Optional.of(s));

        assertEquals(1, closer.closeStale(NOW));

        verify(showcaseService).closeAbandoned(eq("s1"), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("Review que mudou no meio do caminho (service devolve vazio) não conta como encerrada")
    void reviewChangedMeanwhile() {
        ShowcaseSession s = new ShowcaseSession();
        s.setId("s2");
        when(roomRepository.findStartedAndOpen(any(Pageable.class))).thenReturn(List.of());
        when(showcaseRepository.findActiveIdleBefore(any(LocalDateTime.class), any(Pageable.class))).thenReturn(List.of(s));
        when(showcaseService.closeAbandoned(eq("s2"), any(LocalDateTime.class))).thenReturn(Optional.empty());

        assertEquals(0, closer.closeStale(NOW));
    }

    @Test
    @DisplayName("com a flag desligada a rodada agendada não faz nada")
    void disabledDoesNothing() {
        ReflectionTestUtils.setField(closer, "enabled", false);

        closer.run();

        verifyNoInteractions(roomRepository, showcaseRepository, showcaseService, pokerSocket);
    }
}
