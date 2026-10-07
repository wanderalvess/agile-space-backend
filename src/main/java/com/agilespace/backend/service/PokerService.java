package com.agilespace.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.agilespace.backend.domain.PokerRoom;
import com.agilespace.backend.domain.PokerParticipant;
import com.agilespace.backend.domain.PokerVote;
import com.agilespace.backend.domain.PokerRound;
import com.agilespace.backend.domain.PokerChatMessage;
import com.agilespace.backend.repository.PokerChatMessageRepository;
import com.agilespace.backend.repository.PokerRoomRepository;
import com.agilespace.backend.repository.PokerParticipantRepository;
import com.agilespace.backend.repository.PokerVoteRepository;
import com.agilespace.backend.repository.PokerRoundRepository;
import com.agilespace.backend.websocket.PokerWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PokerService {

    private final PokerRoomRepository roomRepository;
    private final PokerParticipantRepository participantRepository;
    private final PokerVoteRepository voteRepository;
    private final PokerRoundRepository roundRepository;
    private final PokerChatMessageRepository chatMessageRepository;
    private final PokerWebSocketHandler webSocketHandler;

    static final int CHAT_MAX_TEXT = 8000;
    static final int CHAT_MAX_SENDER_NAME = 120;
    static final int CHAT_MAX_SENDER_CATEGORY = 255;
    private static final Set<String> CHAT_KINDS = Set.of("text", "code");

    // --- Room Logic ---
    @Transactional(readOnly = true)
    public Optional<PokerRoom> getRoom(String roomId) {
        return roomRepository.findById(roomId);
    }

    @Transactional
    public PokerRoom saveOrUpdateRoom(PokerRoom room, String callerId, String callerRole) {
        Optional<PokerRoom> existing = room.getId() != null ? roomRepository.findById(room.getId()) : Optional.empty();
        if (existing.isPresent()) {
            requireRoomParticipant(existing.get(), callerId, callerRole);
            // Quem não envia a versão (cliente antigo, MCP) mantém o comportamento anterior, última
            // gravação vence. Sem isso o Spring Data trataria a sala como nova e tentaria um INSERT.
            if (room.getVersion() == null) {
                room.setVersion(existing.get().getVersion());
            }
        } else {
            room.setCreatorId(callerId);
        }
        // saveAndFlush: a versão só é incrementada no flush, e o broadcast abaixo ainda roda dentro
        // da transação. Com save() os clientes receberiam a versão antiga e o próprio autor tomaria
        // 409 na ação seguinte.
        PokerRoom saved = roomRepository.saveAndFlush(room);
        webSocketHandler.broadcastEvent(saved.getId(), "ROOM_UPDATED", saved);
        return saved;
    }

    private static final int MAX_REFINEMENT_NOTES_LENGTH = 20_000;

    /**
     * Notas de refinamento (Dev/QA) editáveis por qualquer participante da sala. Mescla só os
     * campos enviados no item informado — o resto da fila e as notas do outro campo ficam
     * intactos, então quem digita com uma cópia desatualizada da sala não apaga nada.
     */
    @Transactional
    public PokerRoom updateIssueNotes(String roomId, String issueId, Map<String, String> notes, String callerId, String callerRole) {
        PokerRoom room = roomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sala não encontrada"));
        requireRoomParticipant(room, callerId, callerRole);

        JsonNode current = room.getIssuesQueue();
        if (current == null || !current.isArray()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tarefa não encontrada");
        }
        // Cópia profunda + setter: não depende do dirty-checking do Hibernate para JSON mutado no lugar.
        ArrayNode queue = ((ArrayNode) current).deepCopy();
        ObjectNode target = null;
        for (JsonNode item : queue) {
            if (item.isObject() && issueId.equals(item.path("id").asText(null))) {
                target = (ObjectNode) item;
                break;
            }
        }
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tarefa não encontrada");
        }

        for (String field : List.of("devNotes", "qaNotes")) {
            if (!notes.containsKey(field)) continue;
            String value = notes.get(field) == null ? "" : notes.get(field).trim();
            if (value.length() > MAX_REFINEMENT_NOTES_LENGTH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Notas muito longas (máx. " + MAX_REFINEMENT_NOTES_LENGTH + " caracteres).");
            }
            if (value.isEmpty()) {
                target.putNull(field);
            } else {
                target.set(field, TextNode.valueOf(value));
            }
        }

        room.setIssuesQueue(queue);
        PokerRoom saved = roomRepository.saveAndFlush(room);
        webSocketHandler.broadcastEvent(saved.getId(), "ROOM_UPDATED", saved);
        return saved;
    }

    private boolean isPrivilegedRole(String role) {
        return "ADMIN".equalsIgnoreCase(role) || "LEAD".equalsIgnoreCase(role);
    }

    /**
     * Facilitador é transferível (ex.: host cai e outro participante assume), então o gate
     * aqui é "já entrou na sala" (creatorId, participante com join registrado, ou ADMIN/LEAD) —
     * não "só o criador original". Isso fecha o buraco real (outsider que nunca entrou na sala
     * mexendo via roomId adivinhado) sem travar o claim-facilitator do frontend.
     */
    private void requireRoomParticipant(PokerRoom room, String callerId, String callerRole) {
        if (isPrivilegedRole(callerRole)) {
            return;
        }
        if (callerId != null && callerId.equals(room.getCreatorId())) {
            return;
        }
        if (callerId != null && participantRepository.existsById(room.getId() + "_" + callerId)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Apenas participantes da sala podem executar esta ação.");
    }

    @Transactional(readOnly = true)
    public List<PokerRoom> listRooms(int limit, String squadId) {
        int safeLimit = limit > 0 ? limit : 1000;
        return roomRepository.findByTeamIgnoreCaseOrderByCreatedAtDesc(squadId, PageRequest.of(0, safeLimit)).getContent();
    }

    // --- Participants Logic ---
    @Transactional(readOnly = true)
    public List<PokerParticipant> getParticipants(String roomId) {
        return participantRepository.findByRoomIdOrderByNicknameAsc(roomId);
    }

    /**
     * Entrada/atualização de participante. A identidade vem do JWT, não do corpo:
     * o próprio usuário se registra, mas só vira facilitador se for o criador da sala,
     * ADMIN/LEAD ou já for facilitador (o "assumir controle" do frontend grava o creatorId
     * da sala antes). Editar outro participante (papel, rebaixar facilitador antigo) é coisa de facilitador.
     */
    @Transactional
    public PokerParticipant joinRoom(PokerParticipant participant, String callerId, String callerRole) {
        if (callerId == null || callerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Autenticação necessária.");
        }
        PokerRoom room = roomRepository.findById(participant.getRoomId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sala não encontrada"));
        boolean facilitatorCaller = isRoomFacilitator(room, callerId, callerRole);
        boolean self = callerId.equals(participant.getId());
        if (!self && !facilitatorCaller) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Só é possível entrar na sala como o próprio usuário.");
        }
        if (!facilitatorCaller) {
            participant.setIsFacilitator(false);
        }
        String dbId = participant.getRoomId() + "_" + participant.getId();
        participant.setDbId(dbId);
        participant.setLastSeen(nowUtcIso());
        participantRepository.upsertParticipant(
                dbId,
                participant.getId(),
                participant.getRoomId(),
                participant.getNickname(),
                participant.getEmail(),
                participant.getRole(),
                participant.getIsFacilitator(),
                participant.getGlobalRole(),
                participant.getLastSeen());
        PokerParticipant saved = participantRepository.findById(dbId).orElse(participant);
        webSocketHandler.broadcastEvent(participant.getRoomId(), "PARTICIPANT_JOINED", saved);
        return saved;
    }

    private boolean isRoomFacilitator(PokerRoom room, String callerId, String callerRole) {
        if (isPrivilegedRole(callerRole) || callerId.equals(room.getCreatorId())) {
            return true;
        }
        return participantRepository.findById(room.getId() + "_" + callerId)
                .map(p -> Boolean.TRUE.equals(p.getIsFacilitator()))
                .orElse(false);
    }

    /** Instante atual em UTC no formato ISO-8601 com milissegundos e sufixo Z (o 'Z' precisa ser verdadeiro). */
    private static String nowUtcIso() {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.now());
    }

    @Transactional
    public void updateHeartbeat(String roomId, String userId) {
        String dbId = roomId + "_" + userId;
        participantRepository.findById(dbId).ifPresent(p -> {
            p.setLastSeen(nowUtcIso());
            participantRepository.save(p);
        });
    }

    @Transactional
    public void leaveRoom(String roomId, String userId) {
        participantRepository.deleteByRoomIdAndId(roomId, userId);
        webSocketHandler.broadcastEvent(roomId, "PARTICIPANT_LEFT", Map.of("userId", userId));
    }

    // --- Votes Logic ---
    @Transactional(readOnly = true)
    public List<PokerVote> getVotes(String roomId) {
        return voteRepository.findByRoomId(roomId);
    }

    @Transactional
    public PokerVote saveVote(PokerVote vote, String callerId) {
        if (callerId == null || !callerId.equals(vote.getParticipantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Só é possível registrar o próprio voto.");
        }
        String id = vote.getRoomId() + "_" + vote.getParticipantId();
        vote.setId(id);
        PokerVote saved = voteRepository.save(vote);
        webSocketHandler.broadcastEvent(vote.getRoomId(), "VOTE_SAVED", saved);
        return saved;
    }

    @Transactional
    public void removeVote(String roomId, String userId) {
        voteRepository.deleteByRoomIdAndParticipantId(roomId, userId);
        webSocketHandler.broadcastEvent(roomId, "VOTE_REMOVED", Map.of("userId", userId));
    }

    @Transactional
    public void clearVotes(String roomId, String callerId, String callerRole) {
        PokerRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sala não encontrada"));
        requireRoomParticipant(room, callerId, callerRole);
        voteRepository.deleteByRoomId(roomId);
        webSocketHandler.broadcastEvent(roomId, "VOTES_CLEARED", Map.of());
    }

    // --- Rounds (History) Logic ---
    @Transactional(readOnly = true)
    public List<PokerRound> getRounds(String roomId, int limit) {
        if (limit > 0) {
            return roundRepository.findRecentRounds(roomId, PageRequest.of(0, limit));
        }
        return roundRepository.findByRoomId(roomId);
    }

    @Transactional
    public PokerRound saveRound(PokerRound round) {
        if (round.getId() == null || round.getId().trim().isEmpty()) {
            round.setId(java.util.UUID.randomUUID().toString());
        }
        PokerRound saved = roundRepository.save(round);
        webSocketHandler.broadcastEvent(round.getRoomId(), "ROUND_SAVED", saved);
        return saved;
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<PokerRound> searchRounds(String query, org.springframework.data.domain.Pageable pageable) {
        return roundRepository.searchByTopicOrNote(query, pageable);
    }

    @Transactional
    public void clearRounds(String roomId, String callerId, String callerRole) {
        PokerRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sala não encontrada"));
        requireRoomParticipant(room, callerId, callerRole);
        roundRepository.deleteByRoomId(roomId);
        webSocketHandler.broadcastEvent(roomId, "ROUNDS_CLEARED", Map.of());
    }

    // --- Reaction Logic (WebSocket Only) ---
    public void sendReaction(String roomId, String reactionPayload) {
        // Dispara o payload direto via WebSocket para as sessões ativas da sala
        // sem persistência em banco para alta performance de animações de emojis
        webSocketHandler.broadcastReaction(roomId, reactionPayload);
    }

    // --- Chat Messages Logic ---
    @Transactional(readOnly = true)
    public List<PokerChatMessage> getChatMessages(String roomId, String channelId, String callerId) {
        requireChatChannelAccess(channelId, callerId);
        return chatMessageRepository.findByRoomIdAndChannelIdOrderByTsAsc(roomId, channelId);
    }

    @Transactional
    public PokerChatMessage saveChatMessage(String roomId, PokerChatMessage message, String callerId) {
        if (message == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mensagem inválida.");
        }
        if (callerId == null || !callerId.equals(message.getSenderId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Só é possível enviar mensagens como o próprio usuário.");
        }
        requireChatChannelAccess(message.getChannelId(), callerId);
        String text = message.getText();
        if (text == null || text.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A mensagem não pode ser vazia.");
        }
        if (text.length() > CHAT_MAX_TEXT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A mensagem excede " + CHAT_MAX_TEXT + " caracteres.");
        }
        String kind = message.getKind() == null || message.getKind().isBlank() ? "text" : message.getKind();
        if (!CHAT_KINDS.contains(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de mensagem inválido.");
        }
        PokerRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sala não encontrada"));
        Optional<PokerParticipant> participant = participantRepository.findById(roomId + "_" + callerId);
        if (participant.isEmpty() && !callerId.equals(room.getCreatorId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Apenas participantes da sala podem usar o chat.");
        }
        // Nome exibido vem do cadastro do participante, não do corpo da requisição.
        String senderName = participant.map(PokerParticipant::getNickname)
                .filter(n -> n != null && !n.isBlank())
                .orElse(message.getSenderName());
        if (senderName == null || senderName.trim().isEmpty() || senderName.length() > CHAT_MAX_SENDER_NAME) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome do remetente inválido.");
        }
        if (message.getSenderCategory() != null && message.getSenderCategory().length() > CHAT_MAX_SENDER_CATEGORY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Categoria do remetente inválida.");
        }
        String id = message.getId() == null || message.getId().trim().isEmpty()
                ? java.util.UUID.randomUUID().toString() : message.getId();
        // id vindo do cliente nunca sobrescreve uma mensagem existente (de outro autor ou sala)
        if (chatMessageRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma mensagem com este id.");
        }
        PokerChatMessage toSave = PokerChatMessage.builder()
                .id(id)
                .roomId(roomId)
                .channelId(message.getChannelId())
                .senderId(callerId)
                .senderName(senderName)
                .senderCategory(message.getSenderCategory())
                .text(text)
                .kind(kind)
                .ts(nowUtcIso())
                .build();
        PokerChatMessage saved = chatMessageRepository.save(toSave);
        publishChatEvent(roomId, "CHAT_MESSAGE_SAVED", saved, saved.getChannelId(), callerId);
        return saved;
    }

    @Transactional
    public void deleteChatMessage(String roomId, String messageId, String callerId, String callerRole) {
        PokerChatMessage message = chatMessageRepository.findById(messageId)
                .filter(m -> roomId.equals(m.getRoomId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mensagem não encontrada"));

        boolean isAuthor = callerId != null && callerId.equals(message.getSenderId());
        boolean isPrivileged = isPrivilegedRole(callerRole);
        // Facilitador da sala também pode moderar (apagar) mensagens nos canais públicos
        boolean isFacilitator = false;
        Optional<PokerRoom> roomOpt = roomRepository.findById(roomId);
        if (roomOpt.isPresent() && callerId != null && callerId.equals(roomOpt.get().getCreatorId())) {
            isFacilitator = true;
        }

        if (!isAuthor && !isPrivileged && !isFacilitator) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sem permissão para excluir esta mensagem.");
        }

        // DM só pode ser apagada pelos próprios participantes da conversa.
        if (isDmChannel(message.getChannelId()) && !isDmParticipant(message.getChannelId(), callerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sem acesso a esta conversa privada.");
        }
        chatMessageRepository.delete(message);
        publishChatEvent(roomId, "CHAT_MESSAGE_DELETED", Map.of(
                "messageId", messageId,
                "channelId", message.getChannelId()
        ), message.getChannelId(), callerId);
    }

    private static boolean isDmChannel(String channelId) {
        return channelId != null && channelId.startsWith("dm_");
    }

    /** dm_uidA_uidB: só os dois uids participam. */
    private static boolean isDmParticipant(String channelId, String userId) {
        if (userId == null || userId.isBlank() || !isDmChannel(channelId)) return false;
        String rest = channelId.substring(3);
        String asFirst = userId + "_";
        String asSecond = "_" + userId;
        return (rest.startsWith(asFirst) && rest.length() > asFirst.length())
                || (rest.endsWith(asSecond) && rest.length() > asSecond.length());
    }

    private static void requireChatChannelAccess(String channelId, String callerId) {
        if (channelId == null || channelId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Canal inválido.");
        }
        if (isDmChannel(channelId) && !isDmParticipant(channelId, callerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sem acesso a esta conversa privada.");
        }
    }

    /** Eventos de DM chegam só aos dois participantes; os demais canais, à sala toda. */
    private void publishChatEvent(String roomId, String eventType, Object payload, String channelId, String callerId) {
        if (isDmChannel(channelId)) {
            webSocketHandler.broadcastEventToUsers(roomId, eventType, payload, dmUsers(channelId, callerId));
        } else {
            webSocketHandler.broadcastEvent(roomId, eventType, payload);
        }
    }

    /** dm_uidA_uidB: com o caller conhecido, o outro uid é o resto — correto mesmo se o uid contiver "_". */
    private static Set<String> dmUsers(String channelId, String callerId) {
        String rest = channelId.substring(3);
        if (callerId == null) return Set.of();
        String asFirst = callerId + "_";
        String asSecond = "_" + callerId;
        if (rest.startsWith(asFirst) && rest.length() > asFirst.length()) {
            return new java.util.HashSet<>(List.of(callerId, rest.substring(asFirst.length())));
        }
        if (rest.endsWith(asSecond) && rest.length() > asSecond.length()) {
            return new java.util.HashSet<>(List.of(callerId, rest.substring(0, rest.length() - asSecond.length())));
        }
        return Set.of();
    }
}
