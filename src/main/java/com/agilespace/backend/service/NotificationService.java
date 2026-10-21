package com.agilespace.backend.service;

import com.agilespace.backend.domain.Notification;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.NotificationDto;
import com.agilespace.backend.dto.UnreadCountDto;
import com.agilespace.backend.repository.NotificationRepository;
import com.agilespace.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Notificações do usuário (docs/home-api.md §2). Leitura e escrita sempre do próprio usuário (userId do JWT): a
 * notificação de outra pessoa responde 404, como se não existisse. A criação ({@link #notifyUsers}) é usada pelos
 * gatilhos de {@link NotificationTriggers}; o autor da ação nunca é notificado.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    public static final String FOR_YOU = "FOR_YOU";
    public static final String ANNOUNCEMENTS = "ANNOUNCEMENTS";

    /** FOR_YOU = todos os kinds exceto ANNOUNCEMENT e MESSAGE. */
    static final List<String> FOR_YOU_KINDS = List.of(Notification.INVITE, Notification.REMINDER, Notification.ACTION_ASSIGNED,
            Notification.LINK_REQUEST, Notification.SUPPORT_REPLY);
    static final List<String> ANNOUNCEMENT_KINDS = List.of(Notification.ANNOUNCEMENT);
    static final List<String> ALL_KINDS = List.of(Notification.INVITE, Notification.REMINDER, Notification.ACTION_ASSIGNED,
            Notification.MESSAGE, Notification.ANNOUNCEMENT, Notification.LINK_REQUEST, Notification.SUPPORT_REPLY);

    static final int MAX_LIMIT = 100;
    static final int MAX_TITLE = 200;
    static final int MAX_TEXT = 500;

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final ConversationService conversationService;
    private final UserSignalService signalService;

    /** O que um gatilho quer avisar. {@code dedupeKey} != null = no máximo uma por pessoa com essa chave. */
    public record Draft(String kind, String title, String text, String link, String actorId, String squadId, String dedupeKey) { }

    @Transactional(readOnly = true)
    public List<NotificationDto> list(String userId, String category, Integer limit) {
        requireUser(userId);
        int size = limit == null || limit <= 0 ? 30 : Math.min(limit, MAX_LIMIT);
        List<Notification> rows = notificationRepository.findForUser(userId, kindsOf(category, false), PageRequest.of(0, size));
        Map<String, User> actors = new HashMap<>();
        Set<String> actorIds = new HashSet<>();
        rows.forEach(n -> { if (n.getActorId() != null) actorIds.add(n.getActorId()); });
        if (!actorIds.isEmpty()) {
            userRepository.findAllById(actorIds).forEach(u -> actors.put(u.getId(), u));
        }
        return rows.stream().map(n -> toDto(n, actors.get(n.getActorId()))).toList();
    }

    @Transactional(readOnly = true)
    public UnreadCountDto unreadCount(String userId) {
        requireUser(userId);
        long forYou = notificationRepository.countUnread(userId, FOR_YOU_KINDS);
        long announcements = notificationRepository.countUnread(userId, ANNOUNCEMENT_KINDS);
        long messages = conversationService.unreadTotal(userId);
        return UnreadCountDto.of(forYou, messages, announcements);
    }

    @Transactional
    public void markRead(String userId, String notificationId) {
        requireUser(userId);
        Notification n = notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notificação não encontrada."));
        if (n.getReadAt() == null) {
            n.setReadAt(ServerTime.now());
            notificationRepository.save(n);
        }
        signalService.signal(userId, UserSignalService.UNREAD);
    }

    /** Marca todas como lidas; {@code category} null = todas as categorias. */
    @Transactional
    public void markAllRead(String userId, String category) {
        requireUser(userId);
        notificationRepository.markAllRead(userId, kindsOf(category, true), ServerTime.now());
        signalService.signal(userId, UserSignalService.UNREAD);
    }

    /**
     * Cria a notificação para cada destinatário (menos o autor e quem já a recebeu pela mesma chave) e manda o sinal
     * {@code signalType} a quem recebeu. Devolve quem foi notificado.
     */
    @Transactional
    public Set<String> notifyUsers(Collection<String> recipients, Draft draft, String signalType) {
        if (recipients == null || recipients.isEmpty() || draft == null || draft.text() == null || draft.text().isBlank()) {
            return Set.of();
        }
        Set<String> targets = new LinkedHashSet<>();
        for (String r : recipients) {
            if (r != null && !r.isBlank() && !r.equals(draft.actorId())) targets.add(r);
        }
        if (targets.isEmpty()) {
            return Set.of();
        }
        if (draft.dedupeKey() != null) {
            targets.removeAll(new HashSet<>(notificationRepository.findUserIdsWithDedupeKey(draft.dedupeKey(), targets)));
            if (targets.isEmpty()) {
                return Set.of();
            }
        }
        LocalDateTime now = ServerTime.now();
        List<Notification> rows = new ArrayList<>(targets.size());
        for (String userId : targets) {
            rows.add(Notification.builder()
                    .id(UUID.randomUUID().toString())
                    .userId(userId)
                    .kind(draft.kind())
                    .title(truncate(draft.title(), MAX_TITLE))
                    .text(truncate(draft.text(), MAX_TEXT))
                    .link(truncate(draft.link(), 500))
                    .actorId(draft.actorId())
                    .squadId(truncate(draft.squadId(), 100))
                    .dedupeKey(truncate(draft.dedupeKey(), 200))
                    .createdAt(now)
                    .build());
        }
        notificationRepository.saveAll(rows);
        signalService.signal(targets, signalType == null ? UserSignalService.NOTIFICATION : signalType);
        return targets;
    }

    /**
     * Retira um aviso que deixou de fazer sentido (ex.: pedido de vínculo cancelado): marca como lida, para todos, a
     * notificação com esta chave e manda o sinal de não lidas a quem a tinha pendente. A linha continua no histórico.
     */
    @Transactional
    public void retireByDedupeKey(String dedupeKey) {
        if (dedupeKey == null || dedupeKey.isBlank()) return;
        List<String> holders = notificationRepository.findUnreadUserIdsByDedupeKey(dedupeKey);
        if (holders.isEmpty()) return;
        notificationRepository.markReadByDedupeKey(dedupeKey, ServerTime.now());
        signalService.signal(holders, UserSignalService.UNREAD);
    }

    /** Marca como lidas as notificações desta pessoa, deste tipo, que apontam para {@code link}. */
    @Transactional
    public void markReadByLink(String userId, String kind, String link) {
        if (userId == null || userId.isBlank() || link == null) return;
        int changed = notificationRepository.markReadByUserKindAndLink(userId, kind, link, ServerTime.now());
        if (changed > 0) {
            signalService.signal(userId, UserSignalService.UNREAD);
        }
    }

    // --- internos ---

    static List<String> kindsOf(String category, boolean nullMeansAll) {
        if (category == null || category.isBlank()) {
            return nullMeansAll ? ALL_KINDS : FOR_YOU_KINDS;
        }
        return switch (category.trim().toUpperCase(Locale.ROOT)) {
            case FOR_YOU -> FOR_YOU_KINDS;
            case ANNOUNCEMENTS -> ANNOUNCEMENT_KINDS;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Categoria inválida: use FOR_YOU ou ANNOUNCEMENTS.");
        };
    }

    private static NotificationDto toDto(Notification n, User actor) {
        return new NotificationDto(n.getId(), n.getKind(), n.getTitle(), n.getText(), n.getLink(),
                n.getActorId(), actor != null ? actor.getName() : null, null, n.getSquadId(),
                ServerTime.toInstant(n.getCreatedAt()), ServerTime.toInstant(n.getReadAt()));
    }


    static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static void requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida.");
        }
    }

}
