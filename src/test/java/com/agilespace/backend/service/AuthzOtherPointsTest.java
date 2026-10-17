package com.agilespace.backend.service;

import ch.qos.logback.classic.Level;
import com.agilespace.backend.controller.InviteController;
import com.agilespace.backend.domain.Invite;
import com.agilespace.backend.domain.JoltProject;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.InviteRepository;
import com.agilespace.backend.repository.JoltProjectRepository;
import com.agilespace.backend.repository.JoltProjectVersionRepository;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Etapa 2 do vínculo: os demais pontos de autorização que passaram a ler a chave {@code app.membership.authz}
 * (público da home/avisos, convites, Jolt com N squads, squad "do usuário" do Conhecimento/chave de API).
 * Mesma fixture de {@link SquadAccessServiceAuthzModesTest}: {@code legacy} e {@code shadow} servem o legado; {@code new}
 * só enxerga vínculos.
 */
@DisplayName("Autorização por squad - público, convites, Jolt e squad ativa nos modos legacy | shadow | new")
class AuthzOtherPointsTest {

    private AuthzWorld world;

    @BeforeEach
    void setUp() {
        world = new AuthzWorld();
        world.project("SQ1", "Alfa", "SegA", "TriboA")
                .project("SQ2", "Beta", "SegA", "TriboA")
                .project("SQ3", "Gama", "SegZ", "TriboZ");
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
        world.user("outsider", "MEMBER", null);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private static <T> T pick(AuthzMode mode, T legacy, T neu) {
        return mode == AuthzMode.NEW ? neu : legacy;
    }

    // ---------- SquadAudienceService.contextFor ----------

    @ParameterizedTest
    @EnumSource(AuthzMode.class)
    @DisplayName("contextFor: roster, users.squad_id e cargo só valem no legado; vínculo vale em todos")
    void audienceContext(AuthzMode mode) {
        SquadAudienceService audience = world.audience(mode);

        SquadAudienceService.UserContext am = audience.contextFor("am", "MEMBER").orElseThrow();
        assertEquals(List.of("SQ1"), am.squadIds());
        assertTrue(am.leadership());
        assertTrue(am.roleKeys().contains("AGILE_MASTER"));

        SquadAudienceService.UserContext roster = audience.contextFor("roster", "MEMBER").orElseThrow();
        assertEquals(pick(mode, true, false), roster.hasSquad("SQ1"), "roster-only");

        SquadAudienceService.UserContext session = audience.contextFor("session", "MEMBER").orElseThrow();
        assertEquals(pick(mode, true, false), session.hasSquad("SQ1"), "users.squad_id solto");

        SquadAudienceService.UserContext title = audience.contextFor("title", "MEMBER").orElseThrow();
        assertEquals(pick(mode, true, false), title.leadership(), "cargo de liderança não é autoridade");

        SquadAudienceService.UserContext tribe = audience.contextFor("tribe", "MEMBER").orElseThrow();
        assertEquals(List.of("SQ1", "SQ2"), tribe.squadIds().stream().sorted().toList(), "expansão por tribo continua");
        assertTrue(tribe.leadership());

        SquadAudienceService.UserContext admin = audience.contextFor("outsider", "ADMIN").orElseThrow();
        assertTrue(admin.leadership(), "ADMIN do token mantém liderança");
    }

    @Test
    @DisplayName("contextFor (new): a squad corrente é a ativa validada contra os vínculos, não users.squad_id")
    void audienceCurrentSquadIsValidated() {
        world.user("two", "MEMBER", "SQ3").setDefaultProjectId("SQ2");
        world.link("r-two1", "two", "SQ1", "DEVELOPER", "Developer", false);
        world.link("r-two2", "two", "SQ2", "DEVELOPER", "Developer", false);

        SquadAudienceService.UserContext legacy = world.audience(AuthzMode.LEGACY).contextFor("two", "MEMBER").orElseThrow();
        SquadAudienceService.UserContext neu = world.audience(AuthzMode.NEW).contextFor("two", "MEMBER").orElseThrow();

        assertEquals("SQ2", neu.currentSquadId());
        assertEquals(List.of("SQ2", "SQ1"), neu.squadIds());
        assertTrue(legacy.hasSquad("SQ3"), "o legado enxerga a squad solta SQ3");
        assertFalse(neu.hasSquad("SQ3"));
    }

    @Test
    @DisplayName("contextFor (shadow): serve o legado e registra cada squad perdida/ganha, a liderança e os papéis")
    void audienceShadowLogs() {
        world.user("po-dev", "MEMBER", null);
        world.link("r-pd1", "po-dev", "SQ2", "PRODUCT_OWNER", "Product Owner", true);
        world.link("r-pd2", "po-dev", "SQ2", "DEVELOPER", "Developer", false);
        SquadAudienceService audience = world.audience(AuthzMode.SHADOW);

        assertTrue(audience.contextFor("roster", "MEMBER").orElseThrow().hasSquad("SQ1"), "legado decide");
        assertTrue(audience.contextFor("title", "MEMBER").orElseThrow().leadership());
        audience.contextFor("po-dev", "MEMBER");

        List<String> lines = world.divergenceLines();
        assertTrue(lines.contains("authz_divergence user=roster check=audience squad=SQ1 legacy=true new=false reason=ONLY_ROSTER"), lines.toString());
        assertTrue(lines.contains("authz_divergence user=title check=audience squad=SQ1 legacy=true new=false reason=ONLY_SQUAD_ID"), lines.toString());
        assertTrue(lines.contains("authz_divergence user=title check=audienceLeadership squad=- legacy=true new=false reason=ONLY_JOB_TITLE"), lines.toString());
        assertTrue(lines.contains("authz_divergence user=po-dev check=audienceRoles squad=- legacy=false new=true reason=ROLE_KEYS_ADDED"),
                "o resolvedor antigo mostrava só o papel primário; o novo traz todos: " + lines);
        assertTrue(lines.stream().noneMatch(l -> l.contains("@")));
    }

    // ---------- SquadAudienceService.memberUserIds / userIdsWithRoles ----------

    @ParameterizedTest
    @EnumSource(AuthzMode.class)
    @DisplayName("memberUserIds: new = só quem tem linha de vínculo; legado inclui users.squad_id e roster")
    void audienceMembers(AuthzMode mode) {
        SquadAudienceService audience = world.audience(mode);

        Set<String> members = audience.memberUserIds(List.of("sq1")).get("SQ1");

        assertTrue(members.containsAll(Set.of("am", "dev", "tribe")), "vínculos: sempre");
        assertEquals(pick(mode, true, false), members.contains("roster"), "roster-only");
        assertEquals(pick(mode, true, false), members.contains("session"), "users.squad_id solto");
        assertEquals(pick(mode, true, false), members.contains("title"), "cargo + users.squad_id");
        assertFalse(members.contains("outsider"));
        if (mode == AuthzMode.NEW) {
            verify(world.userRepository, never()).findActiveBySquadIds(any());
            verify(world.rosterRepository, never()).findBySquadIdsUpper(any());
        }
    }

    @Test
    @DisplayName("memberUserIds (shadow): serve o legado e registra cada pessoa perdida com a origem do acesso")
    void audienceMembersShadow() {
        SquadAudienceService audience = world.audience(AuthzMode.SHADOW);

        Set<String> served = audience.memberUserIds(List.of("SQ1")).get("SQ1");

        assertTrue(served.containsAll(Set.of("am", "dev", "tribe", "roster", "session", "title")), "decisão = legado");
        List<String> lines = world.divergenceLines();
        assertTrue(lines.contains("authz_divergence user=roster check=audienceMembers squad=SQ1 legacy=true new=false reason=ONLY_ROSTER"), lines.toString());
        assertTrue(lines.contains("authz_divergence user=session check=audienceMembers squad=SQ1 legacy=true new=false reason=ONLY_SQUAD_ID"), lines.toString());
        assertTrue(lines.contains("authz_divergence user=title check=audienceMembers squad=SQ1 legacy=true new=false reason=ONLY_SQUAD_ID"), lines.toString());
        assertEquals(3, lines.size(), lines.toString());
    }

    @ParameterizedTest
    @EnumSource(AuthzMode.class)
    @DisplayName("userIdsWithRoles: já lê só UserRole + pmr, igual nos três modos")
    void audienceRolesUnchanged(AuthzMode mode) {
        world.user("adm", "ADMIN", null);
        when(world.userRepository.findActiveIdsByRoles(any())).thenReturn(List.of("adm"));
        when(world.pmrRepository.findByRoleKeysUpper(any())).thenReturn(world.pmr.stream()
                .filter(r -> "AGILE_MASTER".equals(r.getRoleKey())).toList());

        assertEquals(Set.of("adm", "am"), world.audience(mode).userIdsWithRoles(List.of("ADMIN", "AGILE_MASTER")));
    }

    // ---------- convites ----------

    private InviteService inviteService() {
        InviteRepository inviteRepository = mock(InviteRepository.class);
        InviteService service = new InviteService(inviteRepository, mock(AuditLogRepository.class), world.squadService,
                world.projectRepository, world.pmrRepository, world.userRepository);
        Invite addressed = Invite.builder().id("i1").token("tok").squadId("SQ1").roleName("Developer")
                .email("alguem@outra.com").invitedBy("am").status("PENDING").build();
        when(inviteRepository.findByToken("tok")).thenReturn(Optional.of(addressed));
        return service;
    }

    @ParameterizedTest
    @EnumSource(AuthzMode.class)
    @DisplayName("Convite endereçado: quem vê o e-mail do destinatário - membro por vínculo sempre; só users.squad_id apenas no legado")
    void inviteDisplayPrivilege(AuthzMode mode) {
        InviteService service = inviteService();
        service.setSquadAuthz(world.access(mode), world.shadow(mode));

        assertEquals("alguem@outra.com", service.getForDisplay("tok", world.find("dev").orElseThrow()).getEmail(), "vínculo");
        assertEquals("alguem@outra.com", service.getForDisplay("tok", world.find("tribe").orElseThrow()).getEmail());
        User session = world.find("session").orElseThrow();
        if (mode == AuthzMode.NEW) {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.getForDisplay("tok", session));
            assertEquals(404, ex.getStatusCode().value());
        } else {
            assertEquals("alguem@outra.com", service.getForDisplay("tok", session).getEmail(), "legado: users.squad_id basta");
        }
        assertThrows(ResponseStatusException.class, () -> service.getForDisplay("tok", world.find("outsider").orElseThrow()));
    }

    private InviteController inviteController(AuthzMode mode, InviteService inviteService) {
        return new InviteController(inviteService, world.userRepository, world.pmrRepository, mock(AuthService.class),
                world.access(mode), world.shadow(mode));
    }

    private static MockHttpServletRequest as(String userId, String role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtAuthenticationFilter.ATTR_USER_ID, userId);
        request.setAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE, role);
        return request;
    }

    private int createInviteStatus(InviteController controller, String squad, String userId, String role) {
        try {
            ResponseEntity<Invite> response = controller.createInvite(squad, Map.of("roleName", "Developer"), as(userId, role));
            return response.getStatusCode().value();
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        }
    }

    @ParameterizedTest
    @EnumSource(AuthzMode.class)
    @DisplayName("Criar convite: liderança em linha própria cria; dev não; cargo + users.squad_id só no legado; tribo não cria na squad alheia")
    void inviteAccess(AuthzMode mode) {
        InviteService inviteService = mock(InviteService.class);
        when(inviteService.createInvite(any(), any(), any(), any())).thenReturn(Invite.builder().id("novo").build());
        InviteController controller = inviteController(mode, inviteService);

        assertEquals(201, createInviteStatus(controller, "SQ1", "am", "MEMBER"), "liderança por pmr");
        assertEquals(201, createInviteStatus(controller, "SQ1", "tribe", "MEMBER"), "People Lead com linha própria em SQ1");
        assertEquals(201, createInviteStatus(controller, "SQ3", "outsider", "ADMIN"));
        assertEquals(201, createInviteStatus(controller, "SQ3", "outsider", "LEAD"), "LEAD segue com acesso total");
        assertEquals(403, createInviteStatus(controller, "SQ1", "dev", "MEMBER"));
        assertEquals(403, createInviteStatus(controller, "SQ2", "tribe", "MEMBER"), "liderança só por expansão de tribo não convida");
        assertEquals(403, createInviteStatus(controller, "SQ1", "roster", "MEMBER"));
        assertEquals(pick(mode, 201, 403), createInviteStatus(controller, "SQ1", "title", "MEMBER"), "jobTitle não é autoridade");
    }

    @Test
    @DisplayName("Criar convite (shadow): decisão do legado; perda por cargo vira authz_divergence check=invite")
    void inviteShadow() {
        InviteService inviteService = mock(InviteService.class);
        when(inviteService.createInvite(any(), any(), any(), any())).thenReturn(Invite.builder().id("novo").build());
        InviteController controller = inviteController(AuthzMode.SHADOW, inviteService);

        assertEquals(201, createInviteStatus(controller, "SQ1", "title", "MEMBER"));
        assertEquals(403, createInviteStatus(controller, "SQ1", "dev", "MEMBER"));

        assertEquals(List.of("authz_divergence user=title check=invite squad=SQ1 legacy=true new=false reason=ONLY_JOB_TITLE"),
                world.divergenceLines());
    }

    // ---------- Jolt ----------

    @ParameterizedTest
    @EnumSource(AuthzMode.class)
    @DisplayName("Jolt: new compartilha com N squads dos vínculos; legacy/shadow seguem a squad única de users.squad_id")
    void joltSquads(AuthzMode mode) {
        world.user("multi", "MEMBER", "SQ3");
        world.link("r-m1", "multi", "SQ1", "DEVELOPER", "Developer", false);
        world.link("r-m2", "multi", "SQ2", "DEVELOPER", "Developer", false);
        SquadAccessService access = world.access(mode);

        Set<String> squads = access.squadsForSharing("jolt", world.find("multi").orElseThrow());

        if (mode == AuthzMode.NEW) {
            assertEquals(Set.of("SQ1", "SQ2"), squads);
        } else {
            assertNull(squads, "quem decide ainda é o legado (squad única)");
        }
    }

    @Test
    @DisplayName("Jolt (service): com squadIds a leitura e o compartilhamento valem para qualquer squad dos vínculos")
    void joltServiceUsesSquadList() {
        JoltProjectRepository repository = mock(JoltProjectRepository.class);
        JoltProjectService service = new JoltProjectService(repository, mock(JoltProjectVersionRepository.class));
        JoltProject sq2 = JoltProject.builder().id(UUID.randomUUID()).name("P").authorId("outro").isPublic(false).squadId("sq2").build();

        JoltProjectService.Caller legacy = new JoltProjectService.Caller("multi", "MEMBER", "SQ3", "M", "m@x.com");
        JoltProjectService.Caller neu = new JoltProjectService.Caller("multi", "MEMBER", "SQ3", "M", "m@x.com", Set.of("SQ1", "SQ2"));
        JoltProjectService.Caller noLinks = new JoltProjectService.Caller("x", "MEMBER", "SQ3", "X", "x@x.com", Set.of());

        assertFalse(JoltProjectService.canRead(sq2, legacy));
        assertTrue(JoltProjectService.canRead(sq2, neu), "squad do vínculo, sem caixa");
        assertFalse(JoltProjectService.canRead(sq2, noLinks), "users.squad_id (SQ3) não vale em new");

        when(repository.findAccessibleProjects("multi", "SQ3")).thenReturn(List.of());
        when(repository.findAccessibleProjectsInSquads(any(), any())).thenReturn(List.of());
        service.listProjects(legacy, null);
        verify(repository).findAccessibleProjects("multi", "SQ3");
        verify(repository, never()).findAccessibleProjectsInSquads(any(), any());

        service.listProjects(neu, null);
        verify(repository).findAccessibleProjectsInSquads("multi", Set.of("SQ1", "SQ2"));
        service.listProjects(noLinks, null);
        verify(repository).findAccessibleProjectsInSquads("x", Set.of("\u0000SEM-SQUAD"));
    }

    // ---------- squad "do usuário" (Conhecimento, chave de API, Prompt Hub) ----------

    @ParameterizedTest
    @EnumSource(AuthzMode.class)
    @DisplayName("activeSquadFor: legado = users.squad_id; new = squad ativa dos vínculos (null sem vínculo)")
    void activeSquad(AuthzMode mode) {
        world.user("two", "MEMBER", "SQ1").setDefaultProjectId("SQ2");
        world.link("r-t1", "two", "SQ1", "DEVELOPER", "Developer", false);
        world.link("r-t2", "two", "SQ2", "DEVELOPER", "Developer", false);
        SquadAccessService access = world.access(mode);

        assertEquals("SQ1", access.activeSquadFor("knowledge", world.find("dev").orElseThrow()));
        assertEquals(pick(mode, "SQ1", "SQ2"), access.activeSquadFor("knowledge", world.find("two").orElseThrow()),
                "preferência (default_project_id) válida nos vínculos manda em new");
        assertEquals(pick(mode, "SQ1", null), access.activeSquadFor("apikey", world.find("session").orElseThrow()), "users.squad_id solto");
        assertNull(access.activeSquadFor("apikey", world.find("outsider").orElseThrow()));
        assertNull(access.activeSquadFor("apikey", null));
    }

    @Test
    @DisplayName("activeSquadFor (shadow): devolve o legado e loga a diferença do ponto pedido")
    void activeSquadShadow() {
        SquadAccessService access = world.access(AuthzMode.SHADOW);

        assertEquals("SQ1", access.activeSquadFor("knowledge", world.find("session").orElseThrow()));
        assertEquals("SQ1", access.activeSquadFor("knowledge", world.find("dev").orElseThrow()));

        assertEquals(List.of("authz_divergence user=session check=knowledge squad=SQ1 legacy=true new=false reason=NO_MEMBERSHIP"),
                world.divergenceLines());
    }

    // ---------- janela de repetição e saneamento do log ----------

    @Test
    @DisplayName("Log: a mesma divergência não repete dentro da janela (o contador conta todas); valor da requisição é saneado")
    void dedupeAndSanitize() {
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(world.meters);
        AuthzShadow shadow = new AuthzShadow("shadow", 60, provider);
        long[] now = { 1_000_000L };
        shadow.clock = () -> now[0];

        for (int i = 0; i < 5; i++) {
            shadow.record("matchesSquad", "u1", "SQ1", true, false, "ONLY_ROSTER");
        }
        assertEquals(1, world.divergenceLines().size(), "uma linha dentro da janela");
        assertEquals(5.0, world.meters.counter(AuthzShadow.METRIC, "check", "matchesSquad", "direction", "LOSS").count());

        now[0] += 61_000;
        shadow.record("matchesSquad", "u1", "SQ1", true, false, "ONLY_ROSTER");
        assertEquals(2, world.divergenceLines().size(), "passada a janela, loga de novo");

        shadow.record("matchesSquad", "u2", "sq\n9 x@y.com", false, true, "NEW_ONLY_MEMBERSHIP");
        String last = world.divergenceLines().get(world.divergenceLines().size() - 1);
        assertFalse(last.contains("\n") || last.contains("@"), last);
        assertTrue(last.contains("squad=sq_9_x_y.com"), last);
        assertEquals(Level.INFO, world.logs.list.get(world.logs.list.size() - 1).getLevel());
    }
}
