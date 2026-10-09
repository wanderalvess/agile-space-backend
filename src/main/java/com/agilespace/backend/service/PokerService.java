package com.agilespace.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
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

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Janela em que o organizador atual ainda conta como presente (o cliente bate heartbeat a cada 25s). */
    private static final long HOST_PRESENCE_WINDOW_SECONDS = 90;
    private static final int MAX_VOTE_VALUE_LENGTH = 50;
    private static final int MAX_REACTION_EMOJI_LENGTH = 16;
    /** Valor que substitui o voto real para quem não pode vê-lo (salas com votos às cegas). */
    static final String MASKED_VOTE = "*";
    private static final Set<String> FIBONACCI_DECK = Set.of("0", "1", "2", "3", "5", "8", "13", "21", "?", "☕");
    private static final Set<String> HOURS_DECK = Set.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "12", "14",
            "16", "18", "20", "25", "30", "35", "40", "45", "50", "?", "☕");
    private static final Set<String> TSHIRT_DECK = Set.of("PP", "P", "M", "G", "GG", "?", "☕");

    /**
     * Eventos só saem depois do commit: antes disso o cliente que reage ao evento pode ler o estado
     * antigo, e um rollback deixaria a sala toda com o aviso de algo que não aconteceu.
     */
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private void publish(String roomId, String eventType, Object payload) {
        afterCommit(() -> webSocketHandler.broadcastEvent(roomId, eventType, payload));
    }

    // --- Room Logic ---
    @Transactional(readOnly = true)
    public Optional<PokerRoom> getRoom(String roomId) {
        return roomRepository.findById(roomId);
    }

    @Transactional
    public PokerRoom saveOrUpdateRoom(PokerRoom room, String callerId, String callerRole) {
        Optional<PokerRoom> existing = room.getId() != null ? roomRepository.findById(room.getId()) : Optional.empty();
        if (existing.isPresent()) {
            PokerRoom current = existing.get();
            requireRoomParticipant(current, callerId, callerRole);
            guardCreatorChange(current, room, callerId, callerRole);
            // Quem não envia a versão (cliente antigo, MCP) mantém o comportamento anterior, última
            // gravação vence. Sem isso o Spring Data trataria a sala como nova e tentaria um INSERT.
            if (room.getVersion() == null) {
                room.setVersion(current.getVersion());
            }
        } else {
            room.setCreatorId(callerId);
        }
        // saveAndFlush: a versão só é incrementada no flush, e o broadcast abaixo ainda roda dentro
        // da transação. Com save() os clientes receberiam a versão antiga e o próprio autor tomaria
        // 409 na ação seguinte.
        PokerRoom saved = roomRepository.saveAndFlush(room);
        publish(saved.getId(), "ROOM_UPDATED", saved);
        return saved;
    }

    /**
     * Trocar o organizador (creatorId) é a única forma de "assumir o controle". Um facilitador pode
     * repassar a sala; um participante comum só assume para si mesmo e só se o organizador atual
     * sumiu (sem heartbeat recente). Sem isso, qualquer participante sequestrava a sala pelo corpo do POST.
     */
    private void guardCreatorChange(PokerRoom current, PokerRoom incoming, String callerId, String callerRole) {
        String newCreator = incoming.getCreatorId();
        if (newCreator == null || newCreator.isBlank()) {
            incoming.setCreatorId(current.getCreatorId());
            return;
        }
        if (newCreator.equals(current.getCreatorId())) {
            return;
        }
        if (callerId != null && isRoomFacilitator(current, callerId, callerRole)) {
            return;
        }
        if (!newCreator.equals(callerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Só é possível assumir o controle da sala para si mesmo.");
        }
        boolean hostPresent = current.getCreatorId() != null && participantRepository
                .findById(current.getId() + "_" + current.getCreatorId())
                .map(p -> isRecent(p.getLastSeen()))
                .orElse(false);
        if (hostPresent) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "O organizador atual ainda está na sala.");
        }
    }

    private static boolean isRecent(String isoInstant) {
        if (isoInstant == null || isoInstant.isBlank()) return false;
        try {
            return java.time.Instant.parse(isoInstant)
                    .isAfter(java.time.Instant.now().minusSeconds(HOST_PRESENCE_WINDOW_SECONDS));
        } catch (Exception e) {
            return false;
        }
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
        publish(saved.getId(), "ROOM_UPDATED", saved);
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
     * A entrada também registra o usuário em participantIds da sala, com a sala travada, para que
     * entradas simultâneas não se atropelem nem tomem 409.
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
        // O upsert nunca rebaixa (OR com o valor antigo). Quando um facilitador edita OUTRO participante
        // e manda a flag explicitamente, ela vale de verdade — é assim que "assumir controle" tira o antigo.
        if (facilitatorCaller && !self && participant.getIsFacilitator() != null) {
            participantRepository.setFacilitator(dbId, participant.getIsFacilitator());
        }
        PokerParticipant saved = participantRepository.findById(dbId).orElse(participant);
        registerParticipantId(room, participant.getId());
        publish(participant.getRoomId(), "PARTICIPANT_JOINED", saved);
        return saved;
    }

    private void registerParticipantId(PokerRoom room, String userId) {
        if (!(userId != null && !userId.isBlank())) return;
        if (containsText(room.getParticipantIds(), userId)) return;
        PokerRoom locked = roomRepository.findByIdForUpdate(room.getId()).orElse(room);
        if (containsText(locked.getParticipantIds(), userId)) return;
        ArrayNode ids = JSON.createArrayNode();
        if (locked.getParticipantIds() != null && locked.getParticipantIds().isArray()) {
            locked.getParticipantIds().forEach(ids::add);
        }
        ids.add(userId);
        locked.setParticipantIds(ids);
        roomRepository.saveAndFlush(locked);
        publish(locked.getId(), "ROOM_UPDATED", locked);
    }

    private static boolean containsText(JsonNode array, String value) {
        if (array == null || !array.isArray()) return false;
        for (JsonNode n : array) {
            if (value.equals(n.asText(null))) return true;
        }
        return false;
    }

    /**
     * Facilitador no servidor: criador, ADMIN/LEAD, participante marcado como facilitador ou com papel
     * "organizador" (é o mesmo critério que o cliente usa para mostrar os controles de facilitação).
     */
    private boolean isRoomFacilitator(PokerRoom room, String callerId, String callerRole) {
        if (isPrivilegedRole(callerRole) || callerId.equals(room.getCreatorId())) {
            return true;
        }
        return participantRepository.findById(room.getId() + "_" + callerId)
                .map(p -> Boolean.TRUE.equals(p.getIsFacilitator()) || "organizador".equalsIgnoreCase(p.getRole()))
                .orElse(false);
    }

    /** Instante atual em UTC no formato ISO-8601 com milissegundos e sufixo Z (o 'Z' precisa ser verdadeiro). */
    private static String nowUtcIso() {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.now());
    }

    /**
     * Heartbeat atualiza só a coluna last_seen. Ler e regravar a entidade inteira podia desfazer um
     * papel ou a flag de facilitador alterados no mesmo instante por outro request.
     */
    @Transactional
    public void updateHeartbeat(String roomId, String userId) {
        participantRepository.updateLastSeen(roomId + "_" + userId, nowUtcIso());
    }

    /** Sair da sala: o próprio usuário, ADMIN ou um facilitador (remover participante). */
    @Transactional
    public void leaveRoom(String roomId, String userId, String callerId, String callerRole) {
        requireSelfOrFacilitator(roomId, userId, callerId, callerRole);
        participantRepository.deleteByRoomIdAndId(roomId, userId);
        publish(roomId, "PARTICIPANT_LEFT", Map.of("userId", userId));
    }

    private void requireSelfOrFacilitator(String roomId, String userId, String callerId, String callerRole) {
        if (isPrivilegedRole(callerRole) || (callerId != null && callerId.equals(userId))) {
            return;
        }
        PokerRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sala não encontrada"));
        if (callerId == null || !isRoomFacilitator(room, callerId, callerRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Apenas o próprio usuário ou um facilitador pode fazer isso.");
        }
    }

    // --- Votes Logic ---
    private static boolean isBlind(PokerRoom room) {
        return room.getSettings() != null && room.getSettings().path("blindVotes").asBoolean(false);
    }

    private static boolean isAsync(PokerRoom room) {
        return "async".equalsIgnoreCase(room.getMode());
    }

    /** Votos de um tópico já revelados: sala síncrona = flag da sala; assíncrona = lista por tópico. */
    private static boolean isRevealed(PokerRoom room, String issueId) {
        if (isAsync(room)) {
            return issueId != null && containsText(room.getRevealedIssues(), issueId);
        }
        return Boolean.TRUE.equals(room.getVotesRevealed());
    }

    private static PokerVote masked(PokerVote vote) {
        return PokerVote.builder()
                .id(vote.getId())
                .roomId(vote.getRoomId())
                .participantId(vote.getParticipantId())
                .value(MASKED_VOTE)
                .timestamp(vote.getTimestamp())
                .issueId(vote.getIssueId())
                .participantNickname(vote.getParticipantNickname())
                .participantRole(vote.getParticipantRole())
                .participantGlobalRole(vote.getParticipantGlobalRole())
                .build();
    }

    /**
     * Em salas com votos às cegas (settings.blindVotes, opt-in do facilitador) o valor dos votos de
     * terceiros só sai depois da revelação; antes disso o REST e o WebSocket entregavam o número a
     * qualquer um e a ancoragem que o baralho escondido evita voltava pelo DevTools.
     */
    @Transactional(readOnly = true)
    public List<PokerVote> getVotes(String roomId, String callerId, String callerRole) {
        List<PokerVote> votes = voteRepository.findByRoomId(roomId);
        PokerRoom room = roomRepository.findById(roomId).orElse(null);
        if (room == null || !isBlind(room) || (callerId != null && isRoomFacilitator(room, callerId, callerRole))) {
            return votes;
        }
        List<PokerVote> visible = new ArrayList<>();
        for (PokerVote v : votes) {
            boolean own = callerId != null && callerId.equals(v.getParticipantId());
            visible.add(own || isRevealed(room, v.getIssueId()) ? v : masked(v));
        }
        return visible;
    }

    @Transactional
    public PokerVote saveVote(PokerVote vote, String callerId) {
        if (callerId == null || !callerId.equals(vote.getParticipantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Só é possível registrar o próprio voto.");
        }
        String value = vote.getValue();
        if (value == null || value.isBlank() || value.length() > MAX_VOTE_VALUE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valor de voto inválido.");
        }
        PokerRoom room = roomRepository.findById(vote.getRoomId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sala não encontrada"));
        boolean member = callerId.equals(room.getCreatorId())
                || participantRepository.existsById(room.getId() + "_" + callerId);
        if (!member) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Apenas participantes da sala podem votar.");
        }
        Set<String> deck = deckValues(room.getDeckType());
        if (deck != null && !deck.contains(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Esta carta não existe no baralho da sala.");
        }

        String id;
        if (isAsync(room)) {
            if (vote.getIssueId() == null || vote.getIssueId().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe a tarefa do voto.");
            }
            id = vote.getRoomId() + "_" + vote.getParticipantId() + "_" + vote.getIssueId();
        } else {
            id = vote.getRoomId() + "_" + vote.getParticipantId();
            String active = room.getActiveIssueId();
            if (vote.getIssueId() != null && active != null && !active.equals(vote.getIssueId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A tarefa em votação mudou. Atualize a sala.");
            }
        }
        if (isRevealed(room, isAsync(room) ? vote.getIssueId() : null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Os votos desta rodada já foram revelados.");
        }
        vote.setId(id);
        // Atualizar só a confiança (ou o valor) não pode apagar quem votou, o papel congelado nem a tarefa.
        voteRepository.findById(id).ifPresent(old -> {
            if (vote.getParticipantNickname() == null) vote.setParticipantNickname(old.getParticipantNickname());
            if (vote.getParticipantRole() == null) vote.setParticipantRole(old.getParticipantRole());
            if (vote.getParticipantGlobalRole() == null) vote.setParticipantGlobalRole(old.getParticipantGlobalRole());
            if (vote.getIssueId() == null) vote.setIssueId(old.getIssueId());
        });
        if (vote.getTimestamp() == null || vote.getTimestamp().isBlank()) {
            vote.setTimestamp(nowUtcIso());
        }
        PokerVote saved = voteRepository.save(vote);
        if (isBlind(room) && !isRevealed(room, saved.getIssueId())) {
            PokerVote hidden = masked(saved);
            afterCommit(() -> {
                webSocketHandler.broadcastEventToUsers(saved.getRoomId(), "VOTE_SAVED", saved, Set.of(callerId));
                webSocketHandler.broadcastEventExcludingUsers(saved.getRoomId(), "VOTE_SAVED", hidden, Set.of(callerId));
            });
        } else {
            publish(vote.getRoomId(), "VOTE_SAVED", saved);
        }
        return saved;
    }

    private static Set<String> deckValues(String deckType) {
        if (deckType == null) return null;
        return switch (deckType.toLowerCase()) {
            case "fibonacci" -> FIBONACCI_DECK;
            case "hours" -> HOURS_DECK;
            case "tshirt" -> TSHIRT_DECK;
            default -> null;
        };
    }

    /**
     * Remove o voto de um participante (todos os dele, ou só o do tópico informado nas salas assíncronas).
     * O próprio usuário remove o seu (até a revelação); facilitador/ADMIN removem de qualquer um.
     */
    @Transactional
    public void removeVote(String roomId, String userId, String issueId, String callerId, String callerRole) {
        PokerRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sala não encontrada"));
        boolean self = callerId != null && callerId.equals(userId);
        boolean facilitator = callerId != null && isRoomFacilitator(room, callerId, callerRole);
        if (!self && !facilitator) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Apenas o próprio usuário ou um facilitador pode remover este voto.");
        }
        if (!facilitator && isRevealed(room, issueId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Os votos desta rodada já foram revelados.");
        }
        if (issueId != null && !issueId.isBlank() && isAsync(room)) {
            voteRepository.deleteById(roomId + "_" + userId + "_" + issueId);
        } else {
            voteRepository.deleteByRoomIdAndParticipantId(roomId, userId);
        }
        Map<String, String> payload = new java.util.HashMap<>();
        payload.put("userId", userId);
        if (issueId != null && !issueId.isBlank()) payload.put("issueId", issueId);
        publish(roomId, "VOTE_REMOVED", payload);
    }

    @Transactional
    public void clearVotes(String roomId, String callerId, String callerRole) {
        PokerRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sala não encontrada"));
        requireRoomParticipant(room, callerId, callerRole);
        voteRepository.deleteByRoomId(roomId);
        publish(roomId, "VOTES_CLEARED", Map.of());
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
    public PokerRound saveRound(PokerRound round, String callerId, String callerRole) {
        PokerRoom room = roomRepository.findById(round.getRoomId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sala não encontrada"));
        requireRoomParticipant(room, callerId, callerRole);
        if (round.getId() == null || round.getId().trim().isEmpty()) {
            round.setId(java.util.UUID.randomUUID().toString());
        } else {
            // id vindo do cliente (idempotência / atualização da própria rodada) nunca toca rodada de outra sala
            roundRepository.findById(round.getId()).ifPresent(existing -> {
                if (!round.getRoomId().equals(existing.getRoomId())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Esta rodada pertence a outra sala.");
                }
            });
        }
        PokerRound saved = roundRepository.save(round);
        publish(round.getRoomId(), "ROUND_SAVED", saved);
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
        publish(roomId, "ROUNDS_CLEARED", Map.of());
    }

    // --- Reaction Logic (WebSocket Only) ---
    /**
     * Reação efêmera. Só participante da sala; o corpo do cliente nunca é repassado cru: remetente,
     * apelido e hora são do servidor e o emoji é limitado, senão qualquer um forjava reação de outro.
     */
    @Transactional(readOnly = true)
    public void sendReaction(String roomId, String reactionPayload, String callerId, String callerRole) {
        PokerRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sala não encontrada"));
        requireRoomParticipant(room, callerId, callerRole);
        String emoji;
        try {
            emoji = JSON.readTree(reactionPayload).path("emoji").asText("");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reação inválida.");
        }
        if (emoji.isBlank() || emoji.length() > MAX_REACTION_EMOJI_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reação inválida.");
        }
        String nickname = participantRepository.findById(roomId + "_" + callerId)
                .map(PokerParticipant::getNickname).orElse(null);
        ObjectNode out = JSON.createObjectNode();
        out.put("type", "REACTION");
        out.put("uid", callerId);
        out.put("emoji", emoji);
        out.put("ts", nowUtcIso());
        if (nickname != null) out.put("nickname", nickname);
        // Dispara direto via WebSocket, sem persistência
        webSocketHandler.broadcastReaction(roomId, out.toString());
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
            afterCommit(() -> webSocketHandler.broadcastEventToUsers(roomId, eventType, payload, dmUsers(channelId, callerId)));
        } else {
            publish(roomId, eventType, payload);
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
