package com.agilespace.backend.service;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.SquadMemberExclusion;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.SquadMemberExclusionRepository;
import com.agilespace.backend.repository.SquadMemberRepository;
import com.agilespace.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SquadTeamService - Agile Master/People Lead gerenciam as pessoas da própria equipe")
class SquadTeamServiceTest {

    @Mock private SquadMemberRepository memberRepository;
    @Mock private SquadMemberExclusionRepository exclusionRepository;
    @Mock private ProjectMemberRoleRepository projectRoles;
    @Mock private UserRepository userRepository;
    @Mock private AuditLogRepository auditLogRepository;

    private SquadTeamService service;
    private List<ProjectMemberRole> rows;

    private final User pl = User.builder().id("pl1").email("pl@x.com").name("Paula Lead").role("MEMBER").build();
    private final User admin = User.builder().id("adm").email("adm@x.com").name("Admin").role("ADMIN").build();
    private final User dev = User.builder().id("dev1").email("dev@x.com").name("Dev Um").role("MEMBER").build();

    private ProjectMemberRole row(String id, String key, boolean leadership, String email, String userId) {
        return ProjectMemberRole.builder().id(id).projectId("SQ1").roleName(key).roleKey(key).isLeadership(leadership)
                .email(email).userId(userId).displayName(email).build();
    }

    @BeforeEach
    void setUp() {
        service = new SquadTeamService(memberRepository, exclusionRepository, projectRoles, userRepository, auditLogRepository);
        rows = new ArrayList<>(List.of(
                row("r-pl", "PEOPLE_LEAD", true, "pl@x.com", "pl1"),
                row("r-dev", "DEVELOPER", false, "dev@x.com", "dev1")));
        lenient().when(projectRoles.findByProjectId("SQ1")).thenAnswer(i -> new ArrayList<>(rows));
        lenient().when(memberRepository.findBySquadIdOrderByDisplayNameAsc("SQ1")).thenReturn(List.of());
    }

    private SquadMember member(String acc, String email, String claimed) {
        return SquadMember.builder().dbId("SQ1_" + acc).squadId("SQ1").jiraAccountId(acc).displayName(acc).email(email).claimedByUid(claimed).role("Developer").build();
    }

    @Test
    @DisplayName("Só Agile Master/People Lead da PRÓPRIA squad e admin gerenciam; dev e líder de outra squad não")
    void whoCanManage() {
        assertTrue(service.canManageTeam("SQ1", pl));
        assertTrue(service.canManageTeam("SQ1", admin));
        assertFalse(service.canManageTeam("SQ1", dev));
        assertFalse(service.canManageTeam("OUTRA", pl));
        assertFalse(service.canManageTeam("SQ1", null));
    }

    @Test
    @DisplayName("Adiciona pessoa que já tem conta: vincula a conta, cria o papel no projeto, limpa a exclusão e audita")
    void addExistingUser() {
        User ana = User.builder().id("u-ana").email("ana@x.com").name("Ana Lima").jiraAccountId("jira-ana").build();
        when(userRepository.findByEmail("ana@x.com")).thenReturn(Optional.of(ana));
        when(memberRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        SquadMember saved = service.addMember("SQ1", pl, new SquadTeamService.AddRequest(null, "ana@x.com", null, "QA"));

        assertEquals("jira-ana", saved.getJiraAccountId());
        assertEquals("u-ana", saved.getClaimedByUid());
        assertEquals("QA", saved.getRole());
        ArgumentCaptor<ProjectMemberRole> role = ArgumentCaptor.forClass(ProjectMemberRole.class);
        verify(projectRoles).save(role.capture());
        assertEquals("QA", role.getValue().getRoleKey());
        assertFalse(role.getValue().isLeadership());
        assertEquals("u-ana", role.getValue().getUserId());
        verify(exclusionRepository).deleteById("SQ1_jira-ana");
        verify(auditLogRepository).save(any(AuditLog.class));
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Pré-cadastro por nome (sem conta): linha sem vínculo e papel sem usuário")
    void addPreRegistered() {
        when(memberRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        SquadMember saved = service.addMember("SQ1", pl, new SquadTeamService.AddRequest(null, null, "Zé da Silva", "Developer"));

        assertTrue(saved.getJiraAccountId().startsWith("manual-SQ1-z-da-silva") || saved.getJiraAccountId().startsWith("manual-SQ1-"));
        assertNull(saved.getClaimedByUid());
    }

    @Test
    @DisplayName("Pessoa que já está no time dá 409; nome vazio 400; papel fora da lista 400 (inclusive Tribe Lead)")
    void addValidation() {
        when(memberRepository.findBySquadIdOrderByDisplayNameAsc("SQ1")).thenReturn(List.of(member("acc-1", "ana@x.com", null)));

        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.addMember("SQ1", pl, new SquadTeamService.AddRequest(null, "ana@x.com", "Ana", "QA"))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.addMember("SQ1", pl, new SquadTeamService.AddRequest(null, null, " ", "QA"))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.addMember("SQ1", pl, new SquadTeamService.AddRequest(null, null, "Fulano", "Tribe Lead"))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.addMember("SQ1", pl, new SquadTeamService.AddRequest(null, null, "Fulano", "ADMIN"))).getStatusCode());
        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("People Lead não atribui Agile Master/People Lead (dá poder de gerir equipes); admin atribui")
    void managerRolesOnlyByAdmin() {
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.addMember("SQ1", pl, new SquadTeamService.AddRequest(null, null, "Fulano", "Agile Master"))).getStatusCode());
        when(memberRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        assertEquals("Agile Master", service.addMember("SQ1", admin, new SquadTeamService.AddRequest(null, null, "Fulano", "Agile Master")).getRole());
    }

    @Test
    @DisplayName("Quem não é Agile Master/People Lead da squad não adiciona, não remove e não busca pessoas")
    void nonManagerIsForbidden() {
        assertThrows(ResponseStatusException.class, () -> service.addMember("SQ1", dev, new SquadTeamService.AddRequest(null, null, "X", "QA")));
        assertThrows(ResponseStatusException.class, () -> service.removeMember("SQ1", dev, "acc-1"));
        assertThrows(ResponseStatusException.class, () -> service.changeRole("SQ1", dev, "acc-1", "QA"));
        assertThrows(ResponseStatusException.class, () -> service.searchCandidates("SQ1", dev, "ana@"));
        verifyNoInteractions(exclusionRepository);
    }

    @Test
    @DisplayName("Remover: apaga a linha e o papel no projeto, solta a conta da squad (sem apagá-la), registra a exclusão e audita")
    void removeMember() {
        SquadMember m = member("jira-dev", "dev@x.com", "dev1");
        when(memberRepository.findBySquadIdAndJiraAccountId("SQ1", "jira-dev")).thenReturn(Optional.of(m));
        User stored = User.builder().id("dev1").email("dev@x.com").squadId("SQ1").defaultProjectId("SQ1").build();
        when(userRepository.findById("dev1")).thenReturn(Optional.of(stored));

        service.removeMember("SQ1", pl, "jira-dev");

        verify(memberRepository).delete(m);
        ArgumentCaptor<List<ProjectMemberRole>> deleted = ArgumentCaptor.forClass(List.class);
        verify(projectRoles).deleteAll(deleted.capture());
        assertEquals(List.of("r-dev"), deleted.getValue().stream().map(ProjectMemberRole::getId).toList());
        assertNull(stored.getSquadId());
        assertNull(stored.getDefaultProjectId());
        verify(userRepository).save(stored);
        verify(userRepository, never()).delete(any());
        ArgumentCaptor<SquadMemberExclusion> ex = ArgumentCaptor.forClass(SquadMemberExclusion.class);
        verify(exclusionRepository).save(ex.capture());
        assertEquals("jira-dev", ex.getValue().getJiraAccountId());
        assertEquals("pl1", ex.getValue().getRemovedBy());
        verify(auditLogRepository).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Não remove a única liderança (nem a si mesmo); com outra liderança, pode")
    void lastLeaderGuard() {
        SquadMember self = member("jira-pl", "pl@x.com", "pl1");
        when(memberRepository.findBySquadIdAndJiraAccountId("SQ1", "jira-pl")).thenReturn(Optional.of(self));

        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> service.removeMember("SQ1", pl, "jira-pl")).getStatusCode());
        verify(memberRepository, never()).delete(any());

        rows.add(row("r-po", "PRODUCT_OWNER", true, "po@x.com", "po1"));
        service.removeMember("SQ1", pl, "jira-pl");
        verify(memberRepository).delete(self);
    }

    @Test
    @DisplayName("People Lead não remove nem rebaixa outro Agile Master/People Lead; admin pode, mesmo sendo o último")
    void peerManagersOnlyByAdmin() {
        rows.add(row("r-am", "AGILE_MASTER", true, "am@x.com", "am1"));
        SquadMember am = member("jira-am", "am@x.com", "am1");
        when(memberRepository.findBySquadIdAndJiraAccountId("SQ1", "jira-am")).thenReturn(Optional.of(am));

        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> service.removeMember("SQ1", pl, "jira-am")).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> service.changeRole("SQ1", pl, "jira-am", "Developer")).getStatusCode());

        service.removeMember("SQ1", admin, "jira-am");
        verify(memberRepository).delete(am);
    }

    @Test
    @DisplayName("Trocar o papel atualiza o roster e o cadastro do projeto (liderança acompanha) e audita; rebaixar a única liderança dá 409")
    void changeRole() {
        SquadMember m = member("jira-dev", "dev@x.com", "dev1");
        when(memberRepository.findBySquadIdAndJiraAccountId("SQ1", "jira-dev")).thenReturn(Optional.of(m));
        when(memberRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        SquadMember changed = service.changeRole("SQ1", pl, "jira-dev", "Product Owner");

        assertEquals("Product Owner", changed.getRole());
        ProjectMemberRole devRow = rows.get(1);
        assertEquals("PRODUCT_OWNER", devRow.getRoleKey());
        assertTrue(devRow.isLeadership());
        verify(projectRoles).saveAll(any());
        verify(auditLogRepository).save(any(AuditLog.class));
        verify(userRepository, never()).save(any());

        // Sem o PO, o PL é a única liderança e não se rebaixa sozinho.
        rows.remove(devRow);
        SquadMember self = member("jira-pl", "pl@x.com", "pl1");
        when(memberRepository.findBySquadIdAndJiraAccountId("SQ1", "jira-pl")).thenReturn(Optional.of(self));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> service.changeRole("SQ1", pl, "jira-pl", "Developer")).getStatusCode());
    }
}
