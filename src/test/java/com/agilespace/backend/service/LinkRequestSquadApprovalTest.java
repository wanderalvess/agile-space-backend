package com.agilespace.backend.service;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.domain.LinkRequest;
import com.agilespace.backend.domain.MembershipSource;
import com.agilespace.backend.domain.ProjectConfig;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.AdminLinkRequestDto;
import com.agilespace.backend.dto.LinkRequestCreateRequest;
import com.agilespace.backend.dto.MembershipDto;
import com.agilespace.backend.dto.MembershipDto.RoleDto;
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
import org.springframework.data.domain.Pageable;
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

/**
 * Pedido de vínculo decidido pela liderança da squad: a política REAL (allowlist fixa) decide, o resto são mocks. Cobre
 * quem aprova o quê, autoaprovação, rota de outra squad, flag off, criação de pmr para contribuidor sem linha e
 * os destinatários da notificação.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LinkRequestService - aprovação pela liderança da squad (Etapa 3)")
class LinkRequestSquadApprovalTest {

    @Mock private LinkRequestRepository linkRequestRepository;
    @Mock private ProjectConfigRepository projectConfigRepository;
    @Mock private ProjectMemberRoleRepository projectMemberRoleRepository;
    @Mock private SquadMemberRepository squadMemberRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private NotificationTriggers notificationTriggers;
    @Mock private MembershipService membershipService;

    private LinkRequestService service;

    @BeforeEach
    void setup() {
        service = serviceWithFlag("on");
    }

    private LinkRequestService serviceWithFlag(String flag) {
        OnboardingService onboarding = new OnboardingService(projectMemberRoleRepository, projectConfigRepository,
                squadMemberRepository, userRepository);
        LinkRequestApprovalPolicy policy = new LinkRequestApprovalPolicy(membershipService, projectConfigRepository,
                projectMemberRoleRepository, userRepository, flag);
        return new LinkRequestService(linkRequestRepository, projectConfigRepository, projectMemberRoleRepository,
                userRepository, onboarding, auditLogRepository, notificationTriggers, policy);
    }

    // ---------- fixtures ----------

    private static User person(String id, String role) {
        return User.builder().id(id).name("Pessoa " + id).email(id + "@totvs.com.br").role(role).build();
    }

    private static MembershipDto direct(String squad, String tribe, String key, boolean leadership) {
        RoleDto r = new RoleDto(key, key, leadership, "JIRA", true);
        return new MembershipDto(squad, squad, "Seg", tribe, key, key, leadership, true, MembershipDto.SCOPE_SQUAD, "JIRA", List.of(r));
    }

    /** Conta logada e carregada pelo id, com o vínculo informado. */
    private User caller(String id, String role, MembershipDto... memberships) {
        User u = person(id, role);
        lenient().when(userRepository.findById(id)).thenReturn(Optional.of(u));
        lenient().when(membershipService.membershipsOf(u)).thenReturn(List.of(memberships));
        return u;
    }

    private void squadExists(String squad, String tribe) {
        lenient().when(projectConfigRepository.existsById(squad)).thenReturn(true);
        lenient().when(projectConfigRepository.findById(squad))
                .thenReturn(Optional.of(ProjectConfig.builder().id(squad).name(squad).tribeName(tribe).build()));
    }

    private LinkRequest pendingRequest(String id, String requester, String squad, String memberId, String kind, String roleName) {
        LinkRequest r = LinkRequest.builder().id(id).requesterUserId(requester).projectKey(squad).memberId(memberId)
                .emailInJira("x@jira.com").status(LinkRequest.PENDING).kind(kind).requestedRoleName(roleName)
                .createdAt(LocalDateTime.now()).build();
        lenient().when(linkRequestRepository.findByIdForUpdate(id)).thenReturn(Optional.of(r));
        return r;
    }

    private ProjectMemberRole rosterRow(String id, String squad, String key, boolean leadership) {
        ProjectMemberRole row = ProjectMemberRole.builder().id(id).projectId(squad).roleName(key).roleKey(key)
                .displayName("Fulano " + id).isLeadership(leadership).build();
        lenient().when(projectMemberRoleRepository.findById(id)).thenReturn(Optional.of(row));
        return row;
    }

    private void requesterExists(String id) {
        lenient().when(userRepository.findById(id)).thenReturn(Optional.of(person(id, "MEMBER")));
        lenient().when(userRepository.findAllById(any())).thenAnswer(inv -> List.of(person(id, "MEMBER")));
    }

    private static void assertStatus(HttpStatus expected, Runnable action) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(expected, ex.getStatusCode());
    }

    private AuditLog audit(String action) {
        ArgumentCaptor<AuditLog> all = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, atLeastOnce()).save(all.capture());
        return all.getAllValues().stream().filter(a -> action.equals(a.getAction())).findFirst().orElseThrow();
    }

    // ---------- quem aprova contribuidor ----------

    @Test
    @DisplayName("Agile Master da squad aprova contribuidor: liga a linha, audita só com ids e avisa o solicitante")
    void agileMasterApprovesContributor() {
        squadExists("SQ", "Tribo A");
        User am = caller("am", "MEMBER", direct("SQ", "Tribo A", "AGILE_MASTER", true));
        requesterExists("dev1");
        ProjectMemberRole row = rosterRow("m1", "SQ", "DEVELOPER", false);
        LinkRequest req = pendingRequest("r1", "dev1", "SQ", "m1", "CONTRIBUTOR", null);

        AdminLinkRequestDto out = service.approveInSquad("sq", "r1", null, am.getId(), am.getEmail());

        assertEquals("APPROVED", out.status());
        assertEquals("APPROVED", req.getStatus());
        assertEquals("dev1", row.getUserId());
        AuditLog by = audit("LINK_REQUEST_APPROVED_BY");
        assertEquals("am", by.getPerformedBy());
        assertTrue(by.getDetails().contains("via=SQUAD") && by.getDetails().contains("squad=SQ")
                && by.getDetails().contains("solicitante=dev1") && by.getDetails().contains("pedido=r1"));
        assertFalse(by.getDetails().contains("@"));
        verify(notificationTriggers).linkRequestDecided("r1", "dev1", true, "SQ", null, "am");
    }

    @Test
    @DisplayName("Product Owner e People Lead da squad também aprovam contribuidor")
    void productOwnerAndPeopleLeadApproveContributor() {
        squadExists("SQ", "Tribo A");
        requesterExists("dev1");
        rosterRow("m1", "SQ", "DEVELOPER", false);
        for (String key : List.of("PRODUCT_OWNER", "PEOPLE_LEAD")) {
            User approver = caller("a-" + key, "MEMBER", direct("SQ", "Tribo A", key, true));
            LinkRequest req = pendingRequest("r-" + key, "dev1", "SQ", "m1", "CONTRIBUTOR", null);
            ProjectMemberRole row = rosterRow("m1", "SQ", "DEVELOPER", false);
            assertEquals("APPROVED", service.approveInSquad("SQ", req.getId(), null, approver.getId(), "x").status(), key);
            assertEquals("dev1", row.getUserId(), key);
        }
    }

    @Test
    @DisplayName("Developer da squad não aprova: 403 e o pedido segue pendente")
    void developerCannotApprove() {
        squadExists("SQ", "Tribo A");
        User dev = caller("dev", "MEMBER", direct("SQ", "Tribo A", "DEVELOPER", false));
        LinkRequest req = pendingRequest("r1", "dev1", "SQ", "m1", "CONTRIBUTOR", null);

        assertStatus(HttpStatus.FORBIDDEN, () -> service.approveInSquad("SQ", "r1", null, dev.getId(), "x"));
        assertStatus(HttpStatus.FORBIDDEN, () -> service.rejectInSquad("SQ", "r1", "não", dev.getId(), "x"));
        assertStatus(HttpStatus.FORBIDDEN, () -> service.squadList(dev.getId(), "SQ", null, null, null));

        assertEquals("PENDING", req.getStatus());
        verify(linkRequestRepository, never()).save(any());
        verifyNoInteractions(auditLogRepository, notificationTriggers);
        assertEquals(new LinkRequestApprovalPolicy.Eligibility(false, false), service.canApprove("dev", "SQ"));
    }

    @Test
    @DisplayName("Quem tem cargo de liderança só no jobTitle/papel autodeclarado (sem is_leadership) não aprova")
    void selfDeclaredRoleGivesNoPower() {
        squadExists("SQ", "Tribo A");
        // roleKey de AM mas linha sem is_leadership (convite/join/backfill)
        User fake = caller("fake", "MEMBER", direct("SQ", "Tribo A", "AGILE_MASTER", false));
        pendingRequest("r1", "dev1", "SQ", "m1", "CONTRIBUTOR", null);
        assertStatus(HttpStatus.FORBIDDEN, () -> service.approveInSquad("SQ", "r1", null, fake.getId(), "x"));
    }

    @Test
    @DisplayName("AM de OUTRA squad não aprova")
    void amOfOtherSquadCannotApprove() {
        squadExists("SQ", "Tribo A");
        User am = caller("am", "MEMBER", direct("OUTRA", "Tribo B", "AGILE_MASTER", true));
        pendingRequest("r1", "dev1", "SQ", "m1", "CONTRIBUTOR", null);
        assertStatus(HttpStatus.FORBIDDEN, () -> service.approveInSquad("SQ", "r1", null, am.getId(), "x"));
    }

    // ---------- liderança ----------

    @Test
    @DisplayName("AM não aprova pedido de liderança (403); recusar também exige o mesmo poder")
    void agileMasterCannotApproveLeadership() {
        squadExists("SQ", "Tribo A");
        User am = caller("am", "MEMBER", direct("SQ", "Tribo A", "AGILE_MASTER", true));
        ProjectMemberRole poRow = rosterRow("m-po", "SQ", "PRODUCT_OWNER", true);
        LinkRequest req = pendingRequest("r1", "ana", "SQ", "m-po", "LEADERSHIP", null);

        assertStatus(HttpStatus.FORBIDDEN, () -> service.approveInSquad("SQ", "r1", null, am.getId(), "x"));
        assertStatus(HttpStatus.FORBIDDEN, () -> service.rejectInSquad("SQ", "r1", null, am.getId(), "x"));
        assertEquals("PENDING", req.getStatus());
        assertNull(poRow.getUserId());
        verifyNoInteractions(notificationTriggers);
    }

    @Test
    @DisplayName("People Lead aprova pedido de liderança; Tribe Lead da tribo também")
    void peopleLeadApprovesLeadership() {
        squadExists("SQ", "Tribo A");
        requesterExists("ana");
        User pl = caller("pl", "MEMBER", direct("SQ", "Tribo A", "PEOPLE_LEAD", true));
        ProjectMemberRole poRow = rosterRow("m-po", "SQ", "PRODUCT_OWNER", true);
        pendingRequest("r1", "ana", "SQ", "m-po", "LEADERSHIP", null);

        assertEquals("APPROVED", service.approveInSquad("SQ", "r1", null, pl.getId(), "x").status());
        assertEquals("ana", poRow.getUserId());

        // Tribe Lead de outra squad da mesma tribo (expansão de tribo)
        User tl = caller("tl", "MEMBER", direct("IRMA", "Tribo A", "TRIBE_LEAD", true));
        ProjectMemberRole poRow2 = rosterRow("m-po2", "SQ", "PRODUCT_OWNER", true);
        pendingRequest("r2", "ana", "SQ", "m-po2", "LEADERSHIP", null);
        assertEquals("APPROVED", service.approveInSquad("SQ", "r2", null, tl.getId(), "x").status());
        assertEquals("ana", poRow2.getUserId());
    }

    @Test
    @DisplayName("Escalada: AM não promove um pedido de contribuidor apontando uma linha de liderança no approve")
    void overrideToLeadershipRowEscalatesKind() {
        squadExists("SQ", "Tribo A");
        User am = caller("am", "MEMBER", direct("SQ", "Tribo A", "AGILE_MASTER", true));
        ProjectMemberRole poRow = rosterRow("m-po", "SQ", "PRODUCT_OWNER", true);
        LinkRequest req = pendingRequest("r1", "dev1", "SQ", null, "CONTRIBUTOR", "Developer");

        assertStatus(HttpStatus.FORBIDDEN, () -> service.approveInSquad("SQ", "r1", "m-po", am.getId(), "x"));

        assertNull(poRow.getUserId());
        assertEquals("PENDING", req.getStatus());
        verify(projectMemberRoleRepository, never()).save(any());
    }

    @Test
    @DisplayName("Pedido kind=LEADERSHIP sem linha também exige People/Tribe Lead")
    void leadershipKindWithoutRow() {
        squadExists("SQ", "Tribo A");
        User am = caller("am", "MEMBER", direct("SQ", "Tribo A", "AGILE_MASTER", true));
        pendingRequest("r1", "ana", "SQ", null, "LEADERSHIP", null);
        assertStatus(HttpStatus.FORBIDDEN, () -> service.approveInSquad("SQ", "r1", null, am.getId(), "x"));
    }

    // ---------- autoaprovação ----------

    @Test
    @DisplayName("Autoaprovação recusada, inclusive de quem é AM e pede outro vínculo, e do ADMIN; recusar o próprio também")
    void selfApprovalRefused() {
        squadExists("SQ", "Tribo A");
        User am = caller("am", "MEMBER", direct("SQ", "Tribo A", "AGILE_MASTER", true));
        User admin = caller("adm", "ADMIN");
        LinkRequest own = pendingRequest("r1", "am", "SQ", "m1", "CONTRIBUTOR", null);
        LinkRequest ownAdmin = pendingRequest("r2", "adm", "SQ", "m1", "CONTRIBUTOR", null);
        rosterRow("m1", "SQ", "DEVELOPER", false);

        assertStatus(HttpStatus.FORBIDDEN, () -> service.approveInSquad("SQ", "r1", null, am.getId(), "x"));
        assertStatus(HttpStatus.FORBIDDEN, () -> service.rejectInSquad("SQ", "r1", null, am.getId(), "x"));
        assertStatus(HttpStatus.FORBIDDEN, () -> service.approveInSquad("SQ", "r2", null, admin.getId(), "x"));
        assertStatus(HttpStatus.FORBIDDEN, () -> service.approve("r2", null, "adm", "adm@x"));
        assertEquals("PENDING", own.getStatus());
        assertEquals("PENDING", ownAdmin.getStatus());
        verifyNoInteractions(notificationTriggers);
    }

    // ---------- rota por squad ----------

    @Test
    @DisplayName("Pedido de outra squad pela rota errada: 404 (mesmo se já decidido, para não vazar)")
    void requestFromOtherSquadIs404() {
        squadExists("SQ", "Tribo A");
        squadExists("OUTRA", "Tribo A");
        User am = caller("am", "MEMBER", direct("SQ", "Tribo A", "AGILE_MASTER", true));
        LinkRequest elsewhere = pendingRequest("r1", "dev1", "OUTRA", "m1", "CONTRIBUTOR", null);
        LinkRequest decided = pendingRequest("r2", "dev1", "OUTRA", "m1", "CONTRIBUTOR", null);
        decided.setStatus(LinkRequest.APPROVED);

        assertStatus(HttpStatus.NOT_FOUND, () -> service.approveInSquad("SQ", "r1", null, am.getId(), "x"));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.rejectInSquad("SQ", "r1", null, am.getId(), "x"));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.approveInSquad("SQ", "r2", null, am.getId(), "x"));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.approveInSquad("SQ", "inexistente", null, am.getId(), "x"));
        assertEquals("PENDING", elsewhere.getStatus());
    }

    @Test
    @DisplayName("Squad inexistente: 404; pedido já decidido: 409; sessão sem conta: 401")
    void unknownSquadAlreadyDecidedAndNoSession() {
        squadExists("SQ", "Tribo A");
        User am = caller("am", "MEMBER", direct("SQ", "Tribo A", "AGILE_MASTER", true));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.canApprove(am.getId(), "NOPE"));
        LinkRequest done = pendingRequest("r1", "dev1", "SQ", "m1", "CONTRIBUTOR", null);
        done.setStatus(LinkRequest.REJECTED);
        assertStatus(HttpStatus.CONFLICT, () -> service.approveInSquad("SQ", "r1", null, am.getId(), "x"));
        assertStatus(HttpStatus.UNAUTHORIZED, () -> service.canApprove(null, "SQ"));
        when(userRepository.findById("fantasma")).thenReturn(Optional.empty());
        assertStatus(HttpStatus.UNAUTHORIZED, () -> service.canApprove("fantasma", "SQ"));
    }

    @Test
    @DisplayName("Alias MISSI resolve para DDWMISSI e o pedido da squad canônica casa")
    void missiAliasRoutes() {
        squadExists("DDWMISSI", "Tribo A");
        User am = caller("am", "MEMBER", direct("DDWMISSI", "Tribo A", "AGILE_MASTER", true));
        requesterExists("dev1");
        rosterRow("m1", "DDWMISSI", "DEVELOPER", false);
        pendingRequest("r1", "dev1", "DDWMISSI", "m1", "CONTRIBUTOR", null);
        assertEquals("APPROVED", service.approveInSquad("missi", "r1", null, am.getId(), "x").status());
    }

    @Test
    @DisplayName("can-approve devolve só os poderes do próprio chamador")
    void canApproveForCaller() {
        squadExists("SQ", "Tribo A");
        caller("am", "MEMBER", direct("SQ", "Tribo A", "AGILE_MASTER", true));
        caller("pl", "MEMBER", direct("SQ", "Tribo A", "PEOPLE_LEAD", true));
        caller("adm", "ADMIN");
        assertEquals(new LinkRequestApprovalPolicy.Eligibility(true, false), service.canApprove("am", "SQ"));
        assertEquals(new LinkRequestApprovalPolicy.Eligibility(true, true), service.canApprove("pl", "SQ"));
        assertEquals(new LinkRequestApprovalPolicy.Eligibility(true, true), service.canApprove("adm", "SQ"));
    }

    // ---------- lista ----------

    @Test
    @DisplayName("Lista da squad: PENDING por padrão, ALL sem filtro, canDecide por tipo, status inválido 400, paginação limitada")
    void squadList() {
        squadExists("SQ", "Tribo A");
        User am = caller("am", "MEMBER", direct("SQ", "Tribo A", "AGILE_MASTER", true));
        ProjectMemberRole poRow = rosterRow("m-po", "SQ", "PRODUCT_OWNER", true);
        LinkRequest contrib = pendingRequest("r1", "dev1", "SQ", null, "CONTRIBUTOR", "QA");
        LinkRequest lead = pendingRequest("r2", "ana", "SQ", "m-po", "LEADERSHIP", null);
        when(linkRequestRepository.findByProjectKeyInAndStatusOrderByCreatedAtDesc(eq(Set.of("SQ")), eq("PENDING"), any(Pageable.class)))
                .thenReturn(List.of(contrib, lead));
        when(linkRequestRepository.findByProjectKeyInOrderByCreatedAtDesc(eq(Set.of("SQ")), any(Pageable.class)))
                .thenReturn(List.of(lead));
        when(userRepository.findAllById(any())).thenReturn(List.of(person("dev1", "MEMBER"), person("ana", "MEMBER")));
        when(projectMemberRoleRepository.findAllById(any())).thenReturn(List.of(poRow));

        List<AdminLinkRequestDto> out = service.squadList(am.getId(), "sq", null, null, null);

        assertEquals(2, out.size());
        assertEquals("CONTRIBUTOR", out.get(0).kind());
        assertEquals("QA", out.get(0).requestedRoleName());
        assertEquals(Boolean.TRUE, out.get(0).canDecide());
        assertEquals("LEADERSHIP", out.get(1).kind());
        assertEquals(Boolean.FALSE, out.get(1).canDecide());
        assertEquals("Fulano m-po", out.get(1).memberName());

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(linkRequestRepository).findByProjectKeyInAndStatusOrderByCreatedAtDesc(any(), eq("PENDING"), page.capture());
        assertEquals(0, page.getValue().getPageNumber());
        assertEquals(LinkRequestService.DEFAULT_PAGE_SIZE, page.getValue().getPageSize());

        assertEquals(1, service.squadList(am.getId(), "SQ", " all ", 0, 50).size());
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.squadList(am.getId(), "SQ", "XYZ", null, null));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.squadList(am.getId(), "SQ", null, null, 201));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.squadList(am.getId(), "SQ", null, -1, null));
    }

    @Test
    @DisplayName("Lista de admin não preenche canDecide e traz kind e requestedRoleName")
    void adminListHasKindWithoutCanDecide() {
        LinkRequest r = pendingRequest("r1", "dev1", "SQ", null, "CONTRIBUTOR", "Designer");
        when(linkRequestRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(r));
        when(userRepository.findAllById(any())).thenReturn(List.of(person("dev1", "MEMBER")));
        AdminLinkRequestDto dto = service.adminList(null).get(0);
        assertEquals("CONTRIBUTOR", dto.kind());
        assertEquals("Designer", dto.requestedRoleName());
        assertNull(dto.canDecide());
    }

    // ---------- flag off ----------

    @Test
    @DisplayName("Flag off: rotas por squad 404; AM não aprova; ADMIN continua aprovando pela rota de admin")
    void flagOff() {
        LinkRequestService off = serviceWithFlag("off");
        squadExists("SQ", "Tribo A");
        User am = caller("am", "MEMBER", direct("SQ", "Tribo A", "AGILE_MASTER", true));
        requesterExists("dev1");
        rosterRow("m1", "SQ", "DEVELOPER", false);
        pendingRequest("r1", "dev1", "SQ", "m1", "CONTRIBUTOR", null);

        assertStatus(HttpStatus.NOT_FOUND, () -> off.canApprove(am.getId(), "SQ"));
        assertStatus(HttpStatus.NOT_FOUND, () -> off.squadList(am.getId(), "SQ", null, null, null));
        assertStatus(HttpStatus.NOT_FOUND, () -> off.approveInSquad("SQ", "r1", null, am.getId(), "x"));
        assertStatus(HttpStatus.NOT_FOUND, () -> off.rejectInSquad("SQ", "r1", null, am.getId(), "x"));
        verify(linkRequestRepository, never()).save(any());

        assertEquals("APPROVED", off.approve("r1", null, "adm", "adm@totvs.com.br").status());
    }

    // ---------- criação do pedido ----------

    private LinkRequestCreateRequest body(String memberId, String kind, String role) {
        return new LinkRequestCreateRequest("SQ", memberId, "a@b.com", null, kind, role);
    }

    @Test
    @DisplayName("Criação: contribuidor fora do roster pede papel da allowlist; kind CONTRIBUTOR; notifica os aprovadores sem o solicitante")
    void createContributorWithRequestedRole() {
        when(projectConfigRepository.existsById("SQ")).thenReturn(true);
        LinkRequestApprovalPolicy policy = mock(LinkRequestApprovalPolicy.class);
        when(policy.approverUserIds("SQ", "CONTRIBUTOR")).thenReturn(Set.of("am", "u1"));
        LinkRequestService svc = new LinkRequestService(linkRequestRepository, projectConfigRepository, projectMemberRoleRepository,
                userRepository, new OnboardingService(projectMemberRoleRepository, projectConfigRepository, squadMemberRepository, userRepository),
                auditLogRepository, notificationTriggers, policy);

        var created = svc.create(person("u1", "MEMBER"), body(null, null, "  analista de qa "));

        ArgumentCaptor<LinkRequest> saved = ArgumentCaptor.forClass(LinkRequest.class);
        verify(linkRequestRepository).saveAndFlush(saved.capture());
        assertEquals("CONTRIBUTOR", saved.getValue().getKind());
        assertEquals("analista de qa", saved.getValue().getRequestedRoleName());
        assertNull(saved.getValue().getMemberId());
        verify(notificationTriggers).linkRequestCreated(created.id(), "u1", "Pessoa u1", "SQ", Set.of("am"));
    }

    @Test
    @DisplayName("Criação: papel de liderança, desconhecido ou combinado com linha/LEADERSHIP é recusado (422)")
    void createRejectsLeadershipRoleAsContributor() {
        when(projectConfigRepository.existsById("SQ")).thenReturn(true);
        User u = person("u1", "MEMBER");
        for (String bad : List.of("Agile Master", "Product Owner", "People Lead", "Tribe Lead", "Agile Coach", "AGILE_MASTER",
                "Scrum Master", "Administrador", "Developer; DROP")) {
            assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> service.create(u, body(null, null, bad)));
            assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> service.create(u, body(null, "CONTRIBUTOR", bad)));
        }
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> service.create(u, body(null, "LEADERSHIP", "Developer")));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.create(u, body(null, "ROOT", null)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.create(u, body(null, null, "x".repeat(81))));
        verify(linkRequestRepository, never()).saveAndFlush(any());
        verifyNoInteractions(notificationTriggers);
    }

    @Test
    @DisplayName("Criação: kind vem da linha; contradição com a linha e linha + papel pedido são 422")
    void createDerivesKindFromRow() {
        when(projectConfigRepository.existsById("SQ")).thenReturn(true);
        rosterRow("m-lead", "SQ", "PRODUCT_OWNER", true);
        rosterRow("m-dev", "SQ", "DEVELOPER", false);
        User u = person("u1", "MEMBER");

        service.create(u, body("m-lead", null, null));
        service.create(person("u2", "MEMBER"), body("m-dev", "contributor", null));
        ArgumentCaptor<LinkRequest> saved = ArgumentCaptor.forClass(LinkRequest.class);
        verify(linkRequestRepository, times(2)).saveAndFlush(saved.capture());
        assertEquals("LEADERSHIP", saved.getAllValues().get(0).getKind());
        assertEquals("CONTRIBUTOR", saved.getAllValues().get(1).getKind());

        // não rebaixa uma linha de liderança para CONTRIBUTOR nem promove linha comum
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> service.create(person("u3", "MEMBER"), body("m-lead", "CONTRIBUTOR", null)));
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> service.create(person("u4", "MEMBER"), body("m-dev", "LEADERSHIP", null)));
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> service.create(person("u5", "MEMBER"), body("m-dev", null, "Developer")));
    }

    @Test
    @DisplayName("Criação: quem já tem linha com esse papel na squad leva 409")
    void createAlreadyHasRole() {
        when(projectConfigRepository.existsById("SQ")).thenReturn(true);
        when(projectMemberRoleRepository.findByProjectId("SQ")).thenReturn(List.of(
                ProjectMemberRole.builder().id("x").projectId("SQ").roleKey("QA").roleName("QA").displayName("U").userId("u1").build()));
        assertStatus(HttpStatus.CONFLICT, () -> service.create(person("u1", "MEMBER"), body(null, null, "QA")));
    }

    // ---------- criação da pmr na aprovação ----------

    @Test
    @DisplayName("Aprovar contribuidor sem linha cria a pmr: source=APPROVED, não-liderança, papel da allowlist, dados do solicitante")
    void approveCreatesPmrForContributorWithoutRow() {
        squadExists("SQ", "Tribo A");
        User am = caller("am", "MEMBER", direct("SQ", "Tribo A", "AGILE_MASTER", true));
        User dev = User.builder().id("dev1").name("Dev Um").email("dev1@totvs.com.br").jiraAccountId("acc-dev").role("MEMBER").build();
        when(userRepository.findById("dev1")).thenReturn(Optional.of(dev));
        when(userRepository.findAllById(any())).thenReturn(List.of(dev));
        LinkRequest req = pendingRequest("r1", "dev1", "SQ", null, "CONTRIBUTOR", "Analista de QA");

        AdminLinkRequestDto out = service.approveInSquad("SQ", "r1", null, am.getId(), "am@totvs.com.br");

        ArgumentCaptor<ProjectMemberRole> saved = ArgumentCaptor.forClass(ProjectMemberRole.class);
        verify(projectMemberRoleRepository, atLeastOnce()).save(saved.capture());
        ProjectMemberRole created = saved.getAllValues().get(0);
        assertEquals("SQ", created.getProjectId());
        assertEquals("dev1", created.getUserId());
        assertEquals(MembershipSource.APPROVED, created.getSource());
        assertFalse(created.isLeadership());
        assertEquals("Analista de QA", created.getRoleName());
        assertEquals("ANALISTA_DE_QA", created.getRoleKey());
        assertEquals("Dev Um", created.getDisplayName());
        assertEquals("dev1@totvs.com.br", created.getEmail());
        assertEquals("acc-dev", created.getJiraAccountId());
        assertEquals("APPROVED", out.status());
        assertEquals("APPROVED", req.getStatus());
        // conta sem squad ativa passa a ter a squad aprovada como preferência (users.squad_id aposentada)
        assertEquals("SQ", dev.getDefaultProjectId());
    }

    @Test
    @DisplayName("A aprovação só preenche a preferência de squad vazia, nunca troca a ativa; 'Sem Time' conta como vazio")
    void approvalOnlyFillsEmptyPreference() {
        squadExists("SQ", "Tribo A");
        User am = caller("am", "MEMBER", direct("SQ", "Tribo A", "AGILE_MASTER", true));
        User dev = User.builder().id("dev1").name("Dev").email("d@x.com").role("MEMBER").defaultProjectId("Sem Time").build();
        when(userRepository.findById("dev1")).thenReturn(Optional.of(dev));
        when(userRepository.findAllById(any())).thenReturn(List.of(dev));
        pendingRequest("r1", "dev1", "SQ", null, "CONTRIBUTOR", "Developer");

        service.approveInSquad("SQ", "r1", null, am.getId(), "x");

        assertEquals("SQ", dev.getDefaultProjectId());
        assertNull(dev.getActiveSquadId());
    }

    @Test
    @DisplayName("Papel pedido adulterado no banco (liderança) nunca vira pmr: 422")
    void tamperedRequestedRoleNeverCreatesLeadershipRow() {
        squadExists("SQ", "Tribo A");
        User admin = caller("adm", "ADMIN");
        requesterExists("dev1");
        pendingRequest("r1", "dev1", "SQ", null, "CONTRIBUTOR", "Product Owner");
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> service.approveInSquad("SQ", "r1", null, admin.getId(), "x"));
        verify(projectMemberRoleRepository, never()).save(any());
    }

    @Test
    @DisplayName("Sem linha e sem papel pedido: 422 'Informe a linha do roster'")
    void approveWithoutRowOrRole() {
        squadExists("SQ", "Tribo A");
        User am = caller("am", "MEMBER", direct("SQ", "Tribo A", "AGILE_MASTER", true));
        pendingRequest("r1", "dev1", "SQ", null, "CONTRIBUTOR", null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.approveInSquad("SQ", "r1", null, am.getId(), "x"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        assertEquals("Informe a linha do roster", ex.getReason());
    }

    @Test
    @DisplayName("Aprovar contribuidor cuja linha igual já existe reaproveita a linha (idempotente, sem duplicar)")
    void approveReusesExistingEquivalentRow() {
        squadExists("SQ", "Tribo A");
        User am = caller("am", "MEMBER", direct("SQ", "Tribo A", "AGILE_MASTER", true));
        requesterExists("dev1");
        ProjectMemberRole existing = ProjectMemberRole.builder().id("ex").projectId("SQ").roleName("Developer").roleKey("DEVELOPER")
                .displayName("Dev").userId("dev1").isLeadership(false).source(MembershipSource.CLAIM).build();
        when(projectMemberRoleRepository.findByProjectId("SQ")).thenReturn(List.of(existing));
        LinkRequest req = pendingRequest("r1", "dev1", "SQ", null, "CONTRIBUTOR", "Developer");

        service.approveInSquad("SQ", "r1", null, am.getId(), "x");

        assertEquals("ex", req.getMemberId());
        assertEquals(MembershipSource.CLAIM, existing.getSource());
    }

    // ---------- recusar ----------

    @Test
    @DisplayName("Recusar pela squad: grava motivo, audita _BY só com ids e avisa o solicitante")
    void rejectBySquadLeadership() {
        squadExists("SQ", "Tribo A");
        User po = caller("po", "MEMBER", direct("SQ", "Tribo A", "PRODUCT_OWNER", true));
        requesterExists("dev1");
        LinkRequest req = pendingRequest("r1", "dev1", "SQ", null, "CONTRIBUTOR", "QA");

        AdminLinkRequestDto out = service.rejectInSquad("SQ", "r1", " fora do time ", po.getId(), "po@totvs.com.br");

        assertEquals("REJECTED", out.status());
        assertEquals("fora do time", req.getDecisionNote());
        AuditLog by = audit("LINK_REQUEST_REJECTED_BY");
        assertEquals("po", by.getPerformedBy());
        assertFalse(by.getDetails().contains("@"));
        verify(notificationTriggers).linkRequestDecided("r1", "dev1", false, "SQ", "fora do time", "po");
        verify(projectMemberRoleRepository, never()).save(any());
    }

    @Test
    @DisplayName("Aprovador sem poder não consegue nem listar fila de outra squad (403 antes de tocar no pedido)")
    void noPowerBeforeTouchingTheRequest() {
        squadExists("SQ", "Tribo A");
        User dev = caller("dev", "MEMBER", direct("SQ", "Tribo A", "DEVELOPER", false));
        assertStatus(HttpStatus.FORBIDDEN, () -> service.approveInSquad("SQ", "qualquer", null, dev.getId(), "x"));
        verify(linkRequestRepository, never()).findByIdForUpdate(anyString());
    }
}
