package com.agilespace.backend.service;

import ch.qos.logback.classic.Level;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.MembershipDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Etapa 2 do vínculo: {@link SquadAccessService} nos três modos de {@code app.membership.authz}. O legado (e o sombra,
 * que serve o legado) deve decidir como sempre; só o {@code new} decide pelos vínculos. A fixture tem uma pessoa para
 * cada origem de acesso que a regra nova elimina (roster, users.squad_id, cargo, fallback) e uma para cada que mantém.
 */
@DisplayName("SquadAccessService - autorização por squad nos modos legacy | shadow | new")
class SquadAccessServiceAuthzModesTest {

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
        // "Responsável de issue": só aparece no roster (squad_members), sem users.squad_id nem vínculo.
        world.user("roster", "MEMBER", null);
        world.rosterOf("roster", "SQ1");
        // users.squad_id solto: nenhuma linha em lugar nenhum.
        world.user("session", "MEMBER", "SQ1");
        // Cargo autodeclarado de liderança + users.squad_id, sem vínculo.
        User title = world.user("title", "MEMBER", "SQ1");
        title.setJobTitle("Tech Lead");
        // Liderança transversal da TriboA (âncora em SQ1) -> alcança SQ2, não SQ3.
        world.user("tribe", "MEMBER", "SQ1");
        world.link("r-tribe", "tribe", "SQ1", "PEOPLE_LEAD", "People Lead", true);
        // SQ4 não tem NENHUMA liderança cadastrada: bootstrap de ambiente novo.
        world.user("dev4", "MEMBER", "SQ4");
        world.link("r-dev4", "dev4", "SQ4", "DEVELOPER", "Developer", false);
        world.user("roster4", "MEMBER", null);
        world.rosterOf("roster4", "SQ4");
        world.user("outsider", "MEMBER", null);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private static boolean pick(AuthzMode mode, boolean legacy, boolean neu) {
        return mode == AuthzMode.NEW ? neu : legacy;
    }

    // ---------- matchesSquad ----------

    @ParameterizedTest
    @EnumSource(AuthzMode.class)
    @DisplayName("matchesSquad: vínculo e ADMIN/LEAD leem nos três modos; roster, users.squad_id e cargo só no legado")
    void matchesSquad(AuthzMode mode) {
        SquadAccessService access = world.access(mode);

        assertTrue(access.matchesSquad("SQ1", "admin", "ADMIN"));
        assertTrue(access.matchesSquad("SQ1", "lead", "LEAD"), "LEAD continua com acesso total até a Etapa 4");
        assertTrue(access.matchesSquad("SQ3", "lead", "LEAD"));
        assertTrue(access.matchesSquad("SQ1", "am", "MEMBER"));
        assertTrue(access.matchesSquad("SQ1", "dev", "MEMBER"));
        assertFalse(access.matchesSquad("SQ2", "dev", "MEMBER"));
        assertFalse(access.matchesSquad("SQ1", "outsider", "MEMBER"));

        // roster-only (responsável de issue), users.squad_id solto e cargo: só o legado
        assertEquals(pick(mode, true, false), access.matchesSquad("SQ1", "roster", "MEMBER"), "roster-only");
        assertEquals(pick(mode, true, false), access.matchesSquad("SQ1", "session", "MEMBER"), "users.squad_id solto");
        assertEquals(pick(mode, true, false), access.matchesSquad("SQ1", "title", "MEMBER"), "cargo + users.squad_id");
    }

    @ParameterizedTest
    @EnumSource(AuthzMode.class)
    @DisplayName("matchesSquad: expansão por tribo continua nos três modos (tribo A sim, tribo Z não)")
    void tribeExpansion(AuthzMode mode) {
        SquadAccessService access = world.access(mode);

        assertTrue(access.matchesSquad("SQ1", "tribe", "MEMBER"));
        assertTrue(access.matchesSquad("SQ2", "tribe", "MEMBER"), "mesma tribo");
        assertFalse(access.matchesSquad("SQ3", "tribe", "MEMBER"), "outra tribo");
        assertFalse(access.matchesSquad("SQ4", "tribe", "MEMBER"));
        // a expansão é derivada: não é linha própria do roster
        MembershipDto sq2 = world.membership.membershipIn(world.find("tribe").orElseThrow(), "SQ2");
        assertEquals(MembershipDto.SCOPE_TRIBE, sq2.scope());
    }

    @ParameterizedTest
    @EnumSource(AuthzMode.class)
    @DisplayName("matchesSquad: alias DDWMISSI/MISSI vale nos três modos")
    void missiAlias(AuthzMode mode) {
        world.project("DDWMISSI", "Missão", "SegM", "TriboM");
        world.user("missi", "MEMBER", null);
        world.link("r-missi", "missi", "DDWMISSI", "DEVELOPER", "Developer", false);
        SquadAccessService access = world.access(mode);

        assertTrue(access.matchesSquad("DDWMISSI", "missi", "MEMBER"));
        assertTrue(access.matchesSquad("MISSI", "missi", "MEMBER"));
        assertTrue(access.matchesSquad("ddwmissi", "missi", "MEMBER"), "sem caixa");
    }

    // ---------- canManageSquad ----------

    @ParameterizedTest
    @EnumSource(AuthzMode.class)
    @DisplayName("canManageSquad: liderança por pmr gerencia; dev não; cargo só no legado")
    void canManageSquad(AuthzMode mode) {
        SquadAccessService access = world.access(mode);

        assertTrue(access.canManageSquad("SQ1", "admin", "ADMIN"));
        assertTrue(access.canManageSquad("SQ1", "lead", "LEAD"));
        assertTrue(access.canManageSquad("SQ1", "am", "MEMBER"), "liderança por pmr");
        assertTrue(access.canManageSquad("SQ2", "tribe", "MEMBER"), "liderança transversal alcança a squad da tribo");
        assertFalse(access.canManageSquad("SQ3", "tribe", "MEMBER"));
        assertFalse(access.canManageSquad("SQ1", "dev", "MEMBER"), "SQ1 já tem liderança: dev lê, não gerencia");
        assertFalse(access.canManageSquad("SQ1", "roster", "MEMBER"));
        assertFalse(access.canManageSquad("SQ1", "session", "MEMBER"));
        assertFalse(access.canManageSquad("SQ1", "outsider", "MEMBER"));
        // cargo de liderança autodeclarado + users.squad_id (sem vínculo): só o legado
        assertEquals(pick(mode, true, false), access.canManageSquad("SQ1", "title", "MEMBER"), "jobTitle não é autoridade");
    }

    @ParameterizedTest
    @EnumSource(AuthzMode.class)
    @DisplayName("canManageSquad: squad sem liderança cadastrada - legado libera qualquer membro; new só quem tem VÍNCULO")
    void squadWithoutLeadershipFallback(AuthzMode mode) {
        SquadAccessService access = world.access(mode);

        assertTrue(access.canManageSquad("SQ4", "dev4", "MEMBER"), "tem vínculo: bootstrap vale nos três modos");
        assertEquals(pick(mode, true, false), access.canManageSquad("SQ4", "roster4", "MEMBER"), "roster-only deixa de gerenciar em new");
        assertFalse(access.canManageSquad("SQ4", "outsider", "MEMBER"));
        assertFalse(access.canManageSquad("SQ4", "dev", "MEMBER"), "vínculo em OUTRA squad não vale");
    }

    @Test
    @DisplayName("Fallback sem liderança avisa uma vez por squad (authz_squad_without_leadership), em new")
    void fallbackWarnsOncePerSquad() {
        SquadAccessService access = world.access(AuthzMode.NEW);

        assertTrue(access.canManageSquad("SQ4", "dev4", "MEMBER"));
        assertTrue(access.canManageSquad("SQ4", "dev4", "MEMBER"));
        assertTrue(access.canManageSquad("sq4", "dev4", "MEMBER"));

        List<String> warns = world.lines("authz_squad_without_leadership");
        assertEquals(1, warns.size(), warns.toString());
        assertTrue(warns.get(0).contains("squad=SQ4"));
        assertFalse(warns.get(0).contains("@"), "sem e-mail");
    }

    @Test
    @DisplayName("Squad COM liderança cadastrada não gera o aviso de bootstrap")
    void noWarningWhenSquadHasLeadership() {
        SquadAccessService access = world.access(AuthzMode.NEW);

        assertFalse(access.canManageSquad("SQ1", "dev", "MEMBER"));
        assertTrue(world.lines("authz_squad_without_leadership").isEmpty());
    }

    // ---------- isSquadLeadership ----------

    @ParameterizedTest
    @EnumSource(AuthzMode.class)
    @DisplayName("isSquadLeadership: estrita - sem o fallback; cargo só no legado; ADMIN sempre; LEAD sem pmr não")
    void isSquadLeadership(AuthzMode mode) {
        SquadAccessService access = world.access(mode);

        assertTrue(access.isSquadLeadership("SQ1", "admin", "ADMIN"));
        assertTrue(access.isSquadLeadership("SQ1", "am", "MEMBER"));
        assertTrue(access.isSquadLeadership("SQ2", "tribe", "MEMBER"));
        assertFalse(access.isSquadLeadership("SQ1", "dev", "MEMBER"));
        assertFalse(access.isSquadLeadership("SQ4", "dev4", "MEMBER"), "sem o fallback de squad sem liderança");
        assertFalse(access.isSquadLeadership("SQ1", "lead", "LEAD"), "LEAD sem pmr não é liderança ESTRITA (igual ao legado)");
        assertEquals(pick(mode, true, false), access.isSquadLeadership("SQ1", "title", "MEMBER"), "cargo");
        assertFalse(access.isSquadLeadership("", "am", "MEMBER"));
        assertFalse(access.isSquadLeadership("SQ1", null, "MEMBER"));
    }

    // ---------- modo sombra ----------

    @Test
    @DisplayName("Sombra: a decisão é a do legado e cada perda vira authz_divergence WARN com o motivo certo")
    void shadowLogsLossesWithReasonAndNeverChangesDecision() {
        SquadAccessService access = world.access(AuthzMode.SHADOW);

        assertTrue(access.matchesSquad("SQ1", "roster", "MEMBER"), "legado decide");
        assertTrue(access.matchesSquad("SQ1", "session", "MEMBER"));
        assertTrue(access.canManageSquad("SQ1", "title", "MEMBER"));
        assertTrue(access.canManageSquad("SQ4", "roster4", "MEMBER"));

        List<String> lines = world.divergenceLines();
        assertTrue(lines.contains("authz_divergence user=roster check=matchesSquad squad=SQ1 legacy=true new=false reason=ONLY_ROSTER"), lines.toString());
        assertTrue(lines.contains("authz_divergence user=session check=matchesSquad squad=SQ1 legacy=true new=false reason=ONLY_SQUAD_ID"), lines.toString());
        assertTrue(lines.contains("authz_divergence user=title check=canManageSquad squad=SQ1 legacy=true new=false reason=ONLY_JOB_TITLE"), lines.toString());
        assertTrue(lines.contains("authz_divergence user=roster4 check=canManageSquad squad=SQ4 legacy=true new=false reason=NO_LEADERSHIP_FALLBACK"), lines.toString());
        assertEquals(world.divergenceCount(Level.WARN), lines.size(), "toda perda é WARN");
        assertTrue(lines.stream().noneMatch(l -> l.contains("@")), "sem e-mail");

        assertEquals(2.0, world.meters.counter(AuthzShadow.METRIC, "check", "matchesSquad", "direction", "LOSS").count());
        assertEquals(2.0, world.meters.counter(AuthzShadow.METRIC, "check", "canManageSquad", "direction", "LOSS").count());
    }

    @Test
    @DisplayName("Sombra: sem divergência não loga nada (quem tem vínculo, ADMIN e LEAD)")
    void shadowSilentWhenBothAgree() {
        SquadAccessService access = world.access(AuthzMode.SHADOW);

        assertTrue(access.matchesSquad("SQ1", "am", "MEMBER"));
        assertTrue(access.matchesSquad("SQ1", "dev", "MEMBER"));
        assertTrue(access.matchesSquad("SQ2", "tribe", "MEMBER"));
        assertTrue(access.matchesSquad("SQ3", "lead", "LEAD"));
        assertTrue(access.canManageSquad("SQ1", "am", "MEMBER"));
        assertFalse(access.canManageSquad("SQ1", "dev", "MEMBER"));
        assertFalse(access.matchesSquad("SQ1", "outsider", "MEMBER"));
        assertTrue(access.canManageSquad("SQ4", "dev4", "MEMBER"));

        assertTrue(world.divergenceLines().isEmpty(), world.divergenceLines().toString());
    }

    @Test
    @DisplayName("Sombra: o sentido contrário (novo permite, legado nega) é INFO e GAIN")
    void shadowGainIsInfo() {
        // Um resolvedor antigo "cego" a project_member_roles força o ganho: o legado nega e o novo (vínculos) permite.
        UserProjectResolverService legacyBlind = mock(UserProjectResolverService.class);
        when(legacyBlind.resolveUserAccess(any())).thenReturn(com.agilespace.backend.dto.UserProjectAccessDto.builder().projects(List.of()).build());
        when(legacyBlind.hasRegisteredLeadership(any())).thenReturn(true);
        SquadAccessService access = new SquadAccessService(world.userRepository, world.squadService, legacyBlind,
                world.membership, world.shadow(AuthzMode.SHADOW));

        // Quem só tem vínculo (sem users.squad_id nem roster):
        world.user("onlylink", "MEMBER", null);
        world.link("r-ol", "onlylink", "SQ2", "DEVELOPER", "Developer", false);

        assertFalse(access.matchesSquad("SQ2", "onlylink", "MEMBER"), "legado (cego a pmr) nega");
        List<String> lines = world.divergenceLines();
        assertEquals(1, lines.size(), lines.toString());
        assertEquals("authz_divergence user=onlylink check=matchesSquad squad=SQ2 legacy=false new=true reason=NEW_ONLY_MEMBERSHIP", lines.get(0));
        assertEquals(1L, world.logs.list.stream().filter(e -> e.getLevel() == Level.INFO && e.getFormattedMessage().startsWith("authz_divergence")).count());
        assertEquals(1.0, world.meters.counter(AuthzShadow.METRIC, "check", "matchesSquad", "direction", "GAIN").count());
    }

    @Test
    @DisplayName("Sombra: erro no cálculo novo nunca afeta a decisão (vira authz_shadow_error)")
    void shadowErrorNeverAffectsDecision() {
        MembershipService broken = mock(MembershipService.class);
        when(broken.membershipIn(any(), any())).thenThrow(new IllegalStateException("falha simulada com dado@sensivel.com"));
        SquadAccessService access = new SquadAccessService(world.userRepository, world.squadService, world.resolver,
                broken, world.shadow(AuthzMode.SHADOW));

        assertTrue(access.matchesSquad("SQ1", "dev", "MEMBER"));
        assertTrue(access.canManageSquad("SQ1", "am", "MEMBER"));
        assertFalse(access.matchesSquad("SQ1", "outsider", "MEMBER"));

        assertFalse(world.lines("authz_shadow_error").isEmpty());
        assertTrue(world.lines("authz_shadow_error").stream().noneMatch(l -> l.contains("sensivel")), "só a classe do erro, nunca a mensagem");
        assertTrue(world.divergenceLines().isEmpty());
    }

    // ---------- flag ----------

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "nwe", "NEW ", "true", "novo", "1"})
    @DisplayName("Valor vazio ou desconhecido cai em legacy (exceto 'new' bem escrito, com espaço e caixa tolerados)")
    void unknownFlagFallsBackToLegacy(String raw) {
        AuthzMode mode = AuthzMode.parse(raw);
        if ("NEW ".equals(raw)) {
            assertEquals(AuthzMode.NEW, mode);
        } else {
            assertEquals(AuthzMode.LEGACY, mode, "erro de digitação nunca liga a regra nova");
        }
    }

    @Test
    @DisplayName("Padrão: sem valor configurado o modo é legacy; shadow e new só quando pedidos")
    void defaultIsLegacy() {
        assertEquals(AuthzMode.LEGACY, AuthzMode.parse(null));
        assertEquals(AuthzMode.SHADOW, AuthzMode.parse("shadow"));
        assertEquals(AuthzMode.NEW, AuthzMode.parse("New"));
        assertTrue(AuthzMode.isUnknown("nwe"));
        assertFalse(AuthzMode.isUnknown(""));
        assertEquals(AuthzMode.LEGACY, new SquadAccessService(world.userRepository, world.squadService, world.resolver).authzMode(),
                "montagem à mão (testes antigos) é legacy");
    }

    @Test
    @DisplayName("Em legacy a regra nova nem é calculada (zero consulta a vínculos, zero log)")
    void legacyNeverTouchesMembership() {
        MembershipService spy = spy(world.membership);
        SquadAccessService access = new SquadAccessService(world.userRepository, world.squadService, world.resolver,
                spy, world.shadow(AuthzMode.LEGACY));

        assertTrue(access.matchesSquad("SQ1", "roster", "MEMBER"));
        assertTrue(access.canManageSquad("SQ1", "am", "MEMBER"));
        assertFalse(access.isSquadLeadership("SQ1", "dev", "MEMBER"));

        verify(spy, never()).membershipIn(any(), any());
        verify(spy, never()).membershipsOf(any());
        assertTrue(world.divergenceLines().isEmpty());
    }

    // ---------- desempenho ----------

    @Test
    @DisplayName("new: 200 checagens do mesmo usuário carregam os vínculos UMA vez (cache), sem N+1")
    void membershipCacheAvoidsRepeatedQueries() {
        SquadAccessService access = world.access(AuthzMode.NEW);

        for (int i = 0; i < 200; i++) {
            assertTrue(access.matchesSquad("SQ1", "dev", "MEMBER"));
            assertFalse(access.matchesSquad("SQ3", "dev", "MEMBER"));
        }

        verify(world.pmrRepository, times(1)).findByEmailIgnoreCase("dev@totvs.com.br");
        verify(world.pmrRepository, times(1)).findByUserId("dev");
        verify(world.rosterRepository, never()).findByClaimedByUid(any());
        verify(world.rosterRepository, never()).findByUserIdentifier(any());
        verify(world.squadService, never()).getMembers(any());
    }

    // ---------- as regras puras cruzam com as decisões reais ----------

    @Test
    @DisplayName("AuthzRules (usada no authz-diff) concorda com as decisões reais do SquadAccessService, legado e novo, para todo par")
    void pureRulesMatchRealDecisions() {
        SquadAccessService legacy = world.access(AuthzMode.LEGACY);
        SquadAccessService modern = world.access(AuthzMode.NEW);
        List<String> squads = List.of("SQ1", "SQ2", "SQ3", "SQ4");

        for (User u : world.users) {
            if ("ADMIN".equals(u.getRole()) || "LEAD".equals(u.getRole())) continue;
            for (String sq : squads) {
                AuthzRules.Facts f = legacy.factsFor(u, sq);
                String label = u.getId() + "/" + sq;
                assertEquals(legacy.matchesSquad(sq, u.getId(), u.getRole()), AuthzRules.legacyMatches(f), "legacy matches " + label);
                assertEquals(legacy.canManageSquad(sq, u.getId(), u.getRole()), AuthzRules.legacyManage(f), "legacy manage " + label);
                assertEquals(legacy.isSquadLeadership(sq, u.getId(), u.getRole()), AuthzRules.legacyLeadership(f), "legacy leader " + label);
                assertEquals(modern.matchesSquad(sq, u.getId(), u.getRole()), AuthzRules.newMatches(f), "new matches " + label);
                assertEquals(modern.canManageSquad(sq, u.getId(), u.getRole()), AuthzRules.newManage(f), "new manage " + label);
                assertEquals(modern.isSquadLeadership(sq, u.getId(), u.getRole()), AuthzRules.newLeadership(f), "new leader " + label);
            }
        }
    }
}
