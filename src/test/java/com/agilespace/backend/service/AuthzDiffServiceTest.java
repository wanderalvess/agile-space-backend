package com.agilespace.backend.service;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.AuthzDiffDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("AuthzDiffService - legado x novo para todos os usuários x squads, somente leitura")
class AuthzDiffServiceTest {

    private AuthzWorld world;

    @BeforeEach
    void setUp() {
        world = new AuthzWorld();
        world.project("SQ1", "Alfa", "SegA", "TriboA")
                .project("SQ2", "Beta", "SegA", "TriboA")
                .project("SQ3", "Gama", "SegZ", "TriboZ")
                .project("SQ4", "Delta (sem liderança)", "SegZ", "TriboZ");
        world.user("admin", "ADMIN", null);
        world.user("lead", "LEAD", null);
        world.user("am", "MEMBER", "SQ1");
        world.link("r-am", "am", "SQ1", "AGILE_MASTER", "Agile Master", true);
        world.user("dev", "MEMBER", "SQ1");
        world.link("r-dev", "dev", "SQ1", "DEVELOPER", "Developer", false);
        world.user("roster", "MEMBER", null);
        world.rosterOf("roster", "SQ1");
        world.user("session", "MEMBER", "SQ1");
        User title = world.user("title", "MEMBER", "SQ1");
        title.setJobTitle("Tech Lead");
        world.user("tribe", "MEMBER", "SQ1");
        world.link("r-tribe", "tribe", "SQ1", "PEOPLE_LEAD", "People Lead", true);
        world.user("dev4", "MEMBER", "SQ4");
        world.link("r-dev4", "dev4", "SQ4", "DEVELOPER", "Developer", false);
        world.user("roster4", "MEMBER", null);
        world.rosterOf("roster4", "SQ4");
        world.user("outsider", "MEMBER", null);
        // inativo nunca entra na comparação
        world.user("inativo", "MEMBER", "SQ1").setActive(false);
        // squad que só existe no roster (sem linha em projects) também perde o acesso de leitura
        world.user("ghost", "MEMBER", null);
        world.rosterOf("ghost", "SO_ROSTER");
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    @DisplayName("Conta perdas por sentido, ponto e motivo e entrega amostra sem e-mail")
    void report() {
        AuthzDiffDto report = world.diff().report();

        assertEquals(10, report.usersChecked(), "sem ADMIN/LEAD e sem inativo");
        assertEquals(2, report.usersSkippedPrivileged());
        assertEquals(0, report.usersFailed());
        assertEquals(20, report.sampleLimit());
        assertEquals(0L, report.totals().get("GAIN"));
        // roster, session, title, roster4 e ghost perdem a leitura; title, roster4 e ghost perdem a gestão; title perde a liderança estrita
        assertEquals(5L, report.byCheck().get("matchesSquad").get("LOSS"));
        assertEquals(3L, report.byCheck().get("canManageSquad").get("LOSS"));
        assertEquals(1L, report.byCheck().get("isSquadLeadership").get("LOSS"));
        assertEquals(9L, report.totals().get("LOSS"));
        assertEquals(3L, report.byReason().get("LOSS:ONLY_ROSTER"), "roster, roster4 e ghost (squad só do roster)");
        assertEquals(2L, report.byReason().get("LOSS:ONLY_SQUAD_ID"));
        assertEquals(2L, report.byReason().get("LOSS:ONLY_JOB_TITLE"));
        assertEquals(2L, report.byReason().get("LOSS:NO_LEADERSHIP_FALLBACK"), "roster4 e ghost: squad sem liderança");
        assertEquals(5, report.usersWithDivergence(), "roster, session, title, roster4, ghost");
        assertEquals(9, report.sample().size());
        assertFalse(report.truncated());
        assertTrue(report.sample().stream().anyMatch(s -> "ghost".equals(s.userId()) && "SO_ROSTER".equals(s.squad())));
        assertTrue(report.sample().stream().noneMatch(s -> "dev".equals(s.userId()) || "am".equals(s.userId()) || "tribe".equals(s.userId())),
                "quem tem vínculo (inclusive a liderança de tribo) não aparece");
        assertTrue(report.sample().stream().noneMatch(s -> String.valueOf(s).contains("@")));
    }

    @Test
    @DisplayName("Limita a amostra em 20 e marca truncated, mas as contagens continuam exatas")
    void sampleIsLimited() {
        for (int i = 0; i < 30; i++) {
            world.user("so-roster-" + i, "MEMBER", null);
            world.rosterOf("so-roster-" + i, "SQ1");
        }

        AuthzDiffDto report = world.diff().report();

        assertEquals(20, report.sample().size());
        assertTrue(report.truncated());
        assertEquals(39L, report.totals().get("LOSS"));
        assertEquals(33L, report.byReason().get("LOSS:ONLY_ROSTER"));
    }

    @Test
    @DisplayName("Somente leitura: não grava em nenhum repositório e não popula o cache de vínculos")
    void writesNothing() {
        world.diff().report();
        world.diff().wouldLoseAccess();

        verify(world.userRepository, never()).save(any());
        verify(world.userRepository, never()).saveAll(any());
        verify(world.userRepository, never()).delete(any());
        verify(world.pmrRepository, never()).save(any());
        verify(world.pmrRepository, never()).saveAll(any());
        verify(world.pmrRepository, never()).delete(any());
        verify(world.rosterRepository, never()).save(any());
        assertEquals(0, world.cache.size());
    }

    @Test
    @DisplayName("wouldLoseAccess devolve só o sentido LOSS, com e-mail para o relatório de exceções")
    void wouldLoseAccessOnlyLosses() {
        List<AuthzDiffService.Row> rows = world.diff().wouldLoseAccess();

        assertEquals(9, rows.size());
        assertTrue(rows.stream().allMatch(r -> "LOSS".equals(r.direction())));
        assertTrue(rows.stream().anyMatch(r -> "roster".equals(r.userId()) && "roster@totvs.com.br".equals(r.email())
                && "SQ1".equals(r.squad()) && "matchesSquad".equals(r.check()) && "ONLY_ROSTER".equals(r.reason())));
    }

    @Test
    @DisplayName("Falha de um usuário não derruba o relatório: conta em usersFailed e segue")
    void failureIsolated() {
        when(world.rosterRepository.findByClaimedByUid("roster")).thenThrow(new IllegalStateException("falha"));

        AuthzDiffDto report = world.diff().report();

        assertEquals(1, report.usersFailed());
        assertEquals(9, report.usersChecked() - report.usersFailed());
    }
}
