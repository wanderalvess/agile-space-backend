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
import com.agilespace.backend.websocket.PresenceModule;
import com.agilespace.backend.websocket.PresenceRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
 * <p>Cerimônia (V80, docs/ceremony-brainstorming-api.md): o mural pode ser encerrado (escritas viram 409), a facilitação pode
 * mudar de mãos (assumir se o facilitador está offline; passar), o voto respeita {@code votesPerParticipant} (contado com lock no
 * mural) e a ideia guarda quando/por quem o texto foi editado. Tudo opt-in/aditivo: sem os campos novos o comportamento é o antigo.
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
    static final Set<String> SETTING_KEYS = Set.of("isAnonymous", "isPresentationMode", "isRevealed", "restrictEdits");
    static final int MAX_VOTES_PER_PARTICIPANT = 100;

    private final BrainstormingBoardRepository boardRepository;
    private final BrainstormingIdeaRepository ideaRepository;
    private final BrainstormingGroupRepository groupRepository;
    private final BrainstormingParticipantRepository participantRepository;
    private final BrainstormingWebSocketHandler webSocketHandler;
    private final PresenceRegistry presenceRegistry;

    /** Quanto tempo o facilitador precisa estar offline para outro participante assumir a facilitação. */
    @Value("${app.brainstorming.facilitator-offline-seconds:60}")
    private long facilitatorOfflineSeconds = 60;

    /** Resultado de um upsert: {@code created} separa 201 de 200. */
    public record Saved<T>(T value, boolean created) {
    }

    // --- Utilidades ---

    private void publish(String boardId, String eventType, Object payload) {
        AfterCommit.run(() -> webSocketHandler.broadcastEvent(boardId, eventType, payload));
    }

    /** Ideia publicada por destinatário: cada sessão recebe a versão que a pessoa pode ver (ver BrainstormingRedaction). */
    private void publishIdea(String boardId, BrainstormingIdea idea) {
        BrainstormingBoard board = boardRepository.findById(boardId).orElse(null);
        nameAuthors(board, List.of(idea));
        AfterCommit.run(() -> webSocketHandler.broadcastEventPerUser(boardId, "IDEA_SAVED",
                (userId, role) -> BrainstormingRedaction.redact(idea, board, new CeremonyCaller(userId, role))));
    }

    /** A ideia como {@code caller} pode vê-la (respostas REST). */
    @Transactional(readOnly = true)
    public BrainstormingIdea viewIdea(String boardId, BrainstormingIdea idea, CeremonyCaller caller) {
        BrainstormingBoard board = boardRepository.findById(boardId).orElse(null);
        nameAuthors(board, List.of(idea));
        return BrainstormingRedaction.redact(idea, board, caller);
    }

    /**
     * Preenche {@code authorName}/{@code editedByName} (só na saída) com os apelidos da lista de participantes. Mural
     * anônimo não recebe nome nenhum (nem consulta a lista); a redação por destinatário ainda os remove de ideias ocultas.
     */
    private void nameAuthors(BrainstormingBoard board, java.util.Collection<BrainstormingIdea> ideas) {
        if (board == null || ideas.isEmpty() || BrainstormingRedaction.authorsHidden(board)) {
            return;
        }
        Map<String, String> names = new HashMap<>();
        for (BrainstormingParticipant p : participantRepository.findByBoardIdOrderByNicknameAsc(board.getId())) {
            names.put(p.getId(), p.getNickname());
        }
        for (BrainstormingIdea idea : ideas) {
            idea.setAuthorName(idea.getAuthorId() == null ? null : names.get(idea.getAuthorId()));
            idea.setEditedByName(idea.getEditedBy() == null ? null : names.get(idea.getEditedBy()));
        }
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

    /** O facilitador ATUAL (criador até alguém assumir/receber a facilitação) ou ADMIN. */
    private static boolean isFacilitator(BrainstormingBoard board, CeremonyCaller caller) {
        return BrainstormingRedaction.isFacilitator(board, caller);
    }

    /** Encerrar, reabrir e retomar a facilitação também são do criador do mural, mesmo depois de passá-la. */
    private static boolean isOwnerOrFacilitator(BrainstormingBoard board, CeremonyCaller caller) {
        return isFacilitator(board, caller) || (caller != null && caller.is(board.getCreatorId()));
    }

    private static void requireOpen(BrainstormingBoard board) {
        if (board.isFinished()) {
            throw conflict("Este brainstorming foi encerrado. Peça ao facilitador para reabri-lo.");
        }
    }

    private static void requireFacilitator(BrainstormingBoard board, CeremonyCaller caller) {
        if (!isFacilitator(board, caller)) {
            throw forbidden("Apenas o facilitador pode alterar a sessão.");
        }
    }

    private static String now() {
        return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString();
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

    /** Preenche o nome do facilitador (só na saída) com o apelido dele na lista de participantes; nulo se não consta. */
    private BrainstormingBoard named(BrainstormingBoard board) {
        if (board != null) {
            board.setFacilitatorName(participantRepository.findByBoardIdAndId(board.getId(), board.getFacilitatorId())
                    .map(BrainstormingParticipant::getNickname).orElse(null));
        }
        return board;
    }

    @Transactional(readOnly = true)
    public Optional<BrainstormingBoard> getBoard(String id) {
        return boardRepository.findById(id).map(this::named);
    }

    @Transactional(readOnly = true)
    public boolean boardExists(String id) {
        return boardRepository.existsById(id);
    }

    @Transactional(readOnly = true)
    public List<BrainstormingBoard> listBoards(String squadId) {
        List<BrainstormingBoard> boards = boardRepository.findByTeamIgnoreCaseOrderByCreatedAtDesc(squadId);
        if (boards == null || boards.isEmpty()) {
            return boards;
        }
        Map<String, String> nicknames = new HashMap<>();
        for (BrainstormingParticipant p : participantRepository.findAllById(
                boards.stream().map(b -> b.getId() + "_" + b.getFacilitatorId()).toList())) {
            nicknames.put(p.getDbId(), p.getNickname());
        }
        boards.forEach(b -> b.setFacilitatorName(nicknames.get(b.getId() + "_" + b.getFacilitatorId())));
        return boards;
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
                if (board.isFinished() && (body.getPhase() != null || isPresent(body.getTimer()) || isPresent(body.getSettings())
                        || body.getVotesPerParticipant() != null)) {
                    throw conflict("Este brainstorming foi encerrado. Reabra para alterar a sessão.");
                }
                applyBoardFields(board, body.getTitle(), body.getPhase(), body.getTimer(), body.getSettings());
                if (body.getVotesPerParticipant() != null) {
                    board.setVotesPerParticipant(validVotesLimit(body.getVotesPerParticipant()));
                }
                BrainstormingBoard saved = boardRepository.save(board);
                named(saved);
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
                .status(BrainstormingBoard.OPEN)
                .votesPerParticipant(body.getVotesPerParticipant() == null ? null : validVotesLimit(body.getVotesPerParticipant()))
                .build();
        if (body.getPhase() != null) {
            board.setPhase(validPhase(body.getPhase()));
        }
        BrainstormingBoard saved = boardRepository.save(board);
        named(saved);
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
        if (board.isFinished() && (patch.hasNonNull("phase") || patch.hasNonNull("timer") || patch.hasNonNull("settings")
                || patch.has("votesPerParticipant"))) {
            throw conflict("Este brainstorming foi encerrado. Reabra para alterar a sessão.");
        }
        applyBoardFields(board,
                patch.has("title") ? patch.get("title").asText(null) : null,
                patch.has("phase") ? patch.get("phase").asText(null) : null,
                patch.has("timer") ? patch.get("timer") : null,
                patch.has("settings") ? patch.get("settings") : null);
        if (patch.has("votesPerParticipant")) {
            JsonNode limit = patch.get("votesPerParticipant");
            if (limit.isNull()) {
                board.setVotesPerParticipant(null);
            } else {
                if (!limit.isIntegralNumber()) {
                    throw badRequest(VOTES_LIMIT_MESSAGE);
                }
                board.setVotesPerParticipant(validVotesLimit(limit.asInt()));
            }
        }
        BrainstormingBoard saved = boardRepository.save(board);
        named(saved);
        publish(saved.getId(), "BOARD_UPDATED", saved);
        return saved;
    }

    private static boolean isPresent(JsonNode node) {
        return node != null && !node.isNull();
    }

    private static final String VOTES_LIMIT_MESSAGE = "O limite de votos deve ser um número inteiro entre 1 e " + MAX_VOTES_PER_PARTICIPANT + ".";

    private static Integer validVotesLimit(Integer limit) {
        if (limit < 1 || limit > MAX_VOTES_PER_PARTICIPANT) {
            throw badRequest(VOTES_LIMIT_MESSAGE);
        }
        return limit;
    }

    /** Encerra o mural: leitura segue livre, escritas viram 409. Facilitador, criador ou ADMIN; idempotente. */
    @Transactional
    public BrainstormingBoard finishBoard(String boardId, CeremonyCaller caller) {
        requireAuthenticated(caller);
        BrainstormingBoard board = requireBoardForUpdate(boardId);
        if (!isOwnerOrFacilitator(board, caller)) {
            throw forbidden("Apenas o facilitador pode encerrar o brainstorming.");
        }
        if (board.isFinished()) {
            return named(board);
        }
        board.setStatus(BrainstormingBoard.FINISHED);
        board.setFinishedAt(now());
        BrainstormingBoard saved = boardRepository.save(board);
        named(saved);
        log.info("Brainstorming encerrado board={} by={}", boardId, caller.id());
        publish(boardId, "BOARD_UPDATED", saved);
        return saved;
    }

    /** Reabre um mural encerrado. Mesma permissão de {@link #finishBoard}; idempotente. */
    @Transactional
    public BrainstormingBoard reopenBoard(String boardId, CeremonyCaller caller) {
        requireAuthenticated(caller);
        BrainstormingBoard board = requireBoardForUpdate(boardId);
        if (!isOwnerOrFacilitator(board, caller)) {
            throw forbidden("Apenas o facilitador pode reabrir o brainstorming.");
        }
        if (!board.isFinished()) {
            return named(board);
        }
        board.setStatus(BrainstormingBoard.OPEN);
        board.setFinishedAt(null);
        BrainstormingBoard saved = boardRepository.save(board);
        named(saved);
        log.info("Brainstorming reaberto board={} by={}", boardId, caller.id());
        publish(boardId, "BOARD_UPDATED", saved);
        return saved;
    }

    // --- Facilitação ---

    /**
     * Assume a facilitação. O criador e ADMIN assumem sempre; um participante só se o facilitador atual está offline (sem
     * conexão WebSocket na sala) há mais de {@code app.brainstorming.facilitator-offline-seconds}.
     */
    @Transactional
    public BrainstormingBoard claimFacilitation(String boardId, CeremonyCaller caller) {
        requireAuthenticated(caller);
        BrainstormingBoard board = requireBoardForUpdate(boardId);
        if (caller.is(board.getFacilitatorId())) {
            return named(board);
        }
        if (!caller.isAdmin() && !caller.is(board.getCreatorId())) {
            if (participantRepository.findByBoardIdAndId(boardId, caller.id()).isEmpty()) {
                throw forbidden("Entre na sala para assumir a facilitação.");
            }
            Optional<Duration> offline = presenceRegistry == null ? Optional.empty()
                    : presenceRegistry.offlineFor(PresenceModule.BRAINSTORMING, boardId, board.getFacilitatorId());
            if (offline.isEmpty()) {
                throw conflict("O facilitador está online.");
            }
            if (offline.get().compareTo(Duration.ofSeconds(facilitatorOfflineSeconds)) <= 0) {
                throw conflict("O facilitador saiu há pouco. Tente de novo em instantes.");
            }
        }
        return changeFacilitator(board, caller.id(), caller, "claim");
    }

    /** Passa a facilitação a outra pessoa da sala. Só o facilitador atual ou ADMIN. */
    @Transactional
    public BrainstormingBoard transferFacilitation(String boardId, String targetUserId, CeremonyCaller caller) {
        requireAuthenticated(caller);
        BrainstormingBoard board = requireBoardForUpdate(boardId);
        if (!isFacilitator(board, caller)) {
            throw forbidden("Apenas o facilitador pode passar a facilitação.");
        }
        String target = targetUserId == null ? "" : targetUserId.strip();
        if (target.isEmpty()) {
            throw badRequest("Informe a pessoa que vai facilitar.");
        }
        if (target.equals(board.getFacilitatorId())) {
            return named(board);
        }
        if (participantRepository.findByBoardIdAndId(boardId, target).isEmpty()) {
            throw notFound("Essa pessoa não está nesta sala.");
        }
        return changeFacilitator(board, target, caller, "transfer");
    }

    private BrainstormingBoard changeFacilitator(BrainstormingBoard board, String newFacilitatorId, CeremonyCaller caller, String how) {
        String previous = board.getFacilitatorId();
        board.setFacilitatorId(newFacilitatorId);
        BrainstormingBoard saved = boardRepository.save(board);
        named(saved);
        log.info("Facilitação {} board={} by={} from={} to={}", how, board.getId(), caller.id(), previous, newFacilitatorId);
        publish(board.getId(), "BOARD_UPDATED", saved);
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
        String facilitatorId = boardRepository.findById(boardId).map(BrainstormingBoard::getFacilitatorId).orElse(null);
        List<BrainstormingParticipant> participants = participantRepository.findByBoardIdOrderByNicknameAsc(boardId);
        participants.forEach(p -> describe(p, facilitatorId));
        return participants;
    }

    /** Campos só de saída: nome para exibir e se é o facilitador atual. */
    private static BrainstormingParticipant describe(BrainstormingParticipant participant, String facilitatorId) {
        participant.setDisplayName(participant.getNickname());
        participant.setFacilitator(facilitatorId != null && facilitatorId.equals(participant.getId()));
        return participant;
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
        BrainstormingParticipant saved = describe(participantRepository.save(participant), board.getFacilitatorId());
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
    public List<BrainstormingIdea> getIdeas(String boardId, CeremonyCaller caller) {
        BrainstormingBoard board = boardRepository.findById(boardId).orElse(null);
        List<BrainstormingIdea> ideas = ideaRepository.findByBoardId(boardId);
        nameAuthors(board, ideas);
        return ideas.stream()
                .map(idea -> BrainstormingRedaction.redact(idea, board, caller))
                .toList();
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
        BrainstormingBoard board = requireBoard(boardId);
        requireOpen(board);
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
                    editContent(board, idea, cleanText(body.getContent(), MAX_CONTENT, "O texto da ideia", true), caller);
                }
                idea.setPosition(validPosition(body.getPosition(), idea.getPosition()));
                idea.setQualifiers(validQualifiers(body.getQualifiers()));
                idea.setGroupId(validGroup(boardId, body.getGroupId()));
                idea.setParentId(validParent(boardId, idea.getId(), body.getParentId()));
                if (body.getColor() != null) {
                    idea.setColor(cleanText(body.getColor(), MAX_SHORT, "A cor", false));
                }
                BrainstormingIdea saved = ideaRepository.save(idea);
                publishIdea(boardId, saved);
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
        publishIdea(boardId, saved);
        return new Saved<>(saved, true);
    }

    /** PATCH de ideia: só os campos enviados mudam (ausente = não mexe; null explícito limpa grupo/ligação). */
    @Transactional
    public BrainstormingIdea patchIdea(String boardId, String ideaId, JsonNode patch, CeremonyCaller caller) {
        requireAuthenticated(caller);
        BrainstormingBoard board = requireBoard(boardId);
        requireOpen(board);
        if (patch == null || !patch.isObject()) {
            throw badRequest("Corpo da requisição inválido.");
        }
        BrainstormingIdea idea = requireIdeaForUpdate(boardId, ideaId);
        if (patch.has("content")) {
            editContent(board, idea, cleanText(patch.get("content").asText(null), MAX_CONTENT, "O texto da ideia", true), caller);
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
        publishIdea(boardId, saved);
        return saved;
    }

    /**
     * Troca o texto da ideia. Só quando o texto realmente muda (abas antigas regravam o objeto inteiro com o texto de
     * sempre): aplica a restrição opcional {@code settings.restrictEdits} (só autor, facilitador atual ou ADMIN) e grava
     * {@code editedAt}/{@code editedBy} com o userId do token. Mover, agrupar, ligar e priorizar não passam por aqui.
     */
    private void editContent(BrainstormingBoard board, BrainstormingIdea idea, String content, CeremonyCaller caller) {
        if (content.equals(idea.getContent())) {
            return;
        }
        boolean restricted = board.getSettings() != null && board.getSettings().path("restrictEdits").asBoolean(false);
        if (restricted && !caller.is(idea.getAuthorId()) && !isFacilitator(board, caller)) {
            throw forbidden("Só o autor da ideia ou o facilitador podem editar o texto.");
        }
        idea.setContent(content);
        idea.setEditedAt(now());
        idea.setEditedBy(caller.id());
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
        BrainstormingBoard board = requireBoard(boardId);
        requireOpen(board);
        if (board.getVotesPerParticipant() != null) {
            // Com limite, a conta da pessoa atravessa ideias: serializa no mural (sempre mural antes de ideia, nunca ao contrário)
            // para dois votos simultâneos dela em ideias diferentes não passarem do limite.
            board = requireBoardForUpdate(boardId);
            requireOpen(board);
        }
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
            Integer limit = board.getVotesPerParticipant();
            if (limit != null && votesUsedBy(boardId, caller.id()) >= limit) {
                throw conflict("Você já usou " + (limit == 1 ? "seu voto" : "seus " + limit + " votos")
                        + ". Remova um voto para votar em outra ideia.");
            }
            votes.add(caller.id());
        }
        idea.setVotes(votes);
        BrainstormingIdea saved = ideaRepository.save(idea);
        publishIdea(boardId, saved);
        return saved;
    }

    /** Em quantas ideias do mural a pessoa já votou (conta o que está no servidor, inclusive ideias ocultas). */
    private long votesUsedBy(String boardId, String userId) {
        long used = 0;
        for (BrainstormingIdea other : ideaRepository.findByBoardId(boardId)) {
            JsonNode votes = other.getVotes();
            if (votes != null && votes.isArray()) {
                for (JsonNode vote : votes) {
                    if (userId.equals(vote.asText())) {
                        used++;
                        break;
                    }
                }
            }
        }
        return used;
    }

    /**
     * Funde a ideia de origem na de destino numa só operação: texto vira linha da ideia de destino, votos são
     * unidos sem repetir quem votou nas duas, ideias ligadas à origem passam para o destino, e a origem some.
     */
    @Transactional
    public BrainstormingIdea mergeIdeas(String boardId, String targetId, String sourceId, CeremonyCaller caller) {
        requireAuthenticated(caller);
        requireOpen(requireBoard(boardId));
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
        // Juntar ideias é um fluxo legítimo sobre ideia alheia (vale mesmo com restrictEdits); só fica registrado quem juntou.
        target.setEditedAt(now());
        target.setEditedBy(caller.id());

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

        publishIdea(boardId, saved);
        for (BrainstormingIdea child : reparented) {
            publishIdea(boardId, child);
        }
        publish(boardId, "IDEA_DELETED", Map.of("ideaId", sourceId));
        return saved;
    }

    /** Apaga uma ideia: o autor, o facilitador ou ADMIN. Ideias ligadas a ela perdem só a ligação. */
    @Transactional
    public void deleteIdea(String boardId, String ideaId, CeremonyCaller caller) {
        requireAuthenticated(caller);
        BrainstormingBoard board = requireBoard(boardId);
        requireOpen(board);
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
            publishIdea(boardId, child);
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
        requireOpen(requireBoard(boardId));
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
        requireOpen(requireBoard(boardId));
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
            publishIdea(boardId, idea);
        }
        publish(boardId, "GROUP_DELETED", Map.of("groupId", groupId));
    }
}
