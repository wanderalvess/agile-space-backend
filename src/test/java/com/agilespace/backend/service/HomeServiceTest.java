package com.agilespace.backend.service;

import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.domain.SquadMetricsRollup;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.*;
import com.agilespace.backend.repository.SquadMetricsRollupRepository;
import com.agilespace.backend.repository.SquadRepository;
import com.agilespace.backend.service.SessionStatusResolver.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("HomeService - agregado restrito às squads acessíveis")
class HomeServiceTest {

    @Mock private SquadAudienceService audienceService;
    @Mock private SquadAccessService squadAccessService;
    @Mock private AnnouncementService announcementService;
    @Mock private NotificationService notificationService;
    @Mock private RitualSessionService ritualSessionService;
    @Mock private SquadRepository squadRepository;
    @Mock private SquadMetricsRollupRepository rollupRepository;
    @InjectMocks private HomeService service;

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private void ctx(boolean leadership, String current, String... squads) {
        when(audienceService.contextFor(eq("ana"), any())).thenReturn(Optional.of(new SquadAudienceService.UserContext(
                User.builder().id("ana").build(), current, List.of(squads), Set.of("MEMBER"), leadership)));
        lenient().when(announcementService.active(any(), any())).thenReturn(List.of());
        lenient().when(notificationService.unreadCount("ana")).thenReturn(UnreadCountDto.of(1, 2, 0));
        lenient().when(audienceService.squadNames(any())).thenReturn(Map.of("SQA", "Squad A", "SQB", "Squad B"));
        lenient().when(ritualSessionService.history(any(), any(), any(), any())).thenReturn(List.of());
    }

    private static LiveSessionDto live(String tool, String squad) {
        return new LiveSessionDto(tool, "id-" + tool + squad, "t", squad, null, 2, "LIVE", "/x", NOW, NOW);
    }

    @Test
    @DisplayName("Membro comum: só a squad atual, multiSquad=false, sessões e fatos da squad")
    void singleSquad() {
        ctx(false, "SQA", "SQA", "SQB");
        when(ritualSessionService.live(List.of("SQA"), NOW)).thenReturn(List.of(live("POKER", "SQA")));
        when(ritualSessionService.history(eq(Tool.POKER), eq("SQA"), anyInt(), any())).thenReturn(List.of(
                new RitualSessionDto("POKER", "r0", "Sala", "SQA", NOW, 3, "FINISHED", "60h em 6 tarefas", 45L, "/room/r0")));

        HomeDto home = service.home("ana", "MEMBER", null, NOW);

        assertFalse(home.multiSquad());
        assertEquals(1, home.squads().size());
        assertEquals("SQA", home.squads().get(0).squadId());
        assertEquals(1, home.squads().get(0).liveCount());
        assertEquals(1, home.liveSessions().size());
        assertEquals(5, home.rituals().size());
        RitualFactsDto poker = home.rituals().get(0);
        assertEquals("POKER", poker.tool());
        assertEquals(1, poker.openCount());
        assertEquals(List.of("Última: 60h em 6 tarefas", "1 aberta agora"), poker.facts());
        assertEquals(3, home.unread().total());
        verify(ritualSessionService, never()).live(eq(List.of("SQA", "SQB")), any());
    }

    @Test
    @DisplayName("Liderança com várias squads: todas, uma consulta de sessões em lote; fatos da squad atual")
    void multiSquad() {
        ctx(true, "SQA", "SQA", "SQB");
        when(ritualSessionService.live(List.of("SQA", "SQB"), NOW)).thenReturn(List.of(live("RETRO", "SQB"), live("POKER", "SQB")));
        Squad a = new Squad();
        a.setId("SQA");
        a.setActiveSprintId("41");
        when(squadRepository.findAllById(List.of("SQA", "SQB"))).thenReturn(List.of(a));
        SquadMetricsRollup r = new SquadMetricsRollup();
        r.setSquadId("SQA");
        r.setSprintId("41");
        r.setSprintName("Sprint 41");
        r.setTotalIssues(20);
        r.setDoneIssues(5);
        r.setWorkdaysRemaining(6);
        SquadMetricsRollup old = new SquadMetricsRollup();
        old.setSquadId("SQA");
        old.setSprintId("40");
        old.setSprintName("Sprint 40");
        when(rollupRepository.findBySquadIdIn(List.of("SQA", "SQB"))).thenReturn(List.of(old, r));

        HomeDto home = service.home("ana", "MEMBER", null, NOW);

        assertTrue(home.multiSquad());
        assertEquals(2, home.squads().size());
        SquadSummaryDto sa = home.squads().get(0);
        assertEquals("Sprint 41", sa.sprintName());
        assertEquals(6, sa.sprintDaysLeft());
        assertEquals(25, sa.sprintProgressPct());
        assertEquals(0, sa.liveCount());
        SquadSummaryDto sb = home.squads().get(1);
        assertNull(sb.sprintName());
        assertNull(sb.sprintProgressPct());
        assertNull(sb.nextCeremony());
        assertEquals(2, sb.liveCount());
        verify(ritualSessionService, times(1)).live(any(), any());
        verify(ritualSessionService, times(5)).history(any(), eq("SQA"), anyInt(), any());
    }

    @Test
    @DisplayName("squadId explícita: sem acesso = 403; com acesso, só ela mesmo para liderança")
    void explicitSquadAccess() {
        ctx(true, "SQA", "SQA");
        when(squadAccessService.matchesSquad("SQX", "ana", "MEMBER")).thenReturn(false);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.home("ana", "MEMBER", "SQX", NOW));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(ritualSessionService);
    }

    @Test
    @DisplayName("squadId explícita acessível por outra via (ex.: tribo): aceita e não vira multiSquad")
    void explicitSquadViaAccessService() {
        ctx(true, "SQA", "SQA", "SQB");
        when(squadAccessService.matchesSquad("SQT", "ana", "MEMBER")).thenReturn(true);
        when(ritualSessionService.live(List.of("SQT"), NOW)).thenReturn(List.of());

        HomeDto home = service.home("ana", "MEMBER", "SQT", NOW);

        assertFalse(home.multiSquad());
        assertEquals("SQT", home.squads().get(0).squadId());
    }

    @Test
    @DisplayName("Liderança escolhe uma das próprias squads: continua multiSquad com todas, fatos da escolhida")
    void explicitOwnSquadKeepsMultiSquad() {
        ctx(true, "SQA", "SQA", "SQB");
        when(ritualSessionService.live(List.of("SQA", "SQB"), NOW)).thenReturn(List.of());

        HomeDto home = service.home("ana", "MEMBER", "SQB", NOW);

        assertTrue(home.multiSquad());
        assertEquals(2, home.squads().size());
        verify(ritualSessionService, times(5)).history(any(), eq("SQB"), anyInt(), any());
    }

    @Test
    @DisplayName("Sem squad: avisos e não lidas continuam, o resto vem vazio")
    void withoutSquad() {
        ctx(false, null);
        when(ritualSessionService.live(List.of(), NOW)).thenReturn(List.of());
        HomeDto home = service.home("ana", "MEMBER", null, NOW);
        assertTrue(home.squads().isEmpty());
        assertTrue(home.rituals().isEmpty());
        assertEquals(3, home.unread().total());
    }

    @Test
    @DisplayName("Sessão inválida: 401")
    void unknownUser() {
        when(audienceService.contextFor(any(), any())).thenReturn(Optional.empty());
        assertEquals(HttpStatus.UNAUTHORIZED,
                assertThrows(ResponseStatusException.class, () -> service.home("x", "MEMBER", null, NOW)).getStatusCode());
    }

    @Test
    @DisplayName("Próxima cerimônia: só no modo manual, fuso de Brasília, próxima ocorrência")
    void nextCeremony() throws Exception {
        Squad s = new Squad();
        s.setCeremonyMode("manual");
        // 2026-10-15 é quinta-feira; 12:00Z = 09:00 em Brasília
        s.setCeremonies(JSON.readTree("[{\"title\":\"Daily\",\"daysOfWeek\":[\"MON\",\"TUE\",\"WED\",\"THU\",\"FRI\"],\"startTime\":\"09:15\"},"
                + "{\"title\":\"Planning\",\"daysOfWeek\":[\"THU\"],\"startTime\":\"14:00\"},{\"title\":\"Ruim\",\"daysOfWeek\":[\"FRI\"],\"startTime\":\"x\"}]"));

        SquadSummaryDto.NextCeremony next = HomeService.nextCeremony(s, NOW);

        assertEquals("Daily", next.name());
        assertEquals(Instant.parse("2026-10-15T12:15:00Z"), next.startsAt());

        // depois das 14h de quinta: a próxima é a Daily de sexta
        SquadSummaryDto.NextCeremony after = HomeService.nextCeremony(s, Instant.parse("2026-10-15T18:00:00Z"));
        assertEquals("Daily", after.name());
        assertEquals(Instant.parse("2026-10-16T12:15:00Z"), after.startsAt());

        s.setCeremonyMode("google_calendar");
        assertNull(HomeService.nextCeremony(s, NOW));
        assertNull(HomeService.nextCeremony(null, NOW));
    }

    @Test
    @DisplayName("Progresso: arredonda e não inventa quando não há itens")
    void progress() {
        SquadMetricsRollup r = new SquadMetricsRollup();
        r.setTotalIssues(3);
        r.setDoneIssues(1);
        assertEquals(33, HomeService.progress(r));
        r.setTotalIssues(0);
        assertNull(HomeService.progress(r));
        r.setTotalIssues(null);
        assertNull(HomeService.progress(r));
    }
}
