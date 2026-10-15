package com.agilespace.backend.service;

import com.agilespace.backend.domain.*;
import com.agilespace.backend.dto.LiveSessionDto;
import com.agilespace.backend.dto.RitualSessionDto;
import com.agilespace.backend.repository.*;
import com.agilespace.backend.service.SessionStatusResolver.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RitualSessionService - ao vivo e histórico com resumos em PT-BR")
class RitualSessionServiceTest {

    @Mock private SquadAudienceService audienceService;
    @Mock private PokerRoomRepository pokerRoomRepository;
    @Mock private PokerParticipantRepository pokerParticipantRepository;
    @Mock private PokerRoundRepository pokerRoundRepository;
    @Mock private RetroBoardRepository retroBoardRepository;
    @Mock private RetroCardRepository retroCardRepository;
    @Mock private RetroParticipantRepository retroParticipantRepository;
    @Mock private ShowcaseSessionRepository showcaseSessionRepository;
    @Mock private ShowcaseTaskRepository showcaseTaskRepository;
    @Mock private ShowcaseMemberRepository showcaseMemberRepository;
    @Mock private HealthCheckBoardRepository healthCheckBoardRepository;
    @Mock private HealthCheckParticipantRepository healthCheckParticipantRepository;
    @Mock private HealthCheckVoteRepository healthCheckVoteRepository;
    @Mock private BrainstormingBoardRepository brainstormingBoardRepository;
    @Mock private BrainstormingIdeaRepository brainstormingIdeaRepository;
    @Mock private BrainstormingParticipantRepository brainstormingParticipantRepository;

    private RitualSessionService service;
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    @BeforeEach
    void setup() {
        service = new RitualSessionService(new SessionStatusResolver(), audienceService, pokerRoomRepository,
                pokerParticipantRepository, pokerRoundRepository, retroBoardRepository, retroCardRepository,
                retroParticipantRepository, showcaseSessionRepository, showcaseTaskRepository, showcaseMemberRepository,
                healthCheckBoardRepository, healthCheckParticipantRepository, healthCheckVoteRepository,
                brainstormingBoardRepository, brainstormingIdeaRepository, brainstormingParticipantRepository);
        lenient().when(audienceService.squadNames(any())).thenReturn(Map.of("SQA", "Squad Azul"));
    }

    private static PokerRound round(String dev, String qa, boolean skipped) {
        PokerRound r = new PokerRound();
        r.setRoomId("r1");
        r.setDevPoints(dev);
        r.setQaPoints(qa);
        r.setSkipped(skipped);
        r.setCancelled(false);
        return r;
    }

    @Test
    @DisplayName("Resumo do poker: horas, pontos, camisetas e sem estimativa")
    void pokerSummaries() {
        PokerRoom hours = new PokerRoom();
        hours.setDeckType("hours");
        assertEquals("60h em 3 tarefas", RitualSessionService.pokerSummary(hours,
                List.of(round("10", "5", false), round("20", "5", false), round("20", null, false), round("99", "99", true))));
        PokerRoom fib = new PokerRoom();
        fib.setDeckType("fibonacci");
        assertEquals("13 pontos em 2 tarefas", RitualSessionService.pokerSummary(fib, List.of(round("8", null, false), round("5", null, false))));
        PokerRoom tshirt = new PokerRoom();
        tshirt.setDeckType("tshirt");
        assertEquals("2 tarefas", RitualSessionService.pokerSummary(tshirt, List.of(round("M", null, false), round("G", null, false))));
        assertEquals("1 tarefa", RitualSessionService.pokerSummary(hours, List.of(round("?", null, false))));
        assertNull(RitualSessionService.pokerSummary(hours, List.of()));
    }

    @Test
    @DisplayName("Resumos: retro, review, health e singulares")
    void otherSummaries() {
        assertEquals("18 cards · 3 ações abertas", RitualSessionService.retroSummary(18, 3));
        assertEquals("1 card · 1 ação aberta", RitualSessionService.retroSummary(1, 1));
        assertEquals("5 cards", RitualSessionService.retroSummary(5, 0));
        assertNull(RitualSessionService.retroSummary(0, 0));
        assertEquals("7 aprovadas, 1 ajuste", RitualSessionService.showcaseSummary(Map.of("approved", 7, "needs_adjustment", 1, "open", 2)));
        assertEquals("1 aprovada, 2 ajustes, 1 rejeitada", RitualSessionService.showcaseSummary(Map.of("approved", 1, "needs_adjustment", 2, "rejected", 1)));
        assertEquals("3 itens", RitualSessionService.showcaseSummary(Map.of("open", 3)));
        assertNull(RitualSessionService.showcaseSummary(Map.of()));
    }

    @Test
    @DisplayName("Ao vivo: filtra FINISHED, ordena LIVE > ASYNC_OPEN > PLANNING e preenche o nome da squad")
    void liveFiltersAndOrders() {
        PokerRoom live = room("live", "sync", "2026-10-15T11:00:00.000Z", null);
        PokerRoom stale = room("stale", "sync", "2026-10-14T08:00:00.000Z", null);
        PokerRoom async = room("async", "async", null, null);
        PokerRoom planning = room("plan", "sync", null, null);
        when(pokerRoomRepository.findOpenByTeams(eq(Set.of("SQA")), anyString(), any())).thenReturn(List.of(planning, async, stale, live));
        when(pokerParticipantRepository.summarizeByRoom(any())).thenReturn(List.<Object[]>of(new Object[]{"live", 4L, "2026-10-15T11:50:00.000Z"}));
        HealthCheckBoard hc = new HealthCheckBoard();
        hc.setId("h1");
        hc.setStatus("collecting");
        hc.setTeam("sqa");
        hc.setSprintName("Sprint 41");
        hc.setCreatedAt("2026-10-14T10:00:00.000Z");
        when(healthCheckBoardRepository.findCollectingByTeams(eq(Set.of("SQA")), anyString(), any())).thenReturn(List.of(hc));
        when(healthCheckVoteRepository.countRespondentsByBoard(any())).thenReturn(List.<Object[]>of(new Object[]{"h1", 9L}));
        RetroBoard retro = RetroBoard.builder().id("b1").title("Retro 41").squadId("SQA")
                .createdAt(ServerTime.toLocal(NOW.minusSeconds(18000))).updatedAt(ServerTime.toLocal(NOW.minusSeconds(60))).build();
        when(retroBoardRepository.findActiveBySquads(eq(Set.of("SQA")), any(), any())).thenReturn(List.of(retro));

        List<LiveSessionDto> out = service.live(List.of("SQA"), NOW);

        assertTrue(out.stream().noneMatch(d -> "stale".equals(d.id())), "LIVE abandonada some");
        assertEquals("LIVE", out.stream().filter(d -> d.tool().equals("RETRO")).findFirst().orElseThrow().status());
        assertEquals(4, out.stream().filter(d -> d.id().equals("live")).findFirst().orElseThrow().participantsCount());
        List<String> statuses = out.stream().map(LiveSessionDto::status).toList();
        assertEquals(statuses.stream().sorted(Comparator.comparingInt(s -> SessionStatusResolver.SessionStatus.valueOf(s).ordinal())).toList(), statuses);
        assertTrue(out.stream().allMatch(d -> "Squad Azul".equals(d.squadName()) && "SQA".equals(d.squadId())));
        LiveSessionDto health = out.stream().filter(d -> d.tool().equals("HEALTH_CHECK")).findFirst().orElseThrow();
        assertEquals("ASYNC_OPEN", health.status());
        assertEquals(9, health.participantsCount());
        assertEquals("/health-check/h1", health.link());
    }

    @Test
    @DisplayName("Sem squads: nenhuma consulta")
    void liveWithoutSquads() {
        assertTrue(service.live(List.of(), NOW).isEmpty());
        verifyNoInteractions(pokerRoomRepository, retroBoardRepository);
    }

    @Test
    @DisplayName("Histórico do poker: resumo em horas e duração quando há início e fim")
    void pokerHistory() {
        PokerRoom r = room("r1", "sync", "2026-10-14T10:00:00.000Z", "2026-10-14T11:30:00.000Z");
        r.setDeckType("hours");
        when(pokerRoomRepository.findByTeamIgnoreCaseOrderByCreatedAtDesc(eq("SQA"), any())).thenReturn(new PageImpl<>(List.of(r)));
        when(pokerRoundRepository.findByRoomIdIn(any())).thenReturn(List.of(round("10", "5", false), round("20", "5", false)));
        when(pokerParticipantRepository.summarizeByRoom(any())).thenReturn(List.<Object[]>of(new Object[]{"r1", 5L, null}));

        List<RitualSessionDto> out = service.history(Tool.POKER, "SQA", 10, NOW);

        assertEquals(1, out.size());
        assertEquals("40h em 2 tarefas", out.get(0).summary());
        assertEquals(90L, out.get(0).durationMinutes());
        assertEquals("FINISHED", out.get(0).status());
        assertEquals(5, out.get(0).participantsCount());
    }

    @Test
    @DisplayName("Histórico da retro: cards fora da coluna de ação e ações abertas")
    void retroHistory() {
        RetroBoard b = RetroBoard.builder().id("b1").title("Retro").squadId("SQA").createdAt(LocalDateTime.now().minusDays(3))
                .updatedAt(LocalDateTime.now().minusDays(3))
                .columns(new ArrayList<>(List.of(new RetroColumnDef("good", "Bom", "success", 0), new RetroColumnDef("act", "Ações", "action", 1)))).build();
        when(retroBoardRepository.findBySquadIdIgnoreCaseOrderByCreatedAtDesc(eq("SQA"), any())).thenReturn(List.of(b));
        when(retroParticipantRepository.countByBoard(any())).thenReturn(List.<Object[]>of(new Object[]{"b1", 6L}));
        when(retroCardRepository.countByBoardColumnAndDone(any())).thenReturn(List.of(
                new Object[]{"b1", "good", false, 18L}, new Object[]{"b1", "act", false, 3L}, new Object[]{"b1", "act", true, 2L}));

        RitualSessionDto dto = service.history(Tool.RETRO, "SQA", 10, Instant.now()).get(0);

        assertEquals("18 cards · 3 ações abertas", dto.summary());
        assertEquals("FINISHED", dto.status());
        assertNull(dto.durationMinutes());
    }

    @Test
    @DisplayName("Histórico de health e brainstorming")
    void healthAndBrainstormingHistory() {
        HealthCheckBoard hc = new HealthCheckBoard();
        hc.setId("h1");
        hc.setStatus("finished");
        hc.setTeam("SQA");
        hc.setCreatedAt("2026-10-01T10:00:00.000Z");
        when(healthCheckBoardRepository.findByTeamIgnoreCaseOrderByCreatedAtDesc(eq("SQA"), any())).thenReturn(List.of(hc));
        when(healthCheckVoteRepository.countRespondentsByBoard(any())).thenReturn(List.<Object[]>of(new Object[]{"h1", 9L}));
        assertEquals("9 respostas", service.history(Tool.HEALTH_CHECK, "SQA", 10, NOW).get(0).summary());

        BrainstormingBoard bb = new BrainstormingBoard();
        bb.setId("x1");
        bb.setTitle("Ideias");
        bb.setTeam("SQA");
        bb.setCreatedAt("2026-10-02T10:00:00.000Z");
        when(brainstormingBoardRepository.findByTeamIgnoreCaseOrderByCreatedAtDesc(eq("SQA"), any())).thenReturn(List.of(bb));
        when(brainstormingIdeaRepository.summarizeByBoard(any())).thenReturn(List.<Object[]>of(new Object[]{"x1", 24L, "2026-10-02T11:00:00.000Z"}));
        RitualSessionDto dto = service.history(Tool.BRAINSTORMING, "SQA", 10, NOW).get(0);
        assertEquals("24 ideias", dto.summary());
        assertEquals("FINISHED", dto.status());
    }

    @Test
    @DisplayName("Histórico do showcase: decisões viram resumo")
    void showcaseHistory() {
        ShowcaseSession s = new ShowcaseSession();
        s.setId("s1");
        s.setName("Review 41");
        s.setStatus("finished");
        s.setSquadName("SQA");
        s.setCreatedAt(LocalDateTime.now().minusDays(2));
        when(showcaseSessionRepository.findLatestSessionsBySquad(eq("SQA"), any())).thenReturn(List.of(s));
        when(showcaseTaskRepository.countDecisionsBySession(any())).thenReturn(List.of(
                new Object[]{"s1", "approved", 7L}, new Object[]{"s1", "needs_adjustment", 1L}));
        assertEquals("7 aprovadas, 1 ajuste", service.history(Tool.SHOWCASE, "SQA", 10, NOW).get(0).summary());
    }

    private static PokerRoom room(String id, String mode, String started, String ended) {
        PokerRoom r = new PokerRoom();
        r.setId(id);
        r.setTitle("Sala " + id);
        r.setTeam("SQA");
        r.setMode(mode);
        r.setSessionStartedAt(started);
        r.setSessionEndedAt(ended);
        r.setCreatedAt("2026-10-14T09:00:00.000Z");
        return r;
    }
}
