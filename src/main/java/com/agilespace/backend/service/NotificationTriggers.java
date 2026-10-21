package com.agilespace.backend.service;

import com.agilespace.backend.domain.Notification;
import com.agilespace.backend.domain.RetroParticipant;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.RetroParticipantRepository;
import com.agilespace.backend.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Gatilhos de notificação chamados pelos fluxos existentes (convite, poker assíncrono, ação de retro, tarefa de
 * plano de ação, aviso). Regras comuns:
 * <ul>
 *   <li>rodam DEPOIS do commit da operação principal e numa transação própria: falha aqui só vira log, nunca
 *       desfaz nem quebra o convite/sala/card que a originou;</li>
 *   <li>o autor da ação nunca é notificado (NotificationService.notifyUsers);</li>
 *   <li>os argumentos são valores simples capturados antes do commit (nada de entidade gerenciada).</li>
 * </ul>
 */
@Component
@Slf4j
public class NotificationTriggers {

    private final NotificationService notificationService;
    private final SquadAudienceService audienceService;
    private final UserRepository userRepository;
    private final RetroParticipantRepository retroParticipantRepository;
    private final TransactionTemplate requiresNew;

    @Autowired
    public NotificationTriggers(NotificationService notificationService, SquadAudienceService audienceService,
                                UserRepository userRepository, RetroParticipantRepository retroParticipantRepository,
                                @Autowired(required = false) PlatformTransactionManager transactionManager) {
        this.notificationService = notificationService;
        this.audienceService = audienceService;
        this.userRepository = userRepository;
        this.retroParticipantRepository = retroParticipantRepository;
        if (transactionManager != null) {
            this.requiresNew = new TransactionTemplate(transactionManager);
            this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        } else {
            this.requiresNew = null;
        }
    }

    // --- INVITE ---

    /** Convite com e-mail de uma conta que já existe: avisa a pessoa (link do convite). */
    public void inviteCreated(String inviteToken, String squadId, String squadName, String inviteeEmail, String invitedByEmail) {
        if (inviteeEmail == null || inviteeEmail.isBlank() || inviteToken == null) return;
        fire("INVITE", () -> {
            String email = inviteeEmail.trim().toLowerCase(Locale.ROOT);
            List<User> invitees = userRepository.findActiveByEmails(Set.of(email));
            if (invitees.isEmpty()) return;
            String actorId = null;
            if (invitedByEmail != null && !invitedByEmail.isBlank()) {
                actorId = userRepository.findActiveByEmails(Set.of(invitedByEmail.trim().toLowerCase(Locale.ROOT)))
                        .stream().map(User::getId).findFirst().orElse(null);
            }
            String squadLabel = squadName != null && !squadName.isBlank() ? squadName
                    : audienceService.squadNames(List.of(squadId)).getOrDefault(squadId, squadId);
            notificationService.notifyUsers(invitees.stream().map(User::getId).toList(),
                    new NotificationService.Draft(Notification.INVITE, actorId == null ? "Convite" : null,
                            "convidou você para a squad “" + squadLabel + "”.",
                            "/invite/" + inviteToken, actorId, squadId, "INVITE:" + inviteToken),
                    UserSignalService.NOTIFICATION);
        });
    }

    // --- REMINDER ---

    /** Poker assíncrono aberto: cada membro da squad (menos quem abriu) recebe "falta seu voto", uma vez por sala. */
    public void asyncPokerOpened(String roomId, String roomTitle, String squadId, String creatorId) {
        if (roomId == null || squadId == null || squadId.isBlank()) return;
        fire("REMINDER", () -> {
            Set<String> members = audienceService.memberUserIdsOf(List.of(squadId));
            String title = roomTitle == null || roomTitle.isBlank() ? "Poker assíncrono" : roomTitle;
            notificationService.notifyUsers(members,
                    new NotificationService.Draft(Notification.REMINDER, title,
                            "Falta seu voto no poker assíncrono.", "/room/" + roomId, creatorId, squadId,
                            "POKER_ASYNC:" + roomId),
                    UserSignalService.NOTIFICATION);
        });
    }

    // --- ACTION_ASSIGNED ---

    /**
     * Ação de retro atribuída/alterada. RetroCard.assignee é texto livre digitado (nome), não userId: resolve para uma
     * conta entre os participantes do quadro e os membros da squad; ambíguo ou desconhecido = não notifica.
     */
    public void retroActionAssigned(String boardId, String boardTitle, String squadId, String cardId, String cardContent,
                                    String assignee, String actorId) {
        if (assignee == null || assignee.isBlank() || boardId == null) return;
        fire("ACTION_ASSIGNED", () -> {
            List<Candidate> candidates = new ArrayList<>();
            for (RetroParticipant p : retroParticipantRepository.findByBoardId(boardId)) {
                candidates.add(new Candidate(p.getId(), p.getNickname(), null));
            }
            String userId = resolveAssignee(assignee, candidates, squadId);
            if (userId == null) {
                log.debug("Responsável da ação não resolvido para uma conta (board {})", boardId);
                return;
            }
            notificationService.notifyUsers(List.of(userId),
                    new NotificationService.Draft(Notification.ACTION_ASSIGNED,
                            boardTitle == null || boardTitle.isBlank() ? "Retrospectiva" : boardTitle,
                            "atribuiu a você a ação “" + NotificationService.truncate(oneLine(cardContent), 120) + "”.",
                            "/retro/" + boardId, actorId, squadId, "RETRO_ACTION:" + cardId + ":" + userId),
                    UserSignalService.NOTIFICATION);
        });
    }

    /** Tarefa de plano de ação com responsável ("quem", texto livre) resolvido entre os membros da squad do plano. */
    public void actionPlanTaskAssigned(String planId, String planTitle, String squadId, String taskId, String what,
                                       String who, String actorId) {
        if (who == null || who.isBlank() || squadId == null || squadId.isBlank()) return;
        fire("ACTION_ASSIGNED", () -> {
            String userId = resolveAssignee(who, List.of(), squadId);
            if (userId == null) return;
            notificationService.notifyUsers(List.of(userId),
                    new NotificationService.Draft(Notification.ACTION_ASSIGNED,
                            planTitle == null || planTitle.isBlank() ? "Plano de ação" : planTitle,
                            "atribuiu a você a ação “" + NotificationService.truncate(oneLine(what), 120) + "”.",
                            "/action-plan/" + planId, actorId, squadId, "PLAN_TASK:" + taskId + ":" + userId),
                    UserSignalService.NOTIFICATION);
        });
    }

    // --- ANNOUNCEMENT ---

    /**
     * Aviso vigente: notifica o público uma vez (chave por aviso; reedições não repetem) e manda o sinal ANNOUNCEMENT.
     * Aviso agendado (validFrom no futuro) ou já expirado não gera notificação.
     */
    public void announcementPublished(String announcementId, String title, String audienceType, Collection<String> squadIds,
                                      Collection<String> roles, LocalDateTime validFrom, LocalDateTime validUntil,
                                      String link, String actorId) {
        if (announcementId == null) return;
        LocalDateTime now = LocalDateTime.now();
        if ((validFrom != null && validFrom.isAfter(now)) || (validUntil != null && !validUntil.isAfter(now))) return;
        List<String> squads = squadIds == null ? List.of() : List.copyOf(squadIds);
        List<String> roleList = roles == null ? List.of() : List.copyOf(roles);
        fire("ANNOUNCEMENT", () -> {
            Set<String> recipients = switch (audienceType == null ? "ALL" : audienceType) {
                case "SQUADS" -> audienceService.memberUserIdsOf(squads);
                case "ROLES" -> audienceService.userIdsWithRoles(roleList);
                default -> audienceService.allActiveUserIds();
            };
            notificationService.notifyUsers(recipients,
                    new NotificationService.Draft(Notification.ANNOUNCEMENT, title, "publicou um aviso.",
                            link, actorId, squads.size() == 1 ? squads.get(0) : null, "ANNOUNCEMENT:" + announcementId),
                    UserSignalService.ANNOUNCEMENT);
        });
    }

    // --- LINK_REQUEST ---

    /**
     * Pedido de vínculo novo: avisa os APROVADORES da squad ({@code approverIds}, já sem o solicitante; link para a aba
     * "Pedidos" da squad). Só quando não há nenhum aprovador com conta (ou a aprovação por squad está desligada) cai nos
     * ADMINs ativos, com o link da fila de admin. O solicitante nunca é notificado.
     */
    public void linkRequestCreated(String requestId, String requesterId, String requesterName, String projectKey,
                                   Collection<String> approverIds) {
        if (requestId == null) return;
        List<String> approvers = approverIds == null ? List.of()
                : approverIds.stream().filter(id -> id != null && !id.isBlank() && !id.equals(requesterId)).toList();
        fire("LINK_REQUEST_CREATED", () -> {
            String who = requesterName == null || requesterName.isBlank() ? "Alguém" : requesterName.trim();
            String text = who + " pediu para ser vinculado(a) à squad " + projectKey + ".";
            boolean toApprovers = !approvers.isEmpty();
            Collection<String> recipients = toApprovers ? approvers : userRepository.findActiveIdsByRoles(Set.of("ADMIN"));
            String link = toApprovers ? "/squad/roster?tab=pedidos&squadId=" + projectKey : "/admin?tab=aprovacoes";
            notificationService.notifyUsers(recipients,
                    new NotificationService.Draft(Notification.LINK_REQUEST, "Pedido de vínculo", text, link, requesterId, null,
                            "LINK_REQUEST_CREATED:" + requestId),
                    UserSignalService.NOTIFICATION);
        });
    }

    /**
     * Pedido decidido: avisa quem pediu. Aprovado leva ao /painel; recusado leva ao /onboarding (outra tentativa), com
     * o motivo quando houver.
     */
    public void linkRequestDecided(String requestId, String requesterId, boolean approved, String projectKey,
                                   String decisionNote, String actorId) {
        if (requestId == null || requesterId == null) return;
        fire("LINK_REQUEST_DECIDED", () -> {
            String text = approved
                    ? "Seu pedido de vínculo à squad " + projectKey + " foi aprovado."
                    : "Seu pedido de vínculo à squad " + projectKey + " foi recusado."
                    + (decisionNote == null || decisionNote.isBlank() ? "" : " Motivo: " + oneLine(decisionNote));
            notificationService.notifyUsers(List.of(requesterId),
                    new NotificationService.Draft(Notification.LINK_REQUEST, "Pedido de vínculo", text,
                            approved ? "/painel" : "/onboarding", actorId, null, "LINK_REQUEST_DECIDED:" + requestId),
                    UserSignalService.NOTIFICATION);
        });
    }

    /** Pedido cancelado pelo solicitante: retira (marca como lido) o aviso de pedido novo que os aprovadores tinham. */
    public void linkRequestCancelled(String requestId) {
        if (requestId == null) return;
        fire("LINK_REQUEST_CANCELLED", () -> notificationService.retireByDedupeKey("LINK_REQUEST_CREATED:" + requestId));
    }

    // --- SUPPORT_REPLY ---

    /** Link do chamado na central de suporte (o mesmo da notificação e da leitura). */
    public static String supportTicketLink(UUID ticketId) {
        return "/support?ticket=" + ticketId;
    }

    /**
     * O time respondeu um chamado: avisa o solicitante ("Seu chamado SUP-NNNN recebeu resposta"). Uma notificação por
     * resposta (chave = id da resposta). O autor nunca é avisado de resposta própria.
     */
    public void supportTicketReplied(UUID ticketId, String protocol, String subject, String requesterId, String replyId,
                                     String replierId) {
        if (ticketId == null || requesterId == null || requesterId.isBlank() || requesterId.equals(replierId)) return;
        fire("SUPPORT_REPLY", () -> notificationService.notifyUsers(List.of(requesterId),
                new NotificationService.Draft(Notification.SUPPORT_REPLY,
                        "Seu chamado " + protocol + " recebeu resposta",
                        "O time respondeu: " + NotificationService.truncate(oneLine(subject), 200),
                        supportTicketLink(ticketId), null, null, "SUPPORT_REPLY:" + replyId),
                UserSignalService.NOTIFICATION));
    }

    /** O solicitante abriu o chamado: os avisos de resposta dele deixam de contar como não lidos. */
    public void supportTicketRead(UUID ticketId, String requesterId) {
        if (ticketId == null || requesterId == null) return;
        fire("SUPPORT_READ", () -> notificationService.markReadByLink(requesterId, Notification.SUPPORT_REPLY,
                supportTicketLink(ticketId)));
    }

    // --- internos ---

    record Candidate(String userId, String name, String email) { }

    /**
     * Texto livre → conta: 1) é o próprio userId de um candidato/membro; 2) participante do quadro com esse apelido;
     * 3) membro da squad com esse nome, e-mail ou usuário (parte antes do @). Só vale match único.
     */
    String resolveAssignee(String assignee, List<Candidate> participants, String squadId) {
        String key = assignee.trim();
        String keyLower = key.toLowerCase(Locale.ROOT);

        Set<String> byParticipant = new LinkedHashSet<>();
        for (Candidate c : participants) {
            if (c.userId() == null) continue;
            if (c.userId().equals(key)) return c.userId();
            if (c.name() != null && c.name().trim().equalsIgnoreCase(key)) byParticipant.add(c.userId());
        }
        if (byParticipant.size() == 1) return byParticipant.iterator().next();
        if (byParticipant.size() > 1) return null;

        if (squadId == null || squadId.isBlank()) return null;
        Set<String> memberIds = audienceService.memberUserIdsOf(List.of(squadId));
        if (memberIds.contains(key)) return key;
        if (memberIds.isEmpty()) return null;
        Set<String> matches = new LinkedHashSet<>();
        for (User u : userRepository.findActiveByIds(memberIds)) {
            String email = u.getEmail() == null ? "" : u.getEmail().trim().toLowerCase(Locale.ROOT);
            String local = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
            if ((u.getName() != null && u.getName().trim().equalsIgnoreCase(key)) || email.equals(keyLower)
                    || (!local.isEmpty() && local.equals(keyLower))) {
                matches.add(u.getId());
            }
        }
        return matches.size() == 1 ? matches.iterator().next() : null;
    }

    private void fire(String label, Runnable work) {
        AfterCommit.run(() -> {
            try {
                if (requiresNew != null) {
                    requiresNew.executeWithoutResult(status -> work.run());
                } else {
                    work.run();
                }
            } catch (RuntimeException e) {
                log.warn("Falha ao gerar notificação {}; operação principal mantida", label, e);
            }
        });
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}
