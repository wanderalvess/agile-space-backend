package com.agilespace.backend.service;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.domain.LinkRequest;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.AdminLinkRequestDto;
import com.agilespace.backend.dto.LinkRequestCreateRequest;
import com.agilespace.backend.dto.LinkRequestCreatedDto;
import com.agilespace.backend.dto.LinkRequestDto;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.repository.LinkRequestRepository;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.SquadMemberRepository;
import com.agilespace.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LinkRequestService - pedido de vínculo aprovado por admin")
class LinkRequestServiceTest {

    @Mock private LinkRequestRepository linkRequestRepository;
    @Mock private ProjectConfigRepository projectConfigRepository;
    @Mock private ProjectMemberRoleRepository projectMemberRoleRepository;
    @Mock private SquadMemberRepository squadMemberRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private NotificationTriggers notificationTriggers;
    @Mock private LinkRequestApprovalPolicy approvalPolicy;

    private LinkRequestService service;

    @BeforeEach
    void setup() {
        // OnboardingService real: o que a aprovação grava (linha, conta, roster) é parte do que se testa.
        OnboardingService onboarding = new OnboardingService(projectMemberRoleRepository, projectConfigRepository,
                squadMemberRepository, userRepository);
        service = new LinkRequestService(linkRequestRepository, projectConfigRepository, projectMemberRoleRepository,
                userRepository, onboarding, auditLogRepository, notificationTriggers, approvalPolicy);
    }

    private static User ana() {
        return User.builder().id("u1").name("Ana Paula").email("ana@gmail.com").build();
    }

    private static ProjectMemberRole row(String id, String userId, boolean leadership) {
        return ProjectMemberRole.builder().id(id).projectId("DDWMISSI").roleName(leadership ? "Agile Master" : "Developer")
                .roleKey(leadership ? "AGILE_MASTER" : "DEVELOPER").displayName("Ana Paula Nogueira")
                .email("ana.nogueira@totvs.com.br").jiraAccountId("acc-ana").userId(userId).isLeadership(leadership).build();
    }

    private static LinkRequestCreateRequest body(String project, String memberId, String email, String message) {
        return new LinkRequestCreateRequest(project, memberId, email, message);
    }

    private static LinkRequest pending(String id, String memberId) {
        return LinkRequest.builder().id(id).requesterUserId("u1").projectKey("DDWMISSI").memberId(memberId)
                .emailInJira("ana.nogueira@totvs.com.br").status(LinkRequest.PENDING).createdAt(LocalDateTime.now()).build();
    }

    /** A decisão grava 2 linhas de auditoria (a da V74 com e-mail e a _BY só com ids): pega a da ação pedida. */
    private AuditLog savedAudit(String action) {
        ArgumentCaptor<AuditLog> all = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, atLeastOnce()).save(all.capture());
        return all.getAllValues().stream().filter(a -> action.equals(a.getAction())).findFirst()
                .orElseThrow(() -> new AssertionError("auditoria " + action + " não gravada"));
    }

    private static void assertStatus(HttpStatus expected, Runnable action) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(expected, ex.getStatusCode());
    }

    // --- criar ---

    @Test
    @DisplayName("Cria pedido PENDING com chave em maiúsculas, audita e notifica admins")
    void createOk() {
        when(projectConfigRepository.existsById("DDWMISSI")).thenReturn(true);
        when(projectMemberRoleRepository.findById("m1")).thenReturn(Optional.of(row("m1", null, true)));

        LinkRequestCreatedDto created = service.create(ana(), body(" ddwmissi ", " m1 ", " Ana.Nogueira@totvs.com.br ", "  sou o AM  "));

        assertEquals("PENDING", created.status());
        assertNotNull(created.id());
        ArgumentCaptor<LinkRequest> saved = ArgumentCaptor.forClass(LinkRequest.class);
        verify(linkRequestRepository).saveAndFlush(saved.capture());
        LinkRequest r = saved.getValue();
        assertEquals(created.id(), r.getId());
        assertEquals("u1", r.getRequesterUserId());
        assertEquals("DDWMISSI", r.getProjectKey());
        assertEquals("m1", r.getMemberId());
        assertEquals("Ana.Nogueira@totvs.com.br", r.getEmailInJira());
        assertEquals("sou o AM", r.getMessage());

        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        assertEquals("LINK_REQUEST_CREATED", audit.getValue().getAction());
        assertEquals("ana@gmail.com", audit.getValue().getPerformedBy());
        assertTrue(audit.getValue().getDetails().contains(created.id()));
        verify(notificationTriggers).linkRequestCreated(created.id(), "u1", "Ana Paula", "DDWMISSI", Set.of());
    }

    @Test
    @DisplayName("memberId e mensagem são opcionais")
    void createWithoutMember() {
        when(projectConfigRepository.existsById("DDWMISSI")).thenReturn(true);
        LinkRequestCreatedDto created = service.create(ana(), body("DDWMISSI", "  ", "a@b.com", ""));
        assertEquals("PENDING", created.status());
        ArgumentCaptor<LinkRequest> saved = ArgumentCaptor.forClass(LinkRequest.class);
        verify(linkRequestRepository).saveAndFlush(saved.capture());
        assertNull(saved.getValue().getMemberId());
        assertNull(saved.getValue().getMessage());
        verifyNoInteractions(projectMemberRoleRepository);
    }

    @Test
    @DisplayName("Já existe pedido pendente do usuário para a squad: 409 com mensagem clara")
    void createDuplicatePending() {
        when(projectConfigRepository.existsById("DDWMISSI")).thenReturn(true);
        when(linkRequestRepository.existsByRequesterUserIdAndProjectKeyAndStatus("u1", "DDWMISSI", "PENDING")).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.create(ana(), body("DDWMISSI", null, "a@b.com", null)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("Você já tem um pedido pendente para esta squad", ex.getReason());
        verify(linkRequestRepository, never()).saveAndFlush(any());
        verifyNoInteractions(notificationTriggers);
    }

    @Test
    @DisplayName("Corrida: o índice único parcial barra o segundo pendente e vira 409")
    void createRaceBecomes409() {
        when(projectConfigRepository.existsById("DDWMISSI")).thenReturn(true);
        when(linkRequestRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_link_requests_pending"));
        assertStatus(HttpStatus.CONFLICT, () -> service.create(ana(), body("DDWMISSI", null, "a@b.com", null)));
        verifyNoInteractions(notificationTriggers);
    }

    @Test
    @DisplayName("Projeto inexistente: 404")
    void createUnknownProject() {
        when(projectConfigRepository.existsById("NOPE")).thenReturn(false);
        assertStatus(HttpStatus.NOT_FOUND, () -> service.create(ana(), body("nope", null, "a@b.com", null)));
    }

    @Test
    @DisplayName("Projeto ausente ou corpo ausente: 400")
    void createMissingProjectOrBody() {
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.create(ana(), body("  ", null, "a@b.com", null)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.create(ana(), body(null, null, "a@b.com", null)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.create(ana(), null));
    }

    @Test
    @DisplayName("E-mail inválido ou ausente: 400")
    void createInvalidEmail() {
        when(projectConfigRepository.existsById("DDWMISSI")).thenReturn(true);
        for (String bad : new String[] { null, "", "   ", "semarroba", "a@b", "a b@c.com", "@x.com", "a@@x.com", "a".repeat(250) + "@x.com" }) {
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.create(ana(), body("DDWMISSI", null, bad, null)));
        }
        verify(linkRequestRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Mensagem com mais de 500 caracteres: 400; exatamente 500 passa")
    void createMessageLimit() {
        when(projectConfigRepository.existsById("DDWMISSI")).thenReturn(true);
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.create(ana(), body("DDWMISSI", null, "a@b.com", "x".repeat(501))));
        assertEquals("PENDING", service.create(ana(), body("DDWMISSI", null, "a@b.com", "x".repeat(500))).status());
    }

    @Test
    @DisplayName("Linha vinculada a OUTRA conta: 409")
    void createRowTakenByOtherAccount() {
        when(projectConfigRepository.existsById("DDWMISSI")).thenReturn(true);
        when(projectMemberRoleRepository.findById("m1")).thenReturn(Optional.of(row("m1", "outra", false)));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.create(ana(), body("DDWMISSI", "m1", "a@b.com", null)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("outra conta"));
    }

    @Test
    @DisplayName("Usuário já vinculado a essa linha (membro ativo): 409")
    void createAlreadyMember() {
        when(projectConfigRepository.existsById("DDWMISSI")).thenReturn(true);
        when(projectMemberRoleRepository.findById("m1")).thenReturn(Optional.of(row("m1", "u1", false)));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.create(ana(), body("DDWMISSI", "m1", "a@b.com", null)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("já está vinculado"));
    }

    @Test
    @DisplayName("Linha inexistente: 404; linha de outra squad: 422")
    void createRowNotFoundOrOtherProject() {
        when(projectConfigRepository.existsById("DDWMISSI")).thenReturn(true);
        when(projectMemberRoleRepository.findById("sumiu")).thenReturn(Optional.empty());
        assertStatus(HttpStatus.NOT_FOUND, () -> service.create(ana(), body("DDWMISSI", "sumiu", "a@b.com", null)));

        ProjectMemberRole other = row("m2", null, false);
        other.setProjectId("OUTRO");
        when(projectMemberRoleRepository.findById("m2")).thenReturn(Optional.of(other));
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> service.create(ana(), body("DDWMISSI", "m2", "a@b.com", null)));
    }

    @Test
    @DisplayName("Teto de pedidos pendentes por conta: 429")
    void createPendingCap() {
        when(projectConfigRepository.existsById("DDWMISSI")).thenReturn(true);
        when(linkRequestRepository.countByRequesterUserIdAndStatus("u1", "PENDING")).thenReturn(10L);
        assertStatus(HttpStatus.TOO_MANY_REQUESTS, () -> service.create(ana(), body("DDWMISSI", null, "a@b.com", null)));
        verify(linkRequestRepository, never()).saveAndFlush(any());
    }

    // --- listar ---

    @Test
    @DisplayName("mine: só os do usuário, com o nome da linha do roster")
    void mineListsOwnWithMemberName() {
        LinkRequest r = pending("p1", "m1");
        when(linkRequestRepository.findByRequesterUserIdOrderByCreatedAtDesc("u1")).thenReturn(List.of(r, pending("p2", null)));
        when(projectMemberRoleRepository.findAllById(Set.of("m1"))).thenReturn(List.of(row("m1", null, false)));

        List<LinkRequestDto> out = service.mine("u1");

        assertEquals(2, out.size());
        assertEquals("p1", out.get(0).id());
        assertEquals("Ana Paula Nogueira", out.get(0).memberName());
        assertEquals("PENDING", out.get(0).status());
        assertNotNull(out.get(0).createdAt());
        assertNull(out.get(0).decidedAt());
        assertNull(out.get(1).memberName());
        verify(linkRequestRepository, never()).findAllByOrderByCreatedAtDesc();
    }

    @Test
    @DisplayName("mine sem pedidos: lista vazia sem consultar o roster")
    void mineEmpty() {
        when(linkRequestRepository.findByRequesterUserIdOrderByCreatedAtDesc("u1")).thenReturn(List.of());
        assertTrue(service.mine("u1").isEmpty());
        verifyNoInteractions(projectMemberRoleRepository);
    }

    @Test
    @DisplayName("admin: sem status lista todos; com status filtra; traz o solicitante")
    void adminListAllAndByStatus() {
        LinkRequest r = pending("p1", "m1");
        when(linkRequestRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(r));
        when(linkRequestRepository.findByStatusOrderByCreatedAtDesc("PENDING")).thenReturn(List.of(r));
        when(userRepository.findAllById(Set.of("u1"))).thenReturn(List.of(ana()));
        when(projectMemberRoleRepository.findAllById(Set.of("m1"))).thenReturn(List.of(row("m1", null, false)));

        List<AdminLinkRequestDto> all = service.adminList(null);
        assertEquals(1, all.size());
        assertEquals("Ana Paula", all.get(0).requester().name());
        assertEquals("ana@gmail.com", all.get(0).requester().email());
        assertEquals("Ana Paula Nogueira", all.get(0).memberName());

        assertEquals(1, service.adminList(" pending ").size());
        assertEquals(1, service.adminList("").size());
        verify(linkRequestRepository, times(2)).findAllByOrderByCreatedAtDesc();
        verify(linkRequestRepository).findByStatusOrderByCreatedAtDesc("PENDING");
    }

    @Test
    @DisplayName("admin: status inválido é 400; solicitante apagado não derruba a listagem")
    void adminListInvalidStatusAndMissingRequester() {
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.adminList("QUALQUER"));

        when(linkRequestRepository.findByStatusOrderByCreatedAtDesc("REJECTED")).thenReturn(List.of(pending("p1", null)));
        when(userRepository.findAllById(Set.of("u1"))).thenReturn(List.of());
        List<AdminLinkRequestDto> out = service.adminList("REJECTED");
        assertEquals("u1", out.get(0).requester().id());
        assertNull(out.get(0).requester().name());
    }

    // --- aprovar ---

    private LinkRequest stubApproval(LinkRequest request, ProjectMemberRole row, User requester) {
        when(linkRequestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        if (row != null) when(projectMemberRoleRepository.findById(row.getId())).thenReturn(Optional.of(row));
        when(userRepository.findById("u1")).thenReturn(Optional.of(requester));
        lenient().when(userRepository.findAllById(any())).thenReturn(List.of(requester));
        return request;
    }

    @Test
    @DisplayName("Aprovar vincula a conta à linha, herda o accountId do Jira e liga o roster da squad")
    void approveLinksAccount() {
        User requester = ana();
        ProjectMemberRole row = row("m1", null, false);
        LinkRequest request = stubApproval(pending("p1", "m1"), row, requester);
        SquadMember sm = new SquadMember();
        when(squadMemberRepository.findByUserIdentifier("acc-ana")).thenReturn(List.of(sm));
        when(squadMemberRepository.findByUserIdentifier("ana.nogueira@totvs.com.br")).thenReturn(List.of());
        when(squadMemberRepository.findByUserIdentifier("Ana Paula Nogueira")).thenReturn(List.of());

        AdminLinkRequestDto out = service.approve("p1", null, "adm", "adm@totvs.com.br");

        assertEquals("u1", row.getUserId());
        assertEquals("acc-ana", requester.getJiraAccountId());
        verify(userRepository).save(requester);
        assertEquals("u1", sm.getClaimedByUid());
        verify(squadMemberRepository).save(sm);
        assertEquals("APPROVED", request.getStatus());
        assertEquals("APPROVED", out.status());
        assertEquals("adm@totvs.com.br", out.decidedBy());
        assertNotNull(out.decidedAt());
        assertEquals("m1", out.memberId());
        verify(linkRequestRepository).save(request);

        AuditLog audit = savedAudit("LINK_REQUEST_APPROVED");
        assertEquals("adm@totvs.com.br", audit.getPerformedBy());
        AuditLog by = savedAudit("LINK_REQUEST_APPROVED_BY");
        assertEquals("adm", by.getPerformedBy());
        assertTrue(by.getDetails().contains("via=ADMIN") && by.getDetails().contains("squad=DDWMISSI"));
        assertFalse(by.getDetails().contains("@"));
        verify(notificationTriggers).linkRequestDecided("p1", "u1", true, "DDWMISSI", null, "adm");
    }

    @Test
    @DisplayName("Aprovar aceita linha de liderança e não exige e-mail igual (admin confirma a identidade)")
    void approveAllowsLeadershipAndDifferentEmail() {
        User requester = ana();
        requester.setJiraAccountId("conta-propria");
        ProjectMemberRole row = row("m1", null, true);
        stubApproval(pending("p1", "m1"), row, requester);

        service.approve("p1", null, "adm", "adm@totvs.com.br");

        assertEquals("u1", row.getUserId());
        assertTrue(row.isLeadership());
        assertEquals("conta-propria", requester.getJiraAccountId());
        verify(userRepository, never()).save(requester);

        assertTrue(savedAudit("LINK_REQUEST_APPROVED").getDetails().contains("liderança"));
    }

    @Test
    @DisplayName("memberId do corpo do approve sobrescreve o do pedido, e fica gravado no pedido")
    void approveOverrideMember() {
        User requester = ana();
        ProjectMemberRole chosen = row("m2", null, false);
        LinkRequest request = stubApproval(pending("p1", "m1"), chosen, requester);

        AdminLinkRequestDto out = service.approve("p1", " m2 ", "adm", "adm@totvs.com.br");

        assertEquals("m2", request.getMemberId());
        assertEquals("m2", out.memberId());
        assertEquals("u1", chosen.getUserId());
        verify(projectMemberRoleRepository, never()).findById("m1");
    }

    @Test
    @DisplayName("Linha já vinculada a outra conta: 409 e nada é decidido")
    void approveRowTaken() {
        ProjectMemberRole row = row("m1", "outra", false);
        LinkRequest request = stubApproval(pending("p1", "m1"), row, ana());

        assertStatus(HttpStatus.CONFLICT, () -> service.approve("p1", null, "adm", "adm@totvs.com.br"));

        assertEquals("outra", row.getUserId());
        assertEquals("PENDING", request.getStatus());
        verify(linkRequestRepository, never()).save(any());
        verifyNoInteractions(auditLogRepository, notificationTriggers);
    }

    @Test
    @DisplayName("Sem memberId no pedido nem no corpo: 422 'Informe a linha do roster'")
    void approveWithoutMember() {
        LinkRequest request = pending("p1", null);
        when(linkRequestRepository.findByIdForUpdate("p1")).thenReturn(Optional.of(request));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.approve("p1", "  ", "adm", "x"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        assertEquals("Informe a linha do roster", ex.getReason());
        assertEquals("PENDING", request.getStatus());
        verify(linkRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("Pedido já decidido: 409 ao aprovar ou recusar de novo; inexistente: 404")
    void alreadyDecidedAndUnknown() {
        LinkRequest done = pending("p1", "m1");
        done.setStatus(LinkRequest.APPROVED);
        when(linkRequestRepository.findByIdForUpdate("p1")).thenReturn(Optional.of(done));
        when(linkRequestRepository.findByIdForUpdate("nada")).thenReturn(Optional.empty());

        assertStatus(HttpStatus.CONFLICT, () -> service.approve("p1", null, "adm", "x"));
        assertStatus(HttpStatus.CONFLICT, () -> service.reject("p1", "não", "adm", "x"));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.approve("nada", null, "adm", "x"));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.reject("nada", null, "adm", "x"));
        verifyNoInteractions(auditLogRepository, notificationTriggers);
    }

    @Test
    @DisplayName("Linha de outra squad, linha inexistente e solicitante apagado")
    void approveInvalidTargets() {
        LinkRequest request = pending("p1", "m1");
        when(linkRequestRepository.findByIdForUpdate("p1")).thenReturn(Optional.of(request));
        ProjectMemberRole other = row("m1", null, false);
        other.setProjectId("OUTRO");
        when(projectMemberRoleRepository.findById("m1")).thenReturn(Optional.of(other));
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> service.approve("p1", null, "adm", "x"));

        when(projectMemberRoleRepository.findById("m9")).thenReturn(Optional.empty());
        assertStatus(HttpStatus.NOT_FOUND, () -> service.approve("p1", "m9", "adm", "x"));

        when(projectMemberRoleRepository.findById("m2")).thenReturn(Optional.of(row("m2", null, false)));
        when(userRepository.findById("u1")).thenReturn(Optional.empty());
        assertStatus(HttpStatus.NOT_FOUND, () -> service.approve("p1", "m2", "adm", "x"));
        assertEquals("PENDING", request.getStatus());
    }

    // --- recusar ---

    @Test
    @DisplayName("Recusar grava status, motivo e quem decidiu; audita e notifica quem pediu; não vincula nada")
    void rejectOk() {
        LinkRequest request = pending("p1", "m1");
        when(linkRequestRepository.findByIdForUpdate("p1")).thenReturn(Optional.of(request));
        when(userRepository.findAllById(any())).thenReturn(List.of(ana()));

        AdminLinkRequestDto out = service.reject("p1", " e-mail não confere ", "adm", "adm@totvs.com.br");

        assertEquals("REJECTED", out.status());
        assertEquals("e-mail não confere", out.decisionNote());
        assertEquals("adm@totvs.com.br", out.decidedBy());
        assertNotNull(out.decidedAt());
        verify(linkRequestRepository).save(request);
        verify(projectMemberRoleRepository, never()).save(any());
        verifyNoInteractions(squadMemberRepository);

        assertTrue(savedAudit("LINK_REQUEST_REJECTED").getDetails().contains("e-mail não confere"));
        assertEquals("adm", savedAudit("LINK_REQUEST_REJECTED_BY").getPerformedBy());
        verify(notificationTriggers).linkRequestDecided("p1", "u1", false, "DDWMISSI", "e-mail não confere", "adm");
    }

    @Test
    @DisplayName("Recusar sem motivo é permitido; motivo acima de 500 caracteres é 400")
    void rejectReasonRules() {
        LinkRequest request = pending("p1", null);
        when(linkRequestRepository.findByIdForUpdate("p1")).thenReturn(Optional.of(request));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.reject("p1", "x".repeat(501), "adm", "x"));
        assertEquals("PENDING", request.getStatus());

        AdminLinkRequestDto out = service.reject("p1", null, null, null);
        assertEquals("REJECTED", out.status());
        assertNull(out.decisionNote());
        assertEquals("ADMIN", savedAudit("LINK_REQUEST_REJECTED").getPerformedBy());
        verify(notificationTriggers).linkRequestDecided(eq("p1"), eq("u1"), eq(false), eq("DDWMISSI"), isNull(), isNull());
    }
}
