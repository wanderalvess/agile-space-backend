package com.agilespace.backend.service;

import com.agilespace.backend.domain.Conversation;
import com.agilespace.backend.domain.DirectMessage;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.ConversationDto;
import com.agilespace.backend.dto.MessageDto;
import com.agilespace.backend.dto.UserRefDto;
import com.agilespace.backend.repository.ConversationRepository;
import com.agilespace.backend.repository.DirectMessageRepository;
import com.agilespace.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Mensagens diretas (docs/home-api.md §3). Só entre pessoas que compartilham ao menos uma squad
 * ({@link SquadAudienceService}); a checagem vale na abertura da conversa. Depois disso só os dois participantes
 * leem/escrevem. Cada envio manda o sinal MESSAGE {conversationId} no canal /ws/user dos dois lados.
 */
@Service
@RequiredArgsConstructor
public class ConversationService {

    static final int MAX_BODY = 2000;
    static final int MAX_LIMIT = 100;

    private final ConversationRepository conversationRepository;
    private final DirectMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final SquadAudienceService audienceService;
    private final UserSignalService signalService;

    @Transactional(readOnly = true)
    public List<ConversationDto> list(String me) {
        requireUser(me);
        List<Conversation> conversations = conversationRepository.findByParticipant(me);
        if (conversations.isEmpty()) {
            return List.of();
        }
        List<String> ids = conversations.stream().map(Conversation::getId).toList();
        Map<String, DirectMessage> last = messageRepository.findLastMessages(ids).stream()
                .collect(Collectors.toMap(DirectMessage::getConversationId, Function.identity(), (a, b) -> a));
        Map<String, Long> unread = unreadByConversation(me);
        Map<String, User> users = usersById(conversations.stream().map(c -> c.otherParticipant(me)).collect(Collectors.toSet()));
        return conversations.stream()
                .map(c -> toDto(c, me, users.get(c.otherParticipant(me)), last.get(c.getId()), unread.getOrDefault(c.getId(), 0L)))
                .toList();
    }

    /** Usuários ativos que compartilham squad com o chamador (o próprio fica de fora), por nome. */
    @Transactional(readOnly = true)
    public List<UserRefDto> contacts(String me, String role) {
        Set<String> ids = contactIds(me, role);
        if (ids.isEmpty()) {
            return List.of();
        }
        return userRepository.findActiveByIds(ids).stream()
                .sorted(Comparator.comparing(u -> u.getName() == null ? "" : u.getName().toLowerCase(Locale.ROOT)))
                .map(ConversationService::userRef)
                .toList();
    }

    /** Get-or-create da conversa com {@code otherUserId}: 400 consigo mesmo, 404 inexistente, 403 sem squad em comum. */
    @Transactional
    public ConversationDto open(String me, String role, String otherUserId) {
        requireUser(me);
        if (otherUserId == null || otherUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe com quem conversar.");
        }
        if (me.equals(otherUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Não dá para abrir conversa consigo mesmo.");
        }
        User other = userRepository.findById(otherUserId).filter(User::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado."));
        String a = me.compareTo(otherUserId) < 0 ? me : otherUserId;
        String b = a.equals(me) ? otherUserId : me;
        Conversation existing = conversationRepository.findByUserAIdAndUserBId(a, b).orElse(null);
        if (existing == null) {
            if (!shareSquad(me, role, otherUserId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Só é possível conversar com pessoas das suas squads.");
            }
            LocalDateTime now = ServerTime.now();
            try {
                existing = conversationRepository.saveAndFlush(Conversation.builder()
                        .id(UUID.randomUUID().toString()).userAId(a).userBId(b)
                        .createdAt(now).updatedAt(now).build());
            } catch (DataIntegrityViolationException race) {
                // Os dois lados abriram ao mesmo tempo: vale a conversa que entrou primeiro.
                existing = conversationRepository.findByUserAIdAndUserBId(a, b).orElseThrow(() -> race);
            }
        }
        DirectMessage last = messageRepository.findLatest(existing.getId(), PageRequest.of(0, 1)).stream().findFirst().orElse(null);
        long unread = unreadByConversation(me).getOrDefault(existing.getId(), 0L);
        return toDto(existing, me, other, last, unread);
    }

    @Transactional(readOnly = true)
    public List<MessageDto> messages(String me, String conversationId, Integer limit, Instant before) {
        Conversation c = requireParticipant(me, conversationId);
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, MAX_LIMIT);
        List<DirectMessage> page = before == null
                ? messageRepository.findLatest(c.getId(), PageRequest.of(0, size))
                : messageRepository.findLatestBefore(c.getId(), ServerTime.toLocal(before), PageRequest.of(0, size));
        List<MessageDto> out = new ArrayList<>(page.stream().map(ConversationService::toDto).toList());
        Collections.reverse(out); // contrato: createdAt asc
        return out;
    }

    @Transactional
    public MessageDto send(String me, String conversationId, String body) {
        Conversation c = requireParticipant(me, conversationId);
        String text = body == null ? "" : body.strip();
        if (text.isEmpty() || text.length() > MAX_BODY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A mensagem deve ter de 1 a " + MAX_BODY + " caracteres.");
        }
        LocalDateTime now = ServerTime.now();
        DirectMessage saved = messageRepository.save(DirectMessage.builder()
                .id(UUID.randomUUID().toString()).conversationId(c.getId()).senderId(me).body(text).createdAt(now).build());
        c.setUpdatedAt(now);
        c.markReadBy(me, now);
        conversationRepository.save(c);
        signalService.signal(List.of(c.otherParticipant(me), me), UserSignalService.MESSAGE, c.getId());
        return toDto(saved);
    }

    @Transactional
    public void markRead(String me, String conversationId) {
        Conversation c = requireParticipant(me, conversationId);
        c.markReadBy(me, ServerTime.now());
        conversationRepository.save(c);
        signalService.signal(me, UserSignalService.UNREAD);
    }

    /** Total de mensagens diretas não lidas (entra no unread-count). */
    @Transactional(readOnly = true)
    public long unreadTotal(String me) {
        return unreadByConversation(me).values().stream().mapToLong(Long::longValue).sum();
    }

    // --- internos ---

    Set<String> contactIds(String me, String role) {
        Set<String> ids = audienceService.contextFor(me, role)
                .map(ctx -> audienceService.memberUserIdsOf(ctx.squadIds()))
                .map(set -> (Set<String>) new LinkedHashSet<>(set))
                .orElseGet(LinkedHashSet::new);
        ids.remove(me);
        return ids;
    }

    /** Em comum: o outro está nas minhas squads, ou eu nas dele (liderança transversal enxerga a tribo). */
    boolean shareSquad(String me, String role, String other) {
        if (contactIds(me, role).contains(other)) {
            return true;
        }
        return contactIds(other, null).contains(me);
    }

    private Conversation requireParticipant(String me, String conversationId) {
        requireUser(me);
        Conversation c = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversa não encontrada."));
        if (!c.hasParticipant(me)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Você não participa desta conversa.");
        }
        return c;
    }

    private Map<String, Long> unreadByConversation(String me) {
        Map<String, Long> out = new HashMap<>();
        for (Object[] row : messageRepository.countUnreadByConversation(me)) {
            out.put((String) row[0], ((Number) row[1]).longValue());
        }
        return out;
    }

    private Map<String, User> usersById(Collection<String> ids) {
        Map<String, User> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        userRepository.findAllById(ids).forEach(u -> out.put(u.getId(), u));
        return out;
    }

    private static void requireUser(String me) {
        if (me == null || me.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida.");
        }
    }

    private static ConversationDto toDto(Conversation c, String me, User other, DirectMessage last, long unread) {
        String otherId = c.otherParticipant(me);
        UserRefDto ref = other != null ? userRef(other) : new UserRefDto(otherId, "Usuário removido", null);
        return new ConversationDto(c.getId(), ref, last == null ? null : toDto(last), unread, ServerTime.toInstant(c.getUpdatedAt()));
    }

    static UserRefDto userRef(User u) {
        return new UserRefDto(u.getId(), u.getName(), null);
    }

    private static MessageDto toDto(DirectMessage m) {
        return new MessageDto(m.getId(), m.getConversationId(), m.getSenderId(), m.getBody(), ServerTime.toInstant(m.getCreatedAt()));
    }


}
