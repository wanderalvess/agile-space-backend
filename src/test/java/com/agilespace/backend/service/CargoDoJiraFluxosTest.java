package com.agilespace.backend.service;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.SquadMetricsRollup;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.JiraConfirmSyncRequest;
import com.agilespace.backend.dto.JiraMemberCandidateDto;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.SquadMemberRepository;
import com.agilespace.backend.repository.SquadMetricsRollupRepository;
import com.agilespace.backend.repository.SquadRepository;
import com.agilespace.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** O cargo (users.job_title) da administração vale de ponta a ponta; o Jira só preenche quem está sem cargo. */
@DisplayName("Cargo do Jira: sync não sobrescreve, claim preenche vazio, admin canonicaliza")
class CargoDoJiraFluxosTest {

    private UserRepository users;
    private AuditLogRepository audit;
    private CargoPreenchimento preenchimento;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        audit = mock(AuditLogRepository.class);
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        preenchimento = new CargoPreenchimento(users, audit);
    }

    // ---------- sincronização do Jira (JiraAdminService) ----------

    private JiraAdminService adminService(User existente) {
        SquadRepository squads = mock(SquadRepository.class);
        SquadMemberRepository members = mock(SquadMemberRepository.class);
        SquadMetricsRollupRepository rollups = mock(SquadMetricsRollupRepository.class);
        when(squads.findById(anyString())).thenReturn(Optional.empty());
        when(squads.save(any(Squad.class))).thenAnswer(i -> i.getArgument(0));
        when(rollups.findById(anyString())).thenReturn(Optional.empty());
        when(rollups.save(any(SquadMetricsRollup.class))).thenAnswer(i -> i.getArgument(0));
        when(members.findById(anyString())).thenReturn(Optional.empty());
        when(members.save(any(SquadMember.class))).thenAnswer(i -> i.getArgument(0));
        when(users.findById("acc1")).thenReturn(Optional.ofNullable(existente));
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());
        when(users.findByJiraAccountId(anyString())).thenReturn(Optional.empty());
        JiraAdminService service = new JiraAdminService();
        ReflectionTestUtils.setField(service, "squadRepository", squads);
        ReflectionTestUtils.setField(service, "squadMemberRepository", members);
        ReflectionTestUtils.setField(service, "userRepository", users);
        ReflectionTestUtils.setField(service, "squadMetricsRollupRepository", rollups);
        ReflectionTestUtils.setField(service, "cargoPreenchimento", preenchimento);
        return service;
    }

    private JiraConfirmSyncRequest syncComRole(String role) {
        return JiraConfirmSyncRequest.builder().squadId("proj1").replaceExisting(false).syncUsers(true)
                .members(Collections.singletonList(JiraMemberCandidateDto.builder().jiraAccountId("acc1")
                        .displayName("Ana").email("acc1@empresa.com").role(role).score(50).selected(true)
                        .capacityHoursPerDay(6.0).build()))
                .build();
    }

    @Test
    @DisplayName("sync de conta nova grava o cargo canônico do Jira")
    void syncContaNovaGravaCanonico() {
        adminService(null).confirmSync(syncComRole("Developer"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertEquals("Desenvolvedor(a)", captor.getValue().getJobTitle());
    }

    @Test
    @DisplayName("sync NÃO sobrescreve o cargo definido na administração")
    void syncNaoSobrescreveCargoDefinido() {
        User existente = User.builder().id("acc1").email("acc1@empresa.com").role("MEMBER").jobTitle("Agile Master").build();
        adminService(existente).confirmSync(syncComRole("Developer"));

        assertEquals("Agile Master", existente.getJobTitle());
        verify(audit, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("sync preenche conta sem cargo (nulo ou em branco), canonicaliza e audita; nível de acesso intacto")
    void syncPreencheVazio() {
        User semCargo = User.builder().id("acc1").email("acc1@empresa.com").role("ADMIN").build();
        adminService(semCargo).confirmSync(syncComRole("QA"));
        assertEquals("Analista de QA", semCargo.getJobTitle());
        assertEquals("ADMIN", semCargo.getRole());
        ArgumentCaptor<AuditLog> log = ArgumentCaptor.forClass(AuditLog.class);
        verify(audit).save(log.capture());
        assertEquals("USER_JOBTITLE_FILLED_FROM_JIRA", log.getValue().getAction());

        User emBranco = User.builder().id("acc1").email("acc1@empresa.com").role("MEMBER").jobTitle("   ").build();
        adminService(emBranco).confirmSync(syncComRole("Tech Lead"));
        assertEquals("Tech Lead", emBranco.getJobTitle(), "liderança não é mapeada: passa como veio");
    }

    // ---------- "Sou eu" (OnboardingService) ----------

    private OnboardingService onboarding(ProjectMemberRole row) {
        ProjectMemberRoleRepository pmr = mock(ProjectMemberRoleRepository.class);
        when(pmr.findById(row.getId())).thenReturn(Optional.of(row));
        when(pmr.findByProjectId("P1")).thenReturn(List.of(row));
        OnboardingService service = new OnboardingService(pmr, mock(ProjectConfigRepository.class),
                mock(SquadMemberRepository.class), users);
        ReflectionTestUtils.setField(service, "cargoPreenchimento", preenchimento);
        return service;
    }

    private ProjectMemberRole linha(String role, String source) {
        return ProjectMemberRole.builder().id("r1").projectId("P1").roleName(role).roleKey("X").email("ana@totvs.com.br")
                .jiraAccountId("acc1").displayName("Ana").source(source).isLeadership(false).build();
    }

    @Test
    @DisplayName("claim preenche o cargo vazio com a role canônica da linha do Jira e audita")
    void claimPreencheVazio() {
        User ana = User.builder().id("u1").name("Ana").email("ana@totvs.com.br").role("MEMBER").build();
        onboarding(linha("Developer", null)).claim("r1", ana);

        assertEquals("Desenvolvedor(a)", ana.getJobTitle());
        assertEquals("MEMBER", ana.getRole());
        verify(audit).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("claim não sobrescreve cargo existente nem usa linha de decisão humana (MANUAL/INVITE)")
    void claimNaoSobrescreve() {
        User comCargo = User.builder().id("u1").name("Ana").email("ana@totvs.com.br").jobTitle("Analista de QA").build();
        onboarding(linha("Developer", "JIRA")).claim("r1", comCargo);
        assertEquals("Analista de QA", comCargo.getJobTitle());

        User semCargo = User.builder().id("u2").name("Ana").email("ana@totvs.com.br").build();
        onboarding(linha("Developer", "MANUAL")).claim("r1", semCargo);
        assertNull(semCargo.getJobTitle());
        verify(audit, never()).save(any(AuditLog.class));
    }

    // ---------- edição pelo admin (UserService) ----------

    @Test
    @DisplayName("admin que digita Developer/QA grava o nome canônico; vazio limpa; reenviar o mesmo valor não audita")
    void adminCanonicaliza() throws Exception {
        UserService service = new UserService();
        ReflectionTestUtils.setField(service, "userRepository", users);
        ReflectionTestUtils.setField(service, "auditLogRepository", audit);
        User alvo = User.builder().id("m1").email("m@totvs.com.br").role("MEMBER").active(true).build();
        when(users.findById("m1")).thenReturn(Optional.of(alvo));
        ObjectMapper om = new ObjectMapper();

        service.saveUser(om.readValue("{\"id\":\"m1\",\"jobTitle\":\"Developer\"}", User.class), true, null);
        assertEquals("Desenvolvedor(a)", alvo.getJobTitle());

        service.saveUser(om.readValue("{\"id\":\"m1\",\"jobTitle\":\"  qa \"}", User.class), true, null);
        assertEquals("Analista de QA", alvo.getJobTitle());

        service.saveUser(om.readValue("{\"id\":\"m1\",\"jobTitle\":\"Analista de QA\"}", User.class), true, null);
        verify(audit, times(2)).save(any(AuditLog.class));

        service.saveUser(om.readValue("{\"id\":\"m1\",\"jobTitle\":\"  \"}", User.class), true, null);
        assertNull(alvo.getJobTitle());
    }
}
