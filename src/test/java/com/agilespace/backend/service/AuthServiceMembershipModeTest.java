package com.agilespace.backend.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agilespace.backend.domain.ProjectConfig;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.AuthResponseDto;
import com.agilespace.backend.dto.LoginRequestDto;
import com.agilespace.backend.dto.UserProjectAccessDto;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.SquadMemberRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtTokenUtil;
import com.agilespace.backend.security.PasswordUtil;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * A sessão (AuthResponse) nos três modos de app.membership.mode: igual à de antes em legacy e shadow, derivada dos
 * vínculos em new, e o modo sombra registrando divergência.
 */
class AuthServiceMembershipModeTest {

    private static final String PASSWORD = "SenhaForte123";
    private static String passwordHash;

    @Mock private UserRepository userRepository;
    @Mock private ProjectConfigRepository projectConfigRepository;
    @Mock private ProjectMemberRoleRepository pmrRepository;
    @Mock private SquadMemberRepository squadMemberRepository;
    @Mock private JwtTokenUtil jwtTokenUtil;

    private final List<ProjectConfig> projects = new ArrayList<>();
    private final List<ProjectMemberRole> rows = new ArrayList<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private ListAppender<ILoggingEvent> logs;
    private Logger shadowLogger;
    private UserProjectResolverService resolver;
    private MembershipCache cache;

    @BeforeAll
    static void hashOnce() {
        passwordHash = PasswordUtil.hashPassword(PASSWORD);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        MockitoAnnotations.openMocks(this);
        when(jwtTokenUtil.generateToken(any(), any(), any(), any(), any(), any(), any(), anyLong())).thenReturn("jwt-token");
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        when(pmrRepository.findByEmailIgnoreCase(anyString())).thenAnswer(i -> rows.stream()
                .filter(r -> r.getEmail() != null && r.getEmail().equalsIgnoreCase(i.getArgument(0))).toList());
        when(pmrRepository.findByJiraAccountId(anyString())).thenReturn(List.of());
        when(pmrRepository.findByUserId(anyString())).thenReturn(List.of());
        when(projectConfigRepository.findAll()).thenAnswer(i -> List.copyOf(projects));
        when(projectConfigRepository.findById(anyString())).thenAnswer(i -> projects.stream()
                .filter(p -> p.getId().equals(i.getArgument(0))).findFirst());
        when(projectConfigRepository.findAllById(anyIterable())).thenAnswer(i -> {
            List<String> ids = new ArrayList<>();
            ((Iterable<String>) i.getArgument(0)).forEach(ids::add);
            return projects.stream().filter(p -> ids.contains(p.getId())).toList();
        });
        when(squadMemberRepository.findByClaimedByUid(anyString())).thenReturn(List.of());
        when(squadMemberRepository.findByUserIdentifier(anyString())).thenReturn(List.of());
        resolver = new UserProjectResolverService(pmrRepository, projectConfigRepository);
        cache = new MembershipCache(60);

        shadowLogger = (Logger) LoggerFactory.getLogger(MembershipShadowService.class);
        logs = new ListAppender<>();
        logs.start();
        shadowLogger.addAppender(logs);
        shadowLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void detach() {
        shadowLogger.detachAppender(logs);
    }

    // ---------- montagem ----------

    private AuthService service(String mode) {
        AuthService auth = new AuthService(userRepository, projectConfigRepository, pmrRepository, squadMemberRepository,
                resolver, jwtTokenUtil, null);
        if (mode != null) {
            MembershipService membership = new MembershipService(resolver, projectConfigRepository, squadMemberRepository,
                    userRepository, cache, mode);
            @SuppressWarnings("unchecked")
            ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(meters);
            ReflectionTestUtils.setField(auth, "membershipService", membership);
            ReflectionTestUtils.setField(auth, "membershipShadowService",
                    new MembershipShadowService(resolver, membership, userRepository, provider));
        }
        return auth;
    }

    private User user(String id, String email, String defaultProject) {
        User u = User.builder().id(id).email(email).name("Pessoa " + id).role("MEMBER").active(true)
                .passwordHash(passwordHash).defaultProjectId(defaultProject).build();
        when(userRepository.findById(id)).thenReturn(Optional.of(u));
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(u));
        return u;
    }

    private void project(String id, String name, String segment, String tribe) {
        ProjectConfig p = new ProjectConfig();
        p.setId(id);
        p.setName(name);
        p.setSegmentName(segment);
        p.setTribeName(tribe);
        projects.add(p);
    }

    private void row(String id, String email, String project, String key, String name, boolean leadership) {
        ProjectMemberRole r = new ProjectMemberRole();
        r.setId(id);
        r.setEmail(email);
        r.setProjectId(project);
        r.setRoleKey(key);
        r.setRoleName(name);
        r.setLeadership(leadership);
        rows.add(r);
    }

    private long shadowEvents(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level
                && e.getFormattedMessage().startsWith("membership_divergence")).count();
    }

    // ---------- sessão idêntica nos três modos ----------

    @Test
    void sessaoIdenticaNosTresModos_paraOsUsuariosDeFixture() {
        project("SQ1", "Alfa", "SegA", "TriboA");
        project("SQ2", "Beta", "SegA", "TriboA");
        project("SQ3", "Gama", "SegZ", "TriboZ");
        // 1) uma squad, papel único
        row("r1", "um@x.com", "SQ3", "DEVELOPER", "Developer", false);
        user("u-um", "um@x.com", "SQ3");
        // 2) duas linhas na mesma squad (a liderança vence nos dois cálculos)
        row("r2a", "dois@x.com", "SQ1", "DEVELOPER", "Developer", false);
        row("r2b", "dois@x.com", "SQ1", "PRODUCT_OWNER", "Product Owner", true);
        user("u-dois", "dois@x.com", "SQ1");
        // 3) liderança transversal (expande para a tribo)
        row("r3", "tres@x.com", "SQ1", "PEOPLE_LEAD", "People Lead", true);
        user("u-tres", "tres@x.com", "SQ2");
        // 4) sem preferência salva: cai na primeira squad
        row("r4", "quatro@x.com", "SQ2", "SCRUM_MASTER", "Scrum Master", true);
        user("u-quatro", "quatro@x.com", null);

        for (String id : List.of("u-um", "u-dois", "u-tres", "u-quatro")) {
            AuthResponseDto legacy = service("legacy").getMe(id);
            AuthResponseDto semBeans = service(null).getMe(id);
            AuthResponseDto shadow = service("shadow").getMe(id);
            AuthResponseDto novo = service("new").getMe(id);

            assertEquals(legacy, semBeans, id + ": sem os beans novos = legacy");
            assertEquals(legacy, shadow, id + ": shadow devolve a resposta do legado");
            assertEquals(legacy, novo, id + ": new devolve a mesma sessão");
        }
        assertEquals(0, shadowEvents(Level.WARN), "nenhuma divergência de acesso nas fixtures");
        assertEquals("jwt-token", service("new").getMe("u-dois").getToken());
    }

    // ---------- modo sombra ----------

    @Test
    void shadowRegistraDivergenciaSemMudarARespostaDoLegado() {
        project("SQ1", "Alfa", "SegA", "TriboA");
        row("r1", "ana@x.com", "SQ1", "DEVELOPER", "Developer", false);
        // preferência salva aponta para uma squad onde a pessoa não tem vínculo (só roster): o legado a mantém
        user("u-ana", "ana@x.com", "ROSTER_ONLY");

        AuthResponseDto legacy = service("legacy").getMe("u-ana");
        logs.list.clear();
        AuthResponseDto shadow = service("shadow").getMe("u-ana");

        assertEquals(legacy, shadow);
        assertEquals("ROSTER_ONLY", shadow.getActiveProjectId(), "a resposta continua a do legado");
        assertEquals(1, shadowEvents(Level.INFO), "squad ativa diferente é INFO");
        ILoggingEvent event = logs.list.stream().filter(e -> e.getFormattedMessage().startsWith("membership_divergence")).findFirst().orElseThrow();
        assertTrue(event.getFormattedMessage().contains("user=u-ana"));
        assertTrue(event.getFormattedMessage().contains("type=ACTIVE_PROJECT_DIFFERENT"));
        assertTrue(event.getFormattedMessage().contains("detail=preferencia_fora_dos_vinculos"));
        assertFalse(event.getFormattedMessage().contains("ana@x.com"), "log não leva e-mail");
        assertEquals(1.0, meters.counter(MembershipShadowService.METRIC, "type", "ACTIVE_PROJECT_DIFFERENT").count());
    }

    @Test
    void shadowCompara_squadSoNoLegado_soNoNovo_papelDiferente() {
        project("SQ1", "Alfa", "SegA", "TriboA");
        project("SQ2", "Beta", "SegA", "TriboA");
        row("r1", "ana@x.com", "SQ1", "DEVELOPER", "Developer", false);
        User ana = user("u-ana", "ana@x.com", "SQ1");

        // o "legado" devolve: SQ1 com outro papel, uma squad que o novo não tem (SQ9) e não conhece SQ... (nada de SQ2)
        UserProjectAccessDto legadoFalso = UserProjectAccessDto.builder().userId("u-ana").primaryProjectId("SQ1")
                .projects(new ArrayList<>(List.of(
                        UserProjectAccessDto.ProjectAccessItem.builder().projectId("SQ1").roleKey("PRODUCT_OWNER").isLeadership(true).isDirectAssignment(true).build(),
                        UserProjectAccessDto.ProjectAccessItem.builder().projectId("SQ9").roleKey("DEVELOPER").isDirectAssignment(true).build())))
                .build();
        MembershipService membership = new MembershipService(resolver, projectConfigRepository, squadMemberRepository, userRepository, cache, "shadow");
        // vínculo novo extra em SQ2 que o legado falso não tem
        row("r2", "ana@x.com", "SQ2", "DEVELOPER", "Developer", false);
        List<MembershipShadowService.Divergence> diffs = MembershipShadowService.compare(legadoFalso, "SQ1", ana, membership.computeFresh(ana));

        assertEquals(List.of(
                        new MembershipShadowService.Divergence(MembershipShadowService.Type.ROLE_DIFFERENT, "SQ1", "roleKey+leadership"),
                        new MembershipShadowService.Divergence(MembershipShadowService.Type.ONLY_LEGACY, "SQ9", "direto"),
                        new MembershipShadowService.Divergence(MembershipShadowService.Type.ONLY_NEW, "SQ2", "direto")),
                diffs.stream().filter(d -> d.type() != MembershipShadowService.Type.TRANSVERSAL_DIFFERENT).toList());
    }

    @Test
    void shadowNuncaQuebraASessao() {
        project("SQ1", "Alfa", "SegA", "TriboA");
        row("r1", "ana@x.com", "SQ1", "DEVELOPER", "Developer", false);
        user("u-ana", "ana@x.com", "SQ1");
        AuthService auth = service("shadow");
        when(squadMemberRepository.findByClaimedByUid(anyString())).thenThrow(new IllegalStateException("banco fora"));
        when(projectConfigRepository.findAllById(anyIterable())).thenThrow(new IllegalStateException("banco fora"));

        AuthResponseDto response = assertDoesNotThrow(() -> auth.getMe("u-ana"));

        assertEquals("SQ1", response.getActiveProjectId());
    }

    // ---------- modo new ----------

    @Test
    void newDerivaASquadAtivaDosVinculos_eOJwtLevaEssaSquad() {
        project("SQ1", "Alfa", "SegA", "TriboA");
        row("r1", "ana@x.com", "SQ1", "DEVELOPER", "Developer", false);
        user("u-ana", "ana@x.com", "ROSTER_ONLY");

        AuthResponseDto response = service("new").getMe("u-ana");

        assertEquals("SQ1", response.getActiveProjectId(), "preferência fora dos vínculos cai na primeira squad");
        assertEquals("Alfa", response.getActiveProjectName());
        ArgumentCaptor<String> active = ArgumentCaptor.forClass(String.class);
        verify(jwtTokenUtil).generateToken(eq("u-ana"), any(), any(), any(), active.capture(), any(), any(), anyLong());
        assertEquals("SQ1", active.getValue());
    }

    @Test
    void newSemNenhumVinculo_activeNuloELegacyOnlyQuandoHaRastroAntigo() {
        user("u-roster", "roster@x.com", "SQ-ANTIGA");
        user("u-nova", "nova@x.com", null);

        AuthResponseDto roster = service("new").getMe("u-roster");
        AuthResponseDto nova = service("new").getMe("u-nova");

        assertNull(roster.getActiveProjectId());
        assertEquals(Boolean.TRUE, roster.getLegacyOnly());
        assertTrue(roster.getAccessibleProjects().isEmpty());
        assertNull(nova.getActiveProjectId());
        assertNull(nova.getLegacyOnly(), "conta nova vai para o onboarding: não é legado");
        assertNull(service("legacy").getMe("u-roster").getLegacyOnly(), "legacy não acrescenta o campo");
        assertNull(service("shadow").getMe("u-roster").getLegacyOnly());
    }

    @Test
    void loginSemPmr_naoBloqueia_registraELegacyOnlyNoModoNew() {
        User u = user("u-roster", "roster@x.com", null);
        SquadMember sm = new SquadMember();
        sm.setSquadId("SQUAD_ROSTER");
        when(squadMemberRepository.findByUserIdentifier("roster@x.com")).thenReturn(List.of(sm));
        Logger authLogger = (Logger) LoggerFactory.getLogger(AuthService.class);
        ListAppender<ILoggingEvent> authLogs = new ListAppender<>();
        authLogs.start();
        authLogger.addAppender(authLogs);
        try {
            AuthResponseDto response = service("new").login(LoginRequestDto.builder().email("roster@x.com").password(PASSWORD).build());

            assertEquals("jwt-token", response.getToken(), "o login não é bloqueado");
            assertEquals(Boolean.TRUE, response.getLegacyOnly());
            assertTrue(authLogs.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("Login sem vínculo em project_member_roles")
                    && e.getFormattedMessage().contains("user=u-roster")), "fallback por roster fica registrado");
            assertFalse(authLogs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("roster@x.com")), "sem e-mail no log");
            assertEquals("SQUAD_ROSTER", u.getDefaultProjectId(), "o dual-write do fallback continua como antes");
        } finally {
            authLogger.detachAppender(authLogs);
        }
    }

    // ---------- switchActiveProject ----------

    @Test
    void switchProject_continuaGravandoOsDoisCampos_validaEEvictaNoModoNew() {
        project("SQ1", "Alfa", "SegA", "TriboA");
        project("SQ2", "Beta", "SegA", "TriboA");
        row("r1", "ana@x.com", "SQ1", "DEVELOPER", "Developer", false);
        row("r2", "ana@x.com", "SQ2", "DEVELOPER", "Developer", false);
        User ana = user("u-ana", "ana@x.com", "SQ1");
        AuthService auth = service("new");
        auth.getMe("u-ana");
        assertEquals(1, cache.size());

        AuthResponseDto response = auth.switchActiveProject("u-ana", "sq2");

        assertEquals("SQ2", ana.getDefaultProjectId(), "grava o id canônico, não o texto digitado");
        assertEquals("SQ2", ana.getDefaultProjectId());
        assertEquals("SQ2", response.getActiveProjectId());
        ResponseStatusException negado = assertThrows(ResponseStatusException.class, () -> auth.switchActiveProject("u-ana", "SQ3"));
        assertEquals(403, negado.getStatusCode().value());
        assertEquals("SQ2", ana.getDefaultProjectId(), "negado não grava nada");
    }

    @Test
    void switchProject_modosLegacyEShadow_validamComOResolvedorAntigoEGravamOTextoComoVeio() {
        project("SQ1", "Alfa", "SegA", "TriboA");
        row("r1", "ana@x.com", "SQ1", "DEVELOPER", "Developer", false);
        for (String mode : new String[] { "legacy", "shadow" }) {
            User ana = user("u-ana", "ana@x.com", null);
            AuthService auth = service(mode);

            auth.switchActiveProject("u-ana", "sq1");

            assertEquals("sq1", ana.getDefaultProjectId(), mode);
            assertEquals("sq1", ana.getDefaultProjectId(), mode);
            assertThrows(ResponseStatusException.class, () -> auth.switchActiveProject("u-ana", "OUTRA"));
        }
    }
}
