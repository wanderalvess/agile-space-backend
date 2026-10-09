package com.agilespace.backend.service;

import com.agilespace.backend.domain.BrainstormingBoard;
import com.agilespace.backend.domain.BrainstormingGroup;
import com.agilespace.backend.domain.BrainstormingIdea;
import com.agilespace.backend.domain.BrainstormingParticipant;
import com.agilespace.backend.repository.BrainstormingBoardRepository;
import com.agilespace.backend.repository.BrainstormingGroupRepository;
import com.agilespace.backend.repository.BrainstormingIdeaRepository;
import com.agilespace.backend.repository.BrainstormingParticipantRepository;
import com.agilespace.backend.websocket.BrainstormingWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Regras do Brainstorming. A identidade de quem age vem sempre do JWT ({@link CeremonyCaller}), nunca do corpo.
 * Decisão de produto: o link da sala dá acesso (qualquer usuário autenticado lê, entra e escreve ideias, votos e
 * grupos); só o facilitador (criador do mural) ou ADMIN muda fase/timer/configurações, apaga o mural e apaga
 * ideia de outra pessoa. Votos e fusões são operações atômicas no servidor (lock na linha), e os eventos do
 * WebSocket saem depois do commit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BrainstormingService {

    static final int MAX_TITLE = 255;
    static final int MAX_CONTENT = 5000;
    static final int MAX_NICKNAME = 100;
    static final int MAX_SHORT = 50;
    static final Set<String> PHASES = Set.of("ideation", "diagram", "grouping", "prioritization", "actions");
    static final Set<String> TIMER_STATUSES = Set.of("stopped", "running", "paused");
    static final Set<String> SETTING_KEYS = Set.of("isAnonymous", "isPresentationMode", "isRevealed");

    private final BrainstormingBoardRepository boardRepository;
    private final BrainstormingIdeaRepository ideaRepository;
    private final BrainstormingGroupRepository groupRepository;
    private final BrainstormingParticipantRepository participantRepository;
    private final BrainstormingWebSocketHandler webSocketHandler;

    /** Resultado de um upsert: {@code created} separa 201 de 200. */
    public record Saved<T>(T value, boolean created) {
    }

    // --- Utilidades ---

    private void publish(String boardId, String eventType, Object payload) {
        AfterCommit.run(() -> webSocketHandler.broadcastEvent(boardId, eventType, payload));
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static ResponseStatusException forbidden(String msg) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, msg);
    }

    private static ResponseStatusException notFound(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }

    private static ResponseStatusException conflict(String msg) {
        return new ResponseStatusException(HttpStatus.CONFLICT, msg);
    }

    private static void requireAuthenticated(CeremonyCaller caller) {
        if (caller == null || !caller.isAuthenticated()) {
            throw forbidden("Faça login para participar do mural.");
        }
    }

    private BrainstormingBoard requireBoard(String boardId) {
        return boardRepository.findById(boardId).orElseThrow(() -> notFound("Mural não encontrado."));
    }

    private BrainstormingBoard requireBoardForUpdate(String boardId) {
        return boardRepository.findByIdForUpdate(boardId).orElseThrow(() -> notFound("Mural não encontrado."));
    }

    private static boolean isFacilitator(BrainstormingBoard board, CeremonyCaller caller) {
        return caller != null && (caller.isAdmin() || caller.is(board.getCreatorId()));
    }

    private static void requireFacilitator(BrainstormingBoard board, CeremonyCaller caller) {
        if (!isFacilitator(board, caller)) {
            throw forbidden("Apenas o facilitador pode alterar a sessão.");
        }
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static String cleanText(String value, int max, String fieldLabel, boolean required) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty()) {
            if (required) {
                throw badRequest(fieldLabel + " é obrigatório.");
            }
            return null;
        }
        if (text.length() > max) {
            throw badRequest(fieldLabel + " excede " + max + " caracteres.");
        }
        return text;
    }

    // --- Mural ---

    @Transactional(readOnly = true)
    public Optional<BrainstormingBoard> getBoard(String id) {
        return boardRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public boolean boardExists(String id) {
        return boardRepository.existsById(id);
    }

    @Transactional(readOnly = true)
    public List<BrainstormingBoard> listBoards(String squadId) {
        return boardRepository.findByTeamIgnoreCaseOrderByCreatedAtDesc(squadId);
    }

    /**
     * POST /api/brainstormings: cria o mural (criador = quem chama, o creatorId do corpo é ignorado) ou, num mural
     * existente, aplica os campos enviados (só facilitador/ADMIN). O frontend novo usa {@link #patchBoard} para
     * mudar mural existente; esta rota segue aceita por compatibilidade com abas antigas.
     */
    @Transactional
    public Saved<BrainstormingBoard> saveOrUpdateBoard(BrainstormingBoard body, CeremonyCaller caller) {
        requireAuthenticated(caller);
        if (body == null) {
            throw badRequest("Corpo da requisição vazio.");
        }
        String id = body.getId() == null ? "" : body.getId().strip();
        if (id.length() > 100) {
            throw badRequest("Identificador inválido.");
        }
        if (!id.isEmpty()) {
            Optional<BrainstormingBoard> existing = boardRepository.findByIdForUpdate(id);
            if (existing.isPresent()) {
                BrainstormingBoard board = existing.get();
                requireFacilitator(board, caller);
                applyBoardFields(board, body.getTitle(), body.getPhase(), body.getTimer(), body.getSettings());
                BrainstormingBoard saved = boardRepository.save(board);
                publish(saved.getId(), "BOARD_UPDATED", saved);
                return new Saved<>(saved, false);
            }
        }
        BrainstormingBoard board = BrainstormingBoard.builder()
                .id(id.isEmpty() ? UUID.randomUUID().toString() : id)
                .creatorId(caller.id())
                .title(cleanText(body.getTitle(), MAX_TITLE, "O título", true))
                .team(cleanText(body.getTeam(), MAX_TITLE, "A squad", false))
                .createdAt(now())
                .phase("ideation")
                .settings(body.getSettings() != null && body.getSettings().isObject() ? sanitizeSettings(defaultSettings(), body.getSettings()) : defaultSettings())
                .timer(body.getTimer() != null && !body.getTimer().isNull() ? validTimer(body.getTimer()) : defaultTimer())
                .participantIds(JsonNodeFactory.instance.arrayNode().add(caller.id()))
                .build();
        if (body.getPhase() != null) {
            board.setPhase(validPhase(body.getPhase()));
        }
        BrainstormingBoard saved = boardRepository.save(board);
        publish(saved.getId(), "BOARD_UPDATED", saved);
        return new Saved<>(saved, true);
    }

    /** PATCH: só os campos enviados mudam; as configurações são mescladas chave a chave. Facilitador/ADMIN. */
    @Transactional
    public BrainstormingBoard patchBoard(String boardId, JsonNode patch, CeremonyCaller caller) {
        requireAuthenticated(caller);
        BrainstormingBoard board = requireBoardForUpdate(boardId);
        requireFacilitator(board, caller);
        if (patch == null || !patch.isObject()) {
            throw badRequest("Corpo da requisição inválido.");
        }
        applyBoardFields(board,
                patch.has("title") ? patch.get("title").asText(null) : null,
                patch.has("phase") ? patch.get("phase").asText(null) : null,
                patch.has("timer") ? patch.get("timer") : null,
                patch.has("settings") ? patch.get("settings") : null);
        BrainstormingBoard saved = boardRepository.save(board);
        publish(saved.getId(), "BOARD_UPDATED", saved);
        return saved;
    }

    private void applyBoardFields(BrainstormingBoard board, String title, String phase, JsonNode timer, JsonNode settings) {
        if (title != null && !title.isBlank()) {
            board.setTitle(cleanText(title, MAX_TITLE, "O título", true));
        }
        if (phase != null && !phase.isBlank()) {
            board.setPhase(validPhase(phase));
        }
        if (timer != null && !timer.isNull()) {
            board.setTimer(validTimer(timer));
        }
        if (settings != null && settings.isObject()) {
            ObjectNode merged = board.getSettings() != null && board.getSettings().isObject()
                    ? ((ObjectNode) board.getSettings()).deepCopy()
                    : JsonNodeFactory.instance.objectNode();
            board.setSettings(sanitizeSettings(merged, settings));
        }
    }

    private static String validPhase(String phase) {
        if (!PHASES.contains(phase)) {
            throw badRequest("Fase inválida.");
        }
        return phase;
    }

    private static ObjectNode defaultSettings() {
        ObjectNode settings = JsonNodeFactory.instance.objectNode();
        settings.put("isAnonymous", false);
        return settings;
    }

    private static ObjectNode defaultTimer() {
        ObjectNode timer = JsonNodeFactory.instance.objectNode();
        timer.put("status", "stopped");
        timer.putNull("endTime");
        timer.put("initialDuration", 600);
        timer.put("remainingOnPause", 600);
        return timer;
    }

    /** Copia só as chaves conhecidas (booleanas) por cima de {@code base}. */
    private static ObjectNode sanitizeSettings(ObjectNode base, JsonNode incoming) {
        Iterator<Map.Entry<String, JsonNode>> fields = incoming.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (SETTING_KEYS.contains(field.getKey()) && field.getValue().isBoolean()) {
                base.set(field.getKey(), field.getValue());
            }
        }
        return base;
    }

    private static ObjectNode validTimer(JsonNode timer) {
        if (!timer.isObject()) {
            throw badRequest("Timer inválido.");
        }
        String status = timer.path("status").asText("");
        if (!TIMER_STATUSES.contains(status)) {
            throw badRequest("Estado do timer inválido.");
        }
        long initial = timer.path("initialDuration").asLong(-1);
        long remaining = timer.path("remainingOnPause").asLong(initial);
        if (initial < 1 || initial > 86_400 || remaining < 0 || remaining > 86_400) {
            throw badRequest("Duração do timer inválida.");
        }
        ObjectNode clean = JsonNodeFactory.instance.objectNode();
        clean.put("status", status);
        if (timer.path("endTime").isNumber()) {
            clean.put("endTime", timer.get("endTime").asLong());
        } else {
            clean.putNull("endTime");
        }
        clean.put("initialDuration", initial);
        clean.put("remainingOnPause", remaining);
        return clean;
    }

    @Transactional
    public void deleteBoard(String id, CeremonyCaller caller) {
        requireAuthenticated(caller);
        BrainstormingBoard board = requireBoardForUpdate(id);
        requireFacilitator(board, caller);
        ideaRepository.deleteByBoardId(id);
        groupRepository.deleteByBoardId(id);
        participantRepository.deleteByBoardId(id);
        boardRepository.deleteById(id);
        publish(id, "BOARD_DELETED", Map.of("boardId", id));
    }

    // --- Participantes ---

    @Transactional(readOnly = true)
    public List<BrainstormingParticipant> getParticipants(String boardId) {
        return participantRepository.findByBoardIdOrderByNicknameAsc(boardId);
    }

    /** Entrada na sala: o id do participante é sempre o de quem chama (o corpo só dá apelido e papel). */
    @Transactional
    public BrainstormingParticipant joinBoard(String boardId, BrainstormingParticipant body, CeremonyCaller caller) {
        requireAuthenticated(caller);
        BrainstormingBoard board = requireBoard(boardId);
        String nickname = body == null ? null : cleanText(body.getNickname(), MAX_NICKNAME, "O apelido", false);
        String role = body == null ? null : cleanText(body.getRole(), MAX_SHORT, "O papel", false);
        BrainstormingParticipant participant = participantRepository.findByBoardIdAndId(boardId, caller.id())
                .orElseGet(() -> BrainstormingParticipant.builder()
                        .dbId(boardId + "_" + caller.id())
                        .id(caller.id())
                        .boardId(boardId)
                        .build());
        participant.setNickname(nickname != null ? nickname : participant.getNickname() != null ? participant.getNickname() : "Participante");
        if (role != null) {
            participant.setRole(role);
        }
        participant.setIsCreator(caller.is(board.getCreatorId()));
        participant.setLastActive(now());
        BrainstormingParticipant saved = participantRepository.save(participant);
        publish(boardId, "PARTICIPANT_JOINED", saved);
        return saved;
    }

    /** Sai da sala: a própria pessoa, o facilitador ou ADMIN. */
    @Transactional
    public void leaveBoard(String boardId, String userId, CeremonyCaller caller) {
        requireAuthenticated(caller);
        BrainstormingBoard board = requireBoard(boardId);
        if (!caller.is(userId)) {
            requireFacilitator(board, caller);
        }
        participantRepository.deleteByBoardIdAndId(boardId, userId);
        publish(boardId, "PARTICIPANT_LEFT", Map.of("userId", userId));
    }

    // --- Ideias ---

    @Transactional(readOnly = true)
    public List<BrainstormingIdea> getIdeas(String boardId) {
        return ideaRepository.findByBoardId(boardId);
    }

    private BrainstormingIdea requireIdeaForUpdate(String boardId, String ideaId) {
        BrainstormingIdea idea = ideaRepository.findByIdForUpdate(ideaId)
                .orElseThrow(() -> notFound("Ideia não encontrada."));
        if (!boardId.equals(idea.getBoardId())) {
            throw notFound("Ideia não encontrada.");
        }
        return idea;
    }

    /**
     * POST /ideas. Ideia nova: o autor é quem chama, votos começam vazios. Ideia existente (abas antigas gravam o
     * objeto inteiro): só conteúdo, posição, grupo, ligação e qualificadores mudam; votos, autor e mural ficam como
     * estão no servidor.
     */
    @Transactional
    public Saved<BrainstormingIdea> saveOrUpdateIdea(String boardId, BrainstormingIdea body, CeremonyCaller caller) {
        requireAuthenticated(caller);
        requireBoard(boardId);
        if (body == null) {
            throw badRequest("Corpo da requisição vazio.");
        }
        String id = body.getId() == null ? "" : body.getId().strip();
        if (id.length() > 100) {
            throw badRequest("Identificador inválido.");
        }
        if (!id.isEmpty()) {
            Optional<BrainstormingIdea> existing = ideaRepository.findByIdForUpdate(id);
            if (existing.isPresent()) {
                BrainstormingIdea idea = existing.get();
                if (!boardId.equals(idea.getBoardId())) {
                    throw conflict("Esta ideia pertence a outro mural.");
                }
                if (body.getContent() != null && !body.getContent().isBlank()) {
                    idea.setContent(cleanText(body.getContent(), MAX_CONTENT, "O texto da ideia", true));
                }
                idea.setPosition(validPosition(body.getPosition(), idea.getPosition()));
                idea.setQualifiers(validQualifiers(body.getQualifiers()));
                idea.setGroupId(validGroup(boardId, body.getGroupId()));
                idea.setParentId(validParent(boardId, idea.getId(), body.getParentId()));
                if (body.getColor() != null) {
                    idea.setColor(cleanText(body.getColor(), MAX_SHORT, "A cor", false));
                }
                BrainstormingIdea saved = ideaRepository.save(idea);
                publish(boardId, "IDEA_SAVED", saved);
                return new Saved<>(saved, false);
            }
        }
        BrainstormingIdea idea = BrainstormingIdea.builder()
                .id(id.isEmpty() ? UUID.randomUUID().toString() : id)
                .boardId(boardId)
                .content(cleanText(body.getContent(), MAX_CONTENT, "O texto da ideia", true))
                .authorId(caller.id())
                .createdAt(now())
                .votes(JsonNodeFactory.instance.arrayNode())
                .position(validPosition(body.getPosition(), null))
                .qualifiers(validQualifiers(body.getQualifiers()))
                .groupId(validGroup(boardId, body.getGroupId()))
                .parentId(null)
                .color(cleanText(body.getColor(), MAX_SHORT, "A cor", false))
                .build();
        BrainstormingIdea saved = ideaRepository.save(idea);
        publish(boardId, "IDEA_SAVED", saved);
        return new Saved<>(saved, true);
    }

    /** PATCH de ideia: só os campos enviados mudam (ausente = não mexe; null explícito limpa grupo/ligação). */
    @Transactional
    public BrainstormingIdea patchIdea(String boardId, String ideaId, JsonNode patch, CeremonyCaller caller) {
        requireAuthenticated(caller);
        requireBoard(boardId);
        if (patch == null || !patch.isObject()) {
            throw badRequest("Corpo da requisição inválido.");
        }
        BrainstormingIdea idea = requireIdeaForUpdate(boardId, ideaId);
        if (patch.has("content")) {
            idea.setContent(cleanText(patch.get("content").asText(null), MAX_CONTENT, "O texto da ideia", true));
        }
        if (patch.has("position")) {
            idea.setPosition(validPosition(patch.get("position"), idea.getPosition()));
        }
        if (patch.has("qualifiers")) {
            idea.setQualifiers(validQualifiers(patch.get("qualifiers")));
        }
        if (patch.has("color")) {
            idea.setColor(cleanText(patch.get("color").asText(null), MAX_SHORT, "A cor", false));
        }
        if (patch.has("groupId")) {
            idea.setGroupId(validGroup(boardId, patch.get("groupId").isNull() ? null : patch.get("groupId").asText(null)));
        }
        if (patch.has("parentId")) {
            idea.setParentId(validParent(boardId, ideaId, patch.get("parentId").isNull() ? null : patch.get("parentId").asText(null)));
        }
        BrainstormingIdea saved = ideaRepository.save(idea);
        publish(boardId, "IDEA_SAVED", saved);
        return saved;
    }

    private static JsonNode validPosition(JsonNode position, JsonNode fallback) {
        if (position == null || position.isNull()) {
            return fallback != null ? fallback : defaultPosition();
        }
        if (!position.isObject() || !position.path("x").isNumber() || !position.path("y").isNumber()) {
            throw badRequest("Posição inválida.");
        }
        double x = position.get("x").asDouble();
        double y = position.get("y").asDouble();
        if (Math.abs(x) > 1_000_000 || Math.abs(y) > 1_000_000) {
            throw badRequest("Posição fora dos limites.");
        }
        ObjectNode clean = JsonNodeFactory.instance.objectNode();
        clean.put("x", x);
        clean.put("y", y);
        return clean;
    }

    private static JsonNode defaultPosition() {
        ObjectNode pos = JsonNodeFactory.instance.objectNode();
        pos.put("x", 100);
        pos.put("y", 100);
        return pos;
    }

    private static JsonNode validQualifiers(JsonNode qualifiers) {
        if (qualifiers == null || qualifiers.isNull()) {
            return null;
        }
        if (!qualifiers.isObject()) {
            throw badRequest("Qualificadores inválidos.");
        }
        ObjectNode clean = JsonNodeFactory.instance.objectNode();
        for (String key : List.of("roi", "effort")) {
            JsonNode value = qualifiers.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            if (!value.isNumber() || value.asDouble() < 0 || value.asDouble() > 100) {
                throw badRequest("ROI e esforço devem estar entre 0 e 100.");
            }
            clean.set(key, value);
        }
        return clean;
    }

    private String validGroup(String boardId, String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return null;
        }
        BrainstormingGroup group = groupRepository.findById(groupId).orElse(null);
        if (group == null || !boardId.equals(group.getBoardId())) {
            throw badRequest("Grupo não encontrado neste mural.");
        }
        return groupId;
    }

    /** A ligação precisa apontar para outra ideia do mesmo mural e não pode fechar um ciclo. */
    private String validParent(String boardId, String ideaId, String parentId) {
        if (parentId == null || parentId.isBlank()) {
            return null;
        }
        if (parentId.equals(ideaId)) {
            throw badRequest("Uma ideia não pode ser ligada a ela mesma.");
        }
        Set<String> seen = new HashSet<>();
        String cursor = parentId;
        while (cursor != null) {
            if (!seen.add(cursor)) {
                break;
            }
            BrainstormingIdea ancestor = ideaRepository.findById(cursor).orElse(null);
            if (ancestor == null || !boardId.equals(ancestor.getBoardId())) {
                throw badRequest("Ideia de destino não encontrada neste mural.");
            }
            if (ideaId.equals(ancestor.getParentId())) {
                throw badRequest("Essa ligação criaria um ciclo.");
            }
            cursor = ancestor.getParentId();
        }
        return parentId;
    }

    /** Liga/desliga o voto de quem chama, de forma atômica (lock na ideia): dois votos simultâneos não se perdem. */
    @Transactional
    public BrainstormingIdea toggleVote(String boardId, String ideaId, CeremonyCaller caller) {
        requireAuthenticated(caller);
        requireBoard(boardId);
        BrainstormingIdea idea = requireIdeaForUpdate(boardId, ideaId);
        ArrayNode votes = JsonNodeFactory.instance.arrayNode();
        boolean removed = false;
        if (idea.getVotes() != null && idea.getVotes().isArray()) {
            for (JsonNode vote : idea.getVotes()) {
                if (caller.id().equals(vote.asText())) {
                    removed = true;
                } else {
                    votes.add(vote);
                }
            }
        }
        if (!removed) {
            votes.add(caller.id());
        }
        idea.setVotes(votes);
        BrainstormingIdea saved = ideaRepository.save(idea);
        publish(boardId, "IDEA_SAVED", saved);
        return saved;
    }

    /**
     * Funde a ideia de origem na de destino numa só operação: texto vira linha da ideia de destino, votos são
     * unidos sem repetir quem votou nas duas, ideias ligadas à origem passam para o destino, e a origem some.
     */
    @Transactional
    public BrainstormingIdea mergeIdeas(String boardId, String targetId, String sourceId, CeremonyCaller caller) {
        requireAuthenticated(caller);
        requireBoard(boardId);
        if (targetId.equals(sourceId)) {
            throw badRequest("Escolha duas ideias diferentes para fundir.");
        }
        // Ordem fixa de lock: duas fusões cruzadas (A→B e B→A) não travam uma à outra.
        String lo = targetId.compareTo(sourceId) < 0 ? targetId : sourceId;
        String hi = lo.equals(targetId) ? sourceId : targetId;
        BrainstormingIdea loIdea = requireIdeaForUpdate(boardId, lo);
        BrainstormingIdea hiIdea = requireIdeaForUpdate(boardId, hi);
        BrainstormingIdea target = lo.equals(targetId) ? loIdea : hiIdea;
        BrainstormingIdea source = lo.equals(targetId) ? hiIdea : loIdea;

        String merged = target.getContent() + "\n- " + source.getContent();
        if (merged.length() > MAX_CONTENT) {
            throw badRequest("O texto fundido excederia " + MAX_CONTENT + " caracteres.");
        }
        target.setContent(merged);

        ArrayNode votes = JsonNodeFactory.instance.arrayNode();
        Set<String> seen = new HashSet<>();
        for (JsonNode list : new JsonNode[]{target.getVotes(), source.getVotes()}) {
            if (list != null && list.isArray()) {
                for (JsonNode vote : list) {
                    if (seen.add(vote.asText())) {
                        votes.add(vote);
                    }
                }
            }
        }
        target.setVotes(votes);

        if (sourceId.equals(target.getParentId())) {
            target.setParentId(targetId.equals(source.getParentId()) ? null : source.getParentId());
        }

        List<BrainstormingIdea> reparented = new ArrayList<>();
        for (BrainstormingIdea child : ideaRepository.findByBoardIdAndParentId(boardId, sourceId)) {
            if (!child.getId().equals(targetId)) {
                child.setParentId(targetId);
                reparented.add(ideaRepository.save(child));
            }
        }
        BrainstormingIdea saved = ideaRepository.save(target);
        ideaRepository.deleteById(sourceId);

        publish(boardId, "IDEA_SAVED", saved);
        for (BrainstormingIdea child : reparented) {
            publish(boardId, "IDEA_SAVED", child);
        }
        publish(boardId, "IDEA_DELETED", Map.of("ideaId", sourceId));
        return saved;
    }

    /** Apaga uma ideia: o autor, o facilitador ou ADMIN. Ideias ligadas a ela perdem só a ligação. */
    @Transactional
    public void deleteIdea(String boardId, String ideaId, CeremonyCaller caller) {
        requireAuthenticated(caller);
        BrainstormingBoard board = requireBoard(boardId);
        BrainstormingIdea idea = requireIdeaForUpdate(boardId, ideaId);
        if (!caller.is(idea.getAuthorId()) && !isFacilitator(board, caller)) {
            throw forbidden("Só o autor da ideia ou o facilitador podem apagá-la.");
        }
        List<BrainstormingIdea> detached = new ArrayList<>();
        for (BrainstormingIdea child : ideaRepository.findByBoardIdAndParentId(boardId, ideaId)) {
            child.setParentId(null);
            detached.add(ideaRepository.save(child));
        }
        ideaRepository.deleteById(ideaId);
        for (BrainstormingIdea child : detached) {
            publish(boardId, "IDEA_SAVED", child);
        }
        publish(boardId, "IDEA_DELETED", Map.of("ideaId", ideaId));
    }

    // --- Grupos ---

    @Transactional(readOnly = true)
    public List<BrainstormingGroup> getGroups(String boardId) {
        return groupRepository.findByBoardIdOrderByOrderAsc(boardId);
    }

    @Transactional
    public Saved<BrainstormingGroup> saveOrUpdateGroup(String boardId, BrainstormingGroup body, CeremonyCaller caller) {
        requireAuthenticated(caller);
        requireBoard(boardId);
        if (body == null) {
            throw badRequest("Corpo da requisição vazio.");
        }
        String id = body.getId() == null ? "" : body.getId().strip();
        BrainstormingGroup group = id.isEmpty() ? null : groupRepository.findById(id).orElse(null);
        boolean created = group == null;
        if (created) {
            group = BrainstormingGroup.builder()
                    .id(id.isEmpty() ? UUID.randomUUID().toString() : id)
                    .boardId(boardId)
                    .createdAt(now())
                    .order(body.getOrder() != null ? body.getOrder() : groupRepository.findByBoardIdOrderByOrderAsc(boardId).size())
                    .build();
        } else if (!boardId.equals(group.getBoardId())) {
            throw conflict("Este grupo pertence a outro mural.");
        }
        group.setTitle(cleanText(body.getTitle(), MAX_TITLE, "O nome do grupo", true));
        if (!created && body.getOrder() != null) {
            group.setOrder(body.getOrder());
        }
        if (body.getColor() != null) {
            group.setColor(cleanText(body.getColor(), MAX_SHORT, "A cor", false));
        }
        BrainstormingGroup saved = groupRepository.save(group);
        publish(boardId, "GROUP_SAVED", saved);
        return new Saved<>(saved, created);
    }

    /** Apaga o grupo e devolve as ideias dele a "Sem grupo" (cada ideia solta é publicada). */
    @Transactional
    public void deleteGroup(String boardId, String groupId, CeremonyCaller caller) {
        requireAuthenticated(caller);
        requireBoard(boardId);
        BrainstormingGroup group = groupRepository.findById(groupId).orElseThrow(() -> notFound("Grupo não encontrado."));
        if (!boardId.equals(group.getBoardId())) {
            throw notFound("Grupo não encontrado.");
        }
        List<BrainstormingIdea> released = new ArrayList<>();
        for (BrainstormingIdea idea : ideaRepository.findByBoardIdAndGroupId(boardId, groupId)) {
            idea.setGroupId(null);
            released.add(ideaRepository.save(idea));
        }
        groupRepository.deleteById(groupId);
        for (BrainstormingIdea idea : released) {
            publish(boardId, "IDEA_SAVED", idea);
        }
        publish(boardId, "GROUP_DELETED", Map.of("groupId", groupId));
    }
}
