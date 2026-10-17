package com.agilespace.backend.service;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.AnnouncementDto;
import com.agilespace.backend.dto.AnnouncementRequest;
import com.agilespace.backend.repository.AnnouncementDismissalRepository;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.repository.GlobalAnnouncementRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.LeadPolicy;
import com.agilespace.backend.security.SquadLeadership;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Etapa 4: o nível de conta (UserService), o aviso por "liderança" (AnnouncementService), o cargo "lead" que saiu da lista
 * de liderança e o padrão da chave no application.yml.
 */
@DisplayName("Etapa 4 - nível de conta: UserService, avisos, SquadLeadership e padrão do application.yml")
class LeadLevelAccountTest {

    private UserRepository repo;
    private AuditLogRepository audit;
    private UserService service;
    private User admin;
    private User member;
    private User lead;

    @BeforeEach
    void setUp() {
        repo = mock(UserRepository.class);
        audit = mock(AuditLogRepository.class);
        service = new UserService();
        ReflectionTestUtils.setField(service, "userRepository", repo);
        ReflectionTestUtils.setField(service, "auditLogRepository", audit);
        admin = User.builder().id("a1").email("adm@totvs.com.br").name("Adm").role("ADMIN").active(true).build();
        member = User.builder().id("m1").email("m@totvs.com.br").name("Maria").role("MEMBER").active(true).build();
        lead = User.builder().id("l1").email("l@totvs.com.br").name("Lia").role("LEAD").active(true).build();
        for (User u : new User[] {admin, member, lead}) when(repo.findById(u.getId())).thenReturn(Optional.of(u));
        when(repo.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        when(repo.countByRoleIgnoreCaseAndActiveTrue("ADMIN")).thenReturn(2L);
    }

    private User body(String json) throws Exception {
        return new ObjectMapper().readValue(json, User.class);
    }

    // ---------- UserService ----------

    @Test
    @DisplayName("off: atribuir LEAD é recusado (400) e nada é gravado; ADMIN e MEMBER seguem valendo")
    void offRejectsAssigningLead() throws Exception {
        service.setLeadPolicy(LeadPolicy.OFF);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.saveUser(body("{\"id\":\"m1\",\"role\":\"LEAD\"}"), true, "a1"));
        assertEquals(400, ex.getStatusCode().value());
        assertTrue(ex.getReason().contains("ADMIN ou MEMBER"));
        assertEquals("MEMBER", member.getRole());
        verify(audit, never()).save(any());

        assertThrows(ResponseStatusException.class, () -> service.saveUser(body("{\"id\":\"m1\",\"role\":\"lead\"}"), true, "a1"));

        assertEquals("ADMIN", service.saveUser(body("{\"id\":\"m1\",\"role\":\"ADMIN\"}"), true, "a1").getRole());
        assertEquals("MEMBER", service.saveUser(body("{\"id\":\"m1\",\"role\":\"member\"}"), true, "a1").getRole());
    }

    @Test
    @DisplayName("off: o painel devolvendo LEAD sem mudar nada (conta ainda LEAD) não dá erro nem altera; tirar o LEAD pode")
    void offToleratesUnchangedLeadAndAllowsLeavingIt() throws Exception {
        service.setLeadPolicy(LeadPolicy.OFF);

        User saved = service.saveUser(body("{\"id\":\"l1\",\"name\":\"Lia Nova\",\"role\":\"LEAD\"}"), true, "a1");
        assertEquals("LEAD", saved.getRole());
        assertEquals("Lia Nova", saved.getName());
        verify(audit, never()).save(any());

        assertEquals("MEMBER", service.saveUser(body("{\"id\":\"l1\",\"role\":\"MEMBER\"}"), true, "a1").getRole());
    }

    @Test
    @DisplayName("legacy: LEAD continua atribuível e auditado, como antes")
    void legacyStillAssignsLead() throws Exception {
        service.setLeadPolicy(LeadPolicy.LEGACY);

        assertEquals("LEAD", service.saveUser(body("{\"id\":\"m1\",\"role\":\"lead\"}"), true, "a1").getRole());
        verify(audit).save(any(AuditLog.class));
        assertThrows(ResponseStatusException.class, () -> service.saveUser(body("{\"id\":\"m1\",\"role\":\"GERENTE\"}"), true, "a1"));
    }

    // ---------- Sessão ----------

    @Test
    @DisplayName("Sessão (token e AuthResponse): off entrega MEMBER para um LEAD legado; legacy entrega como está gravado")
    void sessionRoleOfALegacyLead() {
        com.agilespace.backend.repository.ProjectMemberRoleRepository pmr = mock(com.agilespace.backend.repository.ProjectMemberRoleRepository.class);
        com.agilespace.backend.repository.ProjectConfigRepository projects = mock(com.agilespace.backend.repository.ProjectConfigRepository.class);
        com.agilespace.backend.security.JwtTokenUtil jwt = mock(com.agilespace.backend.security.JwtTokenUtil.class);
        when(jwt.generateToken(any(), any(), any(), any(), any(), any(), any(), anyLong())).thenReturn("jwt");
        when(jwt.generateToken(any(), any(), any(), any(), any(), any(), any())).thenReturn("jwt");
        when(pmr.findByEmailIgnoreCase(any())).thenReturn(List.of());
        when(pmr.findByJiraAccountId(any())).thenReturn(List.of());
        when(pmr.findByUserId(any())).thenReturn(List.of());
        when(projects.findAll()).thenReturn(List.of());
        AuthService auth = new AuthService(repo, projects, pmr, mock(com.agilespace.backend.repository.SquadMemberRepository.class),
                new UserProjectResolverService(pmr, projects), jwt, null);

        auth.setLeadPolicy(LeadPolicy.OFF);
        assertEquals("MEMBER", auth.getMe("l1").getRole());
        verify(jwt, atLeastOnce()).generateToken(any(), any(), any(), eq("MEMBER"), any(), any(), any(), anyLong());

        auth.setLeadPolicy(LeadPolicy.LEGACY);
        assertEquals("LEAD", auth.getMe("l1").getRole());
    }

    // ---------- Avisos ----------

    @Test
    @DisplayName("Aviso por papel: LEAD enviado por um cliente antigo é gravado como LEADERSHIP")
    void announcementNormalizesLead() {
        GlobalAnnouncementRepository announcements = mock(GlobalAnnouncementRepository.class);
        when(announcements.save(any())).thenAnswer(i -> i.getArgument(0));
        AnnouncementService announcementService = new AnnouncementService(announcements, mock(AnnouncementDismissalRepository.class),
                mock(UserRepository.class), mock(SquadAudienceService.class), mock(NotificationTriggers.class));

        AnnouncementDto dto = announcementService.create(new AnnouncementRequest("Titulo", "Texto", "INFO", "ROLES",
                List.of(), List.of("lead", "ADMIN"), null, null, null), "adm@x.com", "adm");

        assertEquals(List.of("ADMIN", "LEADERSHIP"), dto.audienceRoles().stream().sorted().toList());
    }

    // ---------- SquadLeadership ----------

    @Test
    @DisplayName("SquadLeadership: 'lead' (cargo e nome de papel) deixou de ser liderança; os demais seguem")
    void leadIsNoLongerALeadershipTitle() {
        assertFalse(SquadLeadership.isLeadershipJobTitle("lead"));
        assertFalse(SquadLeadership.isLeadershipJobTitle(" Lead "));
        assertFalse(SquadLeadership.isLeadershipRoleName("Lead"));
        assertFalse(SquadLeadership.isLeadershipRoleName("LEAD"));
        assertTrue(SquadLeadership.isLeadershipJobTitle("Tech Lead"));
        assertTrue(SquadLeadership.isLeadershipJobTitle("Agile Master"));
        assertTrue(SquadLeadership.isLeadershipRoleName("Tech-Lead"));
        assertTrue(SquadLeadership.isLeadershipRoleName("admin"));
    }

    // ---------- application.yml ----------

    @Test
    @DisplayName("application.yml: app.membership.lead-level tem padrão off e lê APP_MEMBERSHIP_LEAD_LEVEL")
    @SuppressWarnings("unchecked")
    void applicationYmlDefaultsToOff() throws Exception {
        try (InputStream in = new ClassPathResource("application.yml").getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> app = (Map<String, Object>) root.get("app");
            Map<String, Object> membership = (Map<String, Object>) app.get("membership");
            assertEquals("${APP_MEMBERSHIP_LEAD_LEVEL:off}", membership.get("lead-level"));
        }
    }
}
