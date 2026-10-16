package com.agilespace.backend.service;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.domain.LinkRequest;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Pedido de vínculo aprovado por admin (docs/link-requests-api.md): quem lidera uma squad no Jira com e-mail diferente
 * do login (ou cuja linha do roster não é reivindicável pelo "sou eu") pede a um admin que ligue a conta à linha do
 * roster. A aprovação reaproveita a gravação do {@link OnboardingService#claim} sem a checagem de identidade (o admin a
 * confirma) e aceitando linha de liderança.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LinkRequestService {

    public static final String AUDIT_CREATED = "LINK_REQUEST_CREATED";
    public static final String AUDIT_APPROVED = "LINK_REQUEST_APPROVED";
    public static final String AUDIT_REJECTED = "LINK_REQUEST_REJECTED";

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
                .createdAt(ServerTime.now())
                .build();
        try {
            linkRequestRepository.saveAndFlush(request);
        } catch (DataIntegrityViolationException e) {
            // Corrida entre dois cliques: o índice único parcial (V74) barra o segundo pendente.
            throw new ResponseStatusException(HttpStatus.CONFLICT, DUPLICATE_PENDING);
        }

        audit(AUDIT_CREATED, requesterLabel(requester),
                "pedido=" + request.getId() + " projeto=" + projectKey + " linha=" + memberId + " emailNoJira=" + email);
        notificationTriggers.linkRequestCreated(request.getId(), requester.getId(), requester.getName(), projectKey);
        log.info("Pedido de vínculo {} criado por {} para {}", request.getId(), requester.getId(), projectKey);
        return new LinkRequestCreatedDto(request.getId(), request.getStatus());
    }

    @Transactional(readOnly = true)
    public List<LinkRequestDto> mine(String userId) {
        List<LinkRequest> rows = linkRequestRepository.findByRequesterUserIdOrderByCreatedAtDesc(userId);
        Map<String, String> names = memberNames(rows);
        return rows.stream().map(r -> new LinkRequestDto(r.getId(), r.getProjectKey(), r.getMemberId(),
                names.get(r.getMemberId()), r.getEmailInJira(), r.getMessage(), r.getStatus(),
                ServerTime.toInstant(r.getCreatedAt()), ServerTime.toInstant(r.getDecidedAt()), r.getDecisionNote())).toList();
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
        return toAdminDtos(rows);
    }

    /**
     * Aprova e vincula a conta do solicitante à linha do roster (transação única). {@code memberIdOverride} (corpo do
     * approve) sobrescreve o {@code memberId} do pedido; sem nenhum dos dois, 422.
     */
    @Transactional
    public AdminLinkRequestDto approve(String id, String memberIdOverride, String adminId, String adminLabel) {
        LinkRequest request = lockPending(id);
        String memberId = firstNonBlank(memberIdOverride, request.getMemberId());
        if (memberId == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Informe a linha do roster");
        }
        ProjectMemberRole row = projectMemberRoleRepository.findById(memberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pessoa não encontrada no roster"));
        if (!request.getProjectKey().equalsIgnoreCase(row.getProjectId())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Essa linha não pertence à squad do pedido.");
        }
        User requester = userRepository.findById(request.getRequesterUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conta do solicitante não encontrada"));

        onboardingService.linkApproved(row, requester);

        request.setMemberId(row.getId());
        decide(request, LinkRequest.APPROVED, adminLabel, null);
        linkRequestRepository.save(request);

        audit(AUDIT_APPROVED, adminLabel, "pedido=" + id + " usuario=" + requester.getId() + " projeto=" + request.getProjectKey()
                + " linha=" + row.getId() + " papel=" + row.getRoleName() + (row.isLeadership() ? " (liderança)" : ""));
        notificationTriggers.linkRequestDecided(id, requester.getId(), true, request.getProjectKey(), null, adminId);
        return toAdminDtos(List.of(request)).get(0);
    }

    @Transactional
    public AdminLinkRequestDto reject(String id, String reason, String adminId, String adminLabel) {
        LinkRequest request = lockPending(id);
        String note = blankToNull(reason);
        if (note != null && note.length() > MAX_MESSAGE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "O motivo pode ter no máximo " + MAX_MESSAGE + " caracteres.");
        }
        decide(request, LinkRequest.REJECTED, adminLabel, note);
        linkRequestRepository.save(request);

        audit(AUDIT_REJECTED, adminLabel, "pedido=" + id + " usuario=" + request.getRequesterUserId()
                + " projeto=" + request.getProjectKey() + (note == null ? "" : " motivo=" + note));
        notificationTriggers.linkRequestDecided(id, request.getRequesterUserId(), false, request.getProjectKey(), note, adminId);
        return toAdminDtos(List.of(request)).get(0);
    }

    // --- internos ---

    private LinkRequest lockPending(String id) {
        LinkRequest request = linkRequestRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido não encontrado"));
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

    private List<AdminLinkRequestDto> toAdminDtos(List<LinkRequest> rows) {
        Set<String> userIds = new HashSet<>();
        rows.forEach(r -> userIds.add(r.getRequesterUserId()));
        Map<String, User> users = new HashMap<>();
        if (!userIds.isEmpty()) {
            userRepository.findAllById(userIds).forEach(u -> users.put(u.getId(), u));
        }
        Map<String, String> names = memberNames(rows);
        return rows.stream().map(r -> {
            User u = users.get(r.getRequesterUserId());
            return new AdminLinkRequestDto(r.getId(),
                    new AdminLinkRequestDto.Requester(r.getRequesterUserId(), u != null ? u.getName() : null, u != null ? u.getEmail() : null),
                    r.getProjectKey(), r.getMemberId(), names.get(r.getMemberId()), r.getEmailInJira(), r.getMessage(),
                    r.getStatus(), ServerTime.toInstant(r.getCreatedAt()), ServerTime.toInstant(r.getDecidedAt()),
                    r.getDecidedBy(), r.getDecisionNote());
        }).toList();
    }

    /** memberId -> nome de exibição da linha do roster (linhas removidas pela reimportação ficam sem nome). */
    private Map<String, String> memberNames(List<LinkRequest> rows) {
        Set<String> ids = new HashSet<>();
        rows.forEach(r -> { if (r.getMemberId() != null) ids.add(r.getMemberId()); });
        Map<String, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            projectMemberRoleRepository.findAllById(ids).forEach(m -> names.put(m.getId(), m.getDisplayName()));
        }
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
