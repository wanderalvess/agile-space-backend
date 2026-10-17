package com.agilespace.backend.service;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.domain.LinkRequest;
import com.agilespace.backend.domain.MembershipSource;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.AdminLinkRequestDto;
import com.agilespace.backend.dto.LinkRequestCreateRequest;
import com.agilespace.backend.dto.LinkRequestCreatedDto;
import com.agilespace.backend.dto.LinkRequestDto;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.repository.LinkRequestRepository;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Pedido de vínculo (docs/link-requests-api.md e docs/link-requests-squad-api.md): quem lidera uma squad no Jira com
 * e-mail diferente do login (ou cuja linha do roster não é reivindicável pelo "sou eu", ou que nem aparece no roster)
 * pede a vinculação. Quem decide (Etapa 3): ADMIN, ou a liderança da squad conforme {@link LinkRequestApprovalPolicy}.
 * As rotas de admin e as rotas por squad passam pelos MESMOS métodos de decisão (um fluxo só). A aprovação reaproveita
 * a gravação do {@link OnboardingService#claim} sem a checagem de identidade (quem aprova a confirma) e, para
 * contribuidor sem linha no roster, cria a linha ({@code source = APPROVED}).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LinkRequestService {

    public static final String AUDIT_CREATED = "LINK_REQUEST_CREATED";
    public static final String AUDIT_APPROVED = "LINK_REQUEST_APPROVED";
    public static final String AUDIT_REJECTED = "LINK_REQUEST_REJECTED";
    /** Quem decidiu, só ids (sem e-mail/nome): ver docs/link-requests-squad-api.md. */
    public static final String AUDIT_APPROVED_BY = "LINK_REQUEST_APPROVED_BY";
    public static final String AUDIT_REJECTED_BY = "LINK_REQUEST_REJECTED_BY";

    static final int MAX_ROLE_NAME = 80;
    static final int DEFAULT_PAGE_SIZE = 100;
    static final int MAX_PAGE_SIZE = 200;

    static final int MAX_MESSAGE = 500;
    static final int MAX_EMAIL = 254;
    /** Teto de pedidos pendentes por conta (somando squads): barra quem tenta inundar a fila de admins. */
    static final int MAX_PENDING_PER_USER = 10;

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Set<String> STATUSES = Set.of(LinkRequest.PENDING, LinkRequest.APPROVED, LinkRequest.REJECTED);

    private static final String DUPLICATE_PENDING = "Você já tem um pedido pendente para esta squad";

    private final LinkRequestRepository linkRequestRepository;
    private final ProjectConfigRepository projectConfigRepository;
    private final ProjectMemberRoleRepository projectMemberRoleRepository;
    private final UserRepository userRepository;
    private final OnboardingService onboardingService;
    private final AuditLogRepository auditLogRepository;
    private final NotificationTriggers notificationTriggers;
    private final LinkRequestApprovalPolicy approvalPolicy;

    /** Quem decide: {@code viaSquad} = rota por squad (poder validado pela política); senão rota de admin (o filtro exige ADMIN). */
    private record Actor(String id, String label, User user, boolean viaSquad) { }

    // --- solicitante ---

    @Transactional
    public LinkRequestCreatedDto create(User requester, LinkRequestCreateRequest body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Corpo da requisição ausente.");
        }
        String projectKey = body.projectKey() == null ? "" : body.projectKey().trim().toUpperCase(Locale.ROOT);
        if (projectKey.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe a squad (projectKey).");
        }
        if (!projectConfigRepository.existsById(projectKey)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Projeto não encontrado");
        }
        String email = body.emailInJira() == null ? "" : body.emailInJira().trim();
        if (email.isEmpty() || email.length() > MAX_EMAIL || !EMAIL.matcher(email).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe um e-mail válido (o que você usa no Jira).");
        }
        String message = body.message() == null || body.message().isBlank() ? null : body.message().trim();
        if (message != null && message.length() > MAX_MESSAGE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A mensagem pode ter no máximo " + MAX_MESSAGE + " caracteres.");
        }

        String memberId = blankToNull(body.memberId());
        String kindRaw = blankToNull(body.kind()) == null ? null : body.kind().trim().toUpperCase(Locale.ROOT);
        if (kindRaw != null && !LinkRequest.KIND_CONTRIBUTOR.equals(kindRaw) && !LinkRequest.KIND_LEADERSHIP.equals(kindRaw)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo inválido: use CONTRIBUTOR ou LEADERSHIP.");
        }
        String requestedRole = blankToNull(body.requestedRoleName());
        if (requestedRole != null && requestedRole.length() > MAX_ROLE_NAME) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "O papel pode ter no máximo " + MAX_ROLE_NAME + " caracteres.");
        }
        String kind = kindRaw == null ? LinkRequest.KIND_CONTRIBUTOR : kindRaw;
        if (memberId != null) {
            ProjectMemberRole row = projectMemberRoleRepository.findById(memberId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pessoa não encontrada no roster"));
            if (!projectKey.equalsIgnoreCase(row.getProjectId())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Essa pessoa não pertence a essa squad.");
            }
            if (row.getUserId() != null && !row.getUserId().isBlank()) {
                if (row.getUserId().equals(requester.getId())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Você já está vinculado(a) a essa pessoa nesta squad.");
                }
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Essa pessoa já está vinculada a outra conta. Se for um engano, peça pra quem administra o projeto desfazer o vínculo.");
            }
            // O tipo vem da linha (quem pode aprovar depende dela); o cliente não rebaixa uma linha de liderança.
            String derived = row.isLeadership() ? LinkRequest.KIND_LEADERSHIP : LinkRequest.KIND_CONTRIBUTOR;
            if (kindRaw != null && !kindRaw.equals(derived)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        row.isLeadership() ? "Essa linha é de liderança: o pedido é do tipo LEADERSHIP."
                                : "Essa linha não é de liderança: o pedido é do tipo CONTRIBUTOR.");
            }
            if (requestedRole != null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Informe a linha do roster OU o papel pedido, não os dois.");
            }
            kind = derived;
        } else if (requestedRole != null) {
            // Contribuidor que não aparece no roster: só papel da allowlist de auto-atendimento (nunca liderança).
            if (LinkRequest.KIND_LEADERSHIP.equals(kindRaw)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Pedido de liderança aponta a linha de liderança do roster; não aceita papel pedido.");
            }
            if (!JiraProfieldsService.isSelfServiceRoleName(requestedRole)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Papel de liderança ou desconhecido não pode ser pedido como contribuidor. "
                                + "Para liderança, aponte a sua linha de liderança no roster.");
            }
            String roleKey = JiraProfieldsService.roleKeyOf(requestedRole);
            boolean alreadyHasRole = projectMemberRoleRepository.findByProjectId(projectKey).stream()
                    .anyMatch(r -> requester.getId().equals(r.getUserId()) && roleKey.equalsIgnoreCase(r.getRoleKey()));
            if (alreadyHasRole) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Você já está vinculado(a) a essa squad com esse papel.");
            }
            kind = LinkRequest.KIND_CONTRIBUTOR;
        }

        if (linkRequestRepository.existsByRequesterUserIdAndProjectKeyAndStatus(requester.getId(), projectKey, LinkRequest.PENDING)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, DUPLICATE_PENDING);
        }
        if (linkRequestRepository.countByRequesterUserIdAndStatus(requester.getId(), LinkRequest.PENDING) >= MAX_PENDING_PER_USER) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Você tem pedidos pendentes demais. Aguarde a análise dos anteriores.");
        }

        LinkRequest request = LinkRequest.builder()
                .id(UUID.randomUUID().toString())
                .requesterUserId(requester.getId())
                .projectKey(projectKey)
                .memberId(memberId)
                .emailInJira(email)
                .message(message)
                .status(LinkRequest.PENDING)
                .kind(kind)
                .requestedRoleName(requestedRole)
                .createdAt(ServerTime.now())
                .build();
        try {
            linkRequestRepository.saveAndFlush(request);
        } catch (DataIntegrityViolationException e) {
            // Corrida entre dois cliques: o índice único parcial (V74) barra o segundo pendente.
            throw new ResponseStatusException(HttpStatus.CONFLICT, DUPLICATE_PENDING);
        }

        audit(AUDIT_CREATED, requesterLabel(requester),
                "pedido=" + request.getId() + " projeto=" + projectKey + " linha=" + memberId + " emailNoJira=" + email
                        + " tipo=" + kind + (requestedRole == null ? "" : " papelPedido=" + requestedRole));
        notificationTriggers.linkRequestCreated(request.getId(), requester.getId(), requester.getName(), projectKey,
                approversToNotify(projectKey, kind, requester.getId()));
        log.info("Pedido de vínculo {} criado por {} para {}", request.getId(), requester.getId(), projectKey);
        return new LinkRequestCreatedDto(request.getId(), request.getStatus());
    }

    @Transactional(readOnly = true)
    public List<LinkRequestDto> mine(String userId) {
        List<LinkRequest> rows = linkRequestRepository.findByRequesterUserIdOrderByCreatedAtDesc(userId);
        Map<String, String> names = memberNames(rows);
        return rows.stream().map(r -> new LinkRequestDto(r.getId(), r.getProjectKey(), r.getMemberId(),
                names.get(r.getMemberId()), r.getEmailInJira(), r.getMessage(), r.getStatus(),
                ServerTime.toInstant(r.getCreatedAt()), ServerTime.toInstant(r.getDecidedAt()), r.getDecisionNote(),
                r.effectiveKind(), r.getRequestedRoleName())).toList();
    }

    // --- admin ---

    /** Mais recentes primeiro; {@code status} null/vazio = todos. */
    @Transactional(readOnly = true)
    public List<AdminLinkRequestDto> adminList(String status) {
        List<LinkRequest> rows;
        if (status == null || status.isBlank()) {
            rows = linkRequestRepository.findAllByOrderByCreatedAtDesc();
        } else {
            String normalized = status.trim().toUpperCase(Locale.ROOT);
            if (!STATUSES.contains(normalized)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status inválido: use PENDING, APPROVED ou REJECTED.");
            }
            rows = linkRequestRepository.findByStatusOrderByCreatedAtDesc(normalized);
        }
        return toAdminDtos(rows, null);
    }

    /**
     * Aprova pela rota de ADMIN (o filtro de {@code /api/admin/**} já exige role=ADMIN) e vincula a conta do solicitante
     * (transação única). {@code memberIdOverride} (corpo do approve) sobrescreve o {@code memberId} do pedido; sem
     * nenhum dos dois e sem papel pedido, 422.
     */
    @Transactional
    public AdminLinkRequestDto approve(String id, String memberIdOverride, String adminId, String adminLabel) {
        return approveInternal(id, memberIdOverride, new Actor(adminId, adminLabel, null, false), null);
    }

    @Transactional
    public AdminLinkRequestDto reject(String id, String reason, String adminId, String adminLabel) {
        return rejectInternal(id, reason, new Actor(adminId, adminLabel, null, false), null);
    }

    // --- por squad (liderança da squad) ---

    /** {@code GET /api/squads/{id}/link-requests/can-approve}: só os poderes do próprio chamador. */
    @Transactional(readOnly = true)
    public LinkRequestApprovalPolicy.Eligibility canApprove(String callerId, String squadKey) {
        requireSquadRoutes();
        User caller = loadCaller(callerId);
        String squad = resolveSquad(squadKey);
        return approvalPolicy.eligibility(caller, squad);
    }

    /** Pedidos da squad ({@code status} PENDING padrão | APPROVED | REJECTED | ALL), mais recentes primeiro. */
    @Transactional(readOnly = true)
    public List<AdminLinkRequestDto> squadList(String callerId, String squadKey, String status, Integer page, Integer size) {
        requireSquadRoutes();
        User caller = loadCaller(callerId);
        String squad = resolveSquad(squadKey);
        LinkRequestApprovalPolicy.Eligibility eligibility = approvalPolicy.eligibility(caller, squad);
        if (!eligibility.canApprove()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, NOT_APPROVER);
        }
        String normalized = status == null || status.isBlank() ? LinkRequest.PENDING : status.trim().toUpperCase(Locale.ROOT);
        if (!"ALL".equals(normalized) && !STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status inválido: use PENDING, APPROVED, REJECTED ou ALL.");
        }
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        int pageIndex = page == null ? 0 : page;
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE || pageIndex < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Paginação inválida: size de 1 a " + MAX_PAGE_SIZE + " e page a partir de 0.");
        }
        PageRequest pageable = PageRequest.of(pageIndex, pageSize);
        Set<String> keys = squadKeyVariants(squad);
        List<LinkRequest> rows = "ALL".equals(normalized)
                ? linkRequestRepository.findByProjectKeyInOrderByCreatedAtDesc(keys, pageable)
                : linkRequestRepository.findByProjectKeyInAndStatusOrderByCreatedAtDesc(keys, normalized, pageable);
        return toAdminDtos(rows, eligibility);
    }

    @Transactional
    public AdminLinkRequestDto approveInSquad(String squadKey, String id, String memberIdOverride, String callerId, String callerLabel) {
        requireSquadRoutes();
        User caller = loadCaller(callerId);
        String squad = resolveSquad(squadKey);
        requireSquadApprover(caller, squad);
        return approveInternal(id, memberIdOverride, new Actor(callerId, callerLabel, caller, true), squad);
    }

    @Transactional
    public AdminLinkRequestDto rejectInSquad(String squadKey, String id, String reason, String callerId, String callerLabel) {
        requireSquadRoutes();
        User caller = loadCaller(callerId);
        String squad = resolveSquad(squadKey);
        requireSquadApprover(caller, squad);
        return rejectInternal(id, reason, new Actor(callerId, callerLabel, caller, true), squad);
    }

    // --- decisão (um fluxo só) ---

    private AdminLinkRequestDto approveInternal(String id, String memberIdOverride, Actor actor, String squadScope) {
        LinkRequest request = lockPending(id, squadScope);
        requireNotSelf(request, actor);

        // Tipo EFETIVO: o do pedido OU o da linha que vai ser ligada (quem aprova não "promove" um contribuidor
        // apontando uma linha de liderança). Calculado antes de qualquer outra validação, para o 403 vir primeiro.
        String memberId = firstNonBlank(memberIdOverride, request.getMemberId());
        ProjectMemberRole row = memberId == null ? null : projectMemberRoleRepository.findById(memberId).orElse(null);
        String kind = effectiveKind(request, row);
        requireMayDecide(actor, request, kind);

        if (memberId == null && blankToNull(request.getRequestedRoleName()) == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Informe a linha do roster");
        }
        if (memberId != null && row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pessoa não encontrada no roster");
        }
        if (row != null && !request.getProjectKey().equalsIgnoreCase(row.getProjectId())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Essa linha não pertence à squad do pedido.");
        }
        User requester = userRepository.findById(request.getRequesterUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conta do solicitante não encontrada"));

        if (row == null) {
            // Contribuidor que não aparece no roster: cria a linha (APPROVED, não-liderança, papel da allowlist).
            row = createContributorRow(request, requester);
        }
        onboardingService.linkApproved(row, requester);
        fillLegacySquad(requester, request.getProjectKey());

        request.setMemberId(row.getId());
        decide(request, LinkRequest.APPROVED, actor.label(), null);
        linkRequestRepository.save(request);

        audit(AUDIT_APPROVED, actor.label(), "pedido=" + id + " usuario=" + requester.getId() + " projeto=" + request.getProjectKey()
                + " linha=" + row.getId() + " papel=" + row.getRoleName() + (row.isLeadership() ? " (liderança)" : ""));
        audit(AUDIT_APPROVED_BY, actor.id(), decisionDetails(request, kind, actor, row.getId()));
        notificationTriggers.linkRequestDecided(id, requester.getId(), true, request.getProjectKey(), null, actor.id());
        return toAdminDtos(List.of(request), null).get(0);
    }

    private AdminLinkRequestDto rejectInternal(String id, String reason, Actor actor, String squadScope) {
        LinkRequest request = lockPending(id, squadScope);
        requireNotSelf(request, actor);

        ProjectMemberRole row = blankToNull(request.getMemberId()) == null ? null
                : projectMemberRoleRepository.findById(request.getMemberId()).orElse(null);
        String kind = effectiveKind(request, row);
        requireMayDecide(actor, request, kind);

        String note = blankToNull(reason);
        if (note != null && note.length() > MAX_MESSAGE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "O motivo pode ter no máximo " + MAX_MESSAGE + " caracteres.");
        }
        decide(request, LinkRequest.REJECTED, actor.label(), note);
        linkRequestRepository.save(request);

        audit(AUDIT_REJECTED, actor.label(), "pedido=" + id + " usuario=" + request.getRequesterUserId()
                + " projeto=" + request.getProjectKey() + (note == null ? "" : " motivo=" + note));
        audit(AUDIT_REJECTED_BY, actor.id(), decisionDetails(request, kind, actor, null));
        notificationTriggers.linkRequestDecided(id, request.getRequesterUserId(), false, request.getProjectKey(), note, actor.id());
        return toAdminDtos(List.of(request), null).get(0);
    }

    /** Quem pediu nunca decide o próprio pedido (nem o ADMIN): evita autoaprovação, também com outro vínculo. */
    private void requireNotSelf(LinkRequest request, Actor actor) {
        if (actor.id() != null && actor.id().equals(request.getRequesterUserId())) {
            log.warn("Pedido de vínculo {}: autodecisão negada (conta {})", request.getId(), actor.id());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Você não pode decidir o próprio pedido de vínculo.");
        }
    }

    private void requireMayDecide(Actor actor, LinkRequest request, String kind) {
        if (!actor.viaSquad()) return; // rota de admin: o filtro já exigiu role=ADMIN
        if (!approvalPolicy.canDecide(actor.user(), request.getProjectKey(), kind)) {
            log.warn("Pedido de vínculo {}: decisão negada (conta {}, squad {}, tipo {})",
                    request.getId(), actor.id(), request.getProjectKey(), kind);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, LinkRequest.KIND_LEADERSHIP.equals(kind)
                    ? "Pedido de liderança: só People Lead/Tribe Lead (da squad ou da tribo) ou um administrador decide."
                    : NOT_APPROVER);
        }
    }

    private void requireSquadApprover(User caller, String squad) {
        if (!approvalPolicy.eligibility(caller, squad).canApprove()) {
            log.warn("Pedidos de vínculo da squad {}: acesso negado (conta {})", squad, caller.getId());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, NOT_APPROVER);
        }
    }

    private static String effectiveKind(LinkRequest request, ProjectMemberRole row) {
        boolean leadership = LinkRequest.KIND_LEADERSHIP.equals(request.effectiveKind()) || (row != null && row.isLeadership());
        return leadership ? LinkRequest.KIND_LEADERSHIP : LinkRequest.KIND_CONTRIBUTOR;
    }

    private static String decisionDetails(LinkRequest request, String kind, Actor actor, String rowId) {
        return "pedido=" + request.getId() + " squad=" + request.getProjectKey() + " solicitante=" + request.getRequesterUserId()
                + " tipo=" + kind + " via=" + (actor.viaSquad() ? "SQUAD" : "ADMIN") + (rowId == null ? "" : " linha=" + rowId);
    }

    /**
     * Linha de contribuidor para quem não aparece no roster. Só papel da allowlist de auto-atendimento (revalidada aqui,
     * mesmo já validada na criação), nunca liderança; reaproveita linha idêntica já ligada à conta (idempotente).
     */
    private ProjectMemberRole createContributorRow(LinkRequest request, User requester) {
        String roleName = blankToNull(request.getRequestedRoleName());
        if (roleName == null || !JiraProfieldsService.isSelfServiceRoleName(roleName)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Informe a linha do roster");
        }
        String roleKey = JiraProfieldsService.roleKeyOf(roleName);
        List<ProjectMemberRole> inSquad = projectMemberRoleRepository.findByProjectId(request.getProjectKey());
        if (inSquad != null) {
            for (ProjectMemberRole existing : inSquad) {
                if (requester.getId().equals(existing.getUserId()) && roleKey.equalsIgnoreCase(existing.getRoleKey())
                        && !existing.isLeadership()) {
                    return existing;
                }
            }
        }
        ProjectMemberRole created = ProjectMemberRole.builder()
                .projectId(request.getProjectKey())
                .roleName(roleName)
                .roleKey(roleKey)
                .jiraAccountId(requester.getJiraAccountId())
                .displayName(requester.getName() == null || requester.getName().isBlank() ? requester.getEmail() : requester.getName())
                .email(requester.getEmail())
                .userId(requester.getId())
                .isLeadership(false)
                .source(MembershipSource.APPROVED)
                .build();
        projectMemberRoleRepository.save(created);
        return created;
    }

    /**
     * O vínculo (pmr) é a fonte da verdade; {@code users.default_project_id} é só a preferência de squad ativa
     * ({@code users.squad_id} foi aposentada na Etapa 5). Só preenche se estiver vazio ("Sem Time" conta como vazio):
     * nunca troca a squad ativa de quem já tem uma. Entidade gerenciada na transação: o flush grava.
     */
    private void fillLegacySquad(User requester, String projectKey) {
        if (isEmptySquad(requester.getDefaultProjectId())) requester.setDefaultProjectId(projectKey);
    }

    private static boolean isEmptySquad(String value) {
        return value == null || value.isBlank() || "Sem Time".equalsIgnoreCase(value.trim()) || "Sem Squad".equalsIgnoreCase(value.trim());
    }

    // --- squad: chave, flag e chamador ---

    private static final String NOT_APPROVER =
            "Só a liderança desta squad (Agile Master, Product Owner, People Lead) ou um administrador decide pedidos de vínculo.";

    private void requireSquadRoutes() {
        if (!approvalPolicy.squadApprovalEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recurso não encontrado");
        }
    }

    private User loadCaller(String callerId) {
        if (callerId == null || callerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida ou expirada");
        }
        return userRepository.findById(callerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida ou expirada"));
    }

    /** Chave canônica da squad (sem caixa; MISSI e DDWMISSI são a mesma); 404 se não existe. */
    String resolveSquad(String raw) {
        String key = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (key.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe a squad.");
        }
        for (String variant : squadKeyVariants(key)) {
            if (projectConfigRepository.existsById(variant)) return variant;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Projeto não encontrado");
    }

    /** A chave em maiúsculas e, para o alias histórico, as duas formas (DDWMISSI e MISSI). */
    static Set<String> squadKeyVariants(String squadKey) {
        String key = squadKey == null ? "" : squadKey.trim().toUpperCase(Locale.ROOT);
        Set<String> out = new LinkedHashSet<>();
        out.add(key);
        if (MembershipService.isMissiAlias(key)) {
            out.add("DDWMISSI");
            out.add("MISSI");
        }
        return out;
    }

    // --- notificações ---

    /** Aprovadores a avisar (sem o próprio solicitante). Falha aqui vira "sem aprovador" (cai em ADMIN), nunca quebra o pedido. */
    private Set<String> approversToNotify(String projectKey, String kind, String requesterId) {
        try {
            Set<String> ids = new HashSet<>(approvalPolicy.approverUserIds(projectKey, kind));
            ids.remove(requesterId);
            return ids;
        } catch (RuntimeException e) {
            log.warn("Falha ao resolver aprovadores do pedido de vínculo na squad {}; avisando só ADMINs", projectKey, e);
            return Set.of();
        }
    }

    // --- internos ---

    /** Trava o pedido. Com {@code squadScope}, um pedido de OUTRA squad é 404 (como se não existisse), antes de qualquer outro erro. */
    private LinkRequest lockPending(String id, String squadScope) {
        LinkRequest request = linkRequestRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido não encontrado"));
        if (squadScope != null && !MembershipService.sameSquad(request.getProjectKey(), squadScope)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido não encontrado");
        }
        if (!LinkRequest.PENDING.equals(request.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esse pedido já foi decidido.");
        }
        return request;
    }

    private static void decide(LinkRequest request, String status, String decidedBy, String note) {
        request.setStatus(status);
        request.setDecidedAt(ServerTime.now());
        request.setDecidedBy(decidedBy);
        request.setDecisionNote(note);
    }

    private void audit(String action, String performedBy, String details) {
        auditLogRepository.save(AuditLog.builder()
                .id(UUID.randomUUID().toString())
                .action(action)
                .performedBy(performedBy == null || performedBy.isBlank() ? "ADMIN" : performedBy)
                .details(details)
                .createdAt(LocalDateTime.now())
                .build());
    }

    /** {@code eligibility} não nulo (lista por squad) preenche {@code canDecide}; nulo (admin) deixa o campo vazio. */
    private List<AdminLinkRequestDto> toAdminDtos(List<LinkRequest> rows, LinkRequestApprovalPolicy.Eligibility eligibility) {
        Set<String> userIds = new HashSet<>();
        rows.forEach(r -> userIds.add(r.getRequesterUserId()));
        Map<String, User> users = new HashMap<>();
        if (!userIds.isEmpty()) {
            userRepository.findAllById(userIds).forEach(u -> users.put(u.getId(), u));
        }
        Map<String, ProjectMemberRole> members = memberRows(rows);
        return rows.stream().map(r -> {
            User u = users.get(r.getRequesterUserId());
            ProjectMemberRole member = r.getMemberId() == null ? null : members.get(r.getMemberId());
            String kind = effectiveKind(r, member);
            Boolean canDecide = null;
            if (eligibility != null) {
                canDecide = LinkRequest.PENDING.equals(r.getStatus())
                        && (LinkRequest.KIND_LEADERSHIP.equals(kind) ? eligibility.canApproveLeadership() : eligibility.canApprove());
            }
            return new AdminLinkRequestDto(r.getId(),
                    new AdminLinkRequestDto.Requester(r.getRequesterUserId(), u != null ? u.getName() : null, u != null ? u.getEmail() : null),
                    r.getProjectKey(), r.getMemberId(), member == null ? null : member.getDisplayName(), r.getEmailInJira(), r.getMessage(),
                    r.getStatus(), ServerTime.toInstant(r.getCreatedAt()), ServerTime.toInstant(r.getDecidedAt()),
                    r.getDecidedBy(), r.getDecisionNote(), kind, r.getRequestedRoleName(), canDecide);
        }).toList();
    }

    /** memberId -> linha do roster (linhas removidas pela reimportação ficam de fora, sem nome). */
    private Map<String, ProjectMemberRole> memberRows(List<LinkRequest> rows) {
        Set<String> ids = new HashSet<>();
        rows.forEach(r -> { if (r.getMemberId() != null) ids.add(r.getMemberId()); });
        Map<String, ProjectMemberRole> byId = new HashMap<>();
        if (!ids.isEmpty()) {
            projectMemberRoleRepository.findAllById(ids).forEach(m -> byId.put(m.getId(), m));
        }
        return byId;
    }

    /** memberId -> nome de exibição da linha do roster. */
    private Map<String, String> memberNames(List<LinkRequest> rows) {
        Map<String, String> names = new HashMap<>();
        memberRows(rows).forEach((id, m) -> names.put(id, m.getDisplayName()));
        return names;
    }

    private static String requesterLabel(User user) {
        return user.getEmail() != null && !user.getEmail().isBlank() ? user.getEmail() : user.getId();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String a, String b) {
        String first = blankToNull(a);
        return first != null ? first : blankToNull(b);
    }
}
