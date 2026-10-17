package com.agilespace.backend.service;

import com.agilespace.backend.domain.HealthCheckBoard;
import com.agilespace.backend.domain.HealthCheckParticipant;
import com.agilespace.backend.domain.HealthCheckVote;
import com.agilespace.backend.repository.HealthCheckBoardRepository;
import com.agilespace.backend.repository.HealthCheckParticipantRepository;
import com.agilespace.backend.repository.HealthCheckVoteRepository;
import com.agilespace.backend.websocket.HealthCheckWebSocketHandler;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Regras do Radar de Saúde. Decisões:
 * <ul>
 *   <li>A identidade vem do JWT: o voto é sempre de quem chama (nunca do {@code participantId} do corpo).</li>
 *   <li>Anonimato de verdade: enquanto a coleta está aberta cada pessoa só enxerga os PRÓPRIOS votos; depois do
 *       encerramento os votos saem sem identificar quem votou (id e papel do votante são removidos).</li>
 *   <li>O resumo é calculado aqui, no encerramento, dentro do lock do board: nenhum voto entra depois dele.</li>
 *   <li>O link da sala dá acesso (qualquer autenticado entra e vota); só o criador/ADMIN encerra e apaga.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HealthCheckService {

    static final int MAX_TITLE = 255;
    static final int MAX_DIMENSIONS = 30;
    static final int MAX_COMMENT = 2000;
    static final int MAX_NICKNAME = 100;
    static final Set<String> SCALES = Set.of("traffic_light", "numbers_5", "emojis");
    static final Set<String> TEAM_ROLES = Set.of("AM", "PO", "PL", "DEV", "QA", "UX", "SME", "OUTRO");
    static final String STATUS_COLLECTING = "collecting";
    static final String STATUS_FINISHED = "finished";
    static final Pattern STAGE_FORMAT = Pattern.compile("[A-Za-z0-9_.-]{1,40}");

    private final HealthCheckBoardRepository boardRepository;
    private final HealthCheckParticipantRepository participantRepository;
    private final HealthCheckVoteRepository voteRepository;
    private final HealthCheckWebSocketHandler webSocketHandler;

    // --- Utilidades ---

    private void publish(String boardId, String eventType, Object payload) {
        AfterCommit.run(() -> webSocketHandler.broadcastEvent(boardId, eventType, payload));
    }

    /** O voto (com comentário) só chega às sessões de quem votou. */
    private void publishToUser(String boardId, String eventType, Object payload, String userId) {
        AfterCommit.run(() -> webSocketHandler.broadcastEventToUsers(boardId, eventType, payload, Set.of(userId)));
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
            throw forbidden("Faça login para participar do radar.");
        }
    }

    private HealthCheckBoard requireBoard(String boardId) {
        return boardRepository.findById(boardId).orElseThrow(() -> notFound("Radar não encontrado."));
    }

    private HealthCheckBoard requireBoardForUpdate(String boardId) {
        return boardRepository.findByIdForUpdate(boardId).orElseThrow(() -> notFound("Radar não encontrado."));
    }

    private static boolean isFacilitator(HealthCheckBoard board, CeremonyCaller caller) {
        return caller != null && (caller.isAdmin() || caller.is(board.getCreatorId()));
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static String clean(String value, int max, String label, boolean required) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty()) {
            if (required) {
                throw badRequest(label + " é obrigatório.");
            }
            return null;
        }
        if (text.length() > max) {
            throw badRequest(label + " excede " + max + " caracteres.");
        }
        return text;
    }

    // --- Radar ---

    @Transactional(readOnly = true)
    public Optional<HealthCheckBoard> getBoard(String id) {
        return boardRepository.findById(id).map(this::withProgress);
    }

    @Transactional(readOnly = true)
    public boolean boardExists(String id) {
        return boardRepository.existsById(id);
    }

    @Transactional(readOnly = true)
    public List<HealthCheckBoard> listBoards(String squadId) {
        List<HealthCheckBoard> boards = boardRepository.findByTeamIgnoreCaseOrderByCreatedAtDesc(squadId);
        applyProgress(boards, progressFor(boards));
        return boards;
    }

    // --- Progresso de respostas (só contagens; nunca o voto de ninguém) ---

    /**
     * Fotografia de quem respondeu um radar. {@code pending} = pessoas na sala sem nenhum voto (só vai ao facilitador).
     * Conta apenas quem ainda está na sala: quem saiu deixa de contar, mas os votos dele seguem no resultado.
     */
    record Progress(int participants, int responded, int completed, List<String> pending) {
    }

    private Map<String, Progress> progressFor(Collection<HealthCheckBoard> boards) {
        if (boards == null || boards.isEmpty()) {
            return Map.of();
        }
        List<String> ids = boards.stream().map(HealthCheckBoard::getId).toList();
        Map<String, List<String>> participantsByBoard = new LinkedHashMap<>();
        List<HealthCheckParticipant> participants = participantRepository.findByBoardIdIn(ids);
        if (participants != null) {
            for (HealthCheckParticipant p : participants) {
                participantsByBoard.computeIfAbsent(p.getBoardId(), k -> new ArrayList<>()).add(p.getId());
            }
        }
        Map<String, Map<String, Integer>> dimsByBoard = new LinkedHashMap<>();
        List<Object[]> rows = voteRepository.countDimensionsByParticipant(ids);
        if (rows != null) {
            for (Object[] row : rows) {
                dimsByBoard.computeIfAbsent((String) row[0], k -> new LinkedHashMap<>())
                        .put((String) row[1], ((Number) row[2]).intValue());
            }
        }
        Map<String, Progress> result = new LinkedHashMap<>();
        for (HealthCheckBoard board : boards) {
            List<String> inRoom = participantsByBoard.getOrDefault(board.getId(), List.of());
            Map<String, Integer> dims = dimsByBoard.getOrDefault(board.getId(), Map.of());
            int total = dimensionCount(board);
            int responded = 0;
            int completed = 0;
            List<String> pending = new ArrayList<>();
            for (String userId : inRoom) {
                int voted = dims.getOrDefault(userId, 0);
                if (voted > 0) {
                    responded++;
                    if (total > 0 && voted >= total) {
                        completed++;
                    }
                } else {
                    pending.add(userId);
                }
            }
            result.put(board.getId(), new Progress(inRoom.size(), responded, completed, pending));
        }
        return result;
    }

    private static int dimensionCount(HealthCheckBoard board) {
        return board.getDimensions() != null && board.getDimensions().isArray() ? board.getDimensions().size() : 0;
    }

    private static void applyProgress(Collection<HealthCheckBoard> boards, Map<String, Progress> progress) {
        for (HealthCheckBoard board : boards) {
            Progress p = progress.get(board.getId());
            if (p != null) {
                board.setParticipantsCount(p.participants());
                board.setRespondedCount(p.responded());
                board.setCompletedCount(p.completed());
            }
        }
    }

    /** Preenche as contagens (campos transitórios) do próprio objeto e o devolve. */
    private HealthCheckBoard withProgress(HealthCheckBoard board) {
        applyProgress(List.of(board), progressFor(List.of(board)));
        return board;
    }

    /**
     * GET /api/health-checks/{id}/progress. Contagens para qualquer pessoa autenticada; a lista de quem ainda não votou
     * (só ids) apenas para o facilitador (criador ou ADMIN).
     */
    @Transactional(readOnly = true)
    public HealthCheckProgress getProgress(String boardId, CeremonyCaller caller) {
        requireAuthenticated(caller);
        HealthCheckBoard board = requireBoard(boardId);
        Progress p = progressFor(List.of(board)).get(boardId);
        return new HealthCheckProgress(boardId, dimensionCount(board), p.participants(), p.responded(), p.completed(),
                isFacilitator(board, caller) ? p.pending() : null);
    }

    /** Resposta de {@code GET /{id}/progress}; {@code pendingParticipantIds} só existe para o facilitador. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record HealthCheckProgress(String boardId, int dimensionsCount, int participantsCount, int respondedCount,
                                      int completedCount, List<String> pendingParticipantIds) {
    }

    /**
     * POST /api/health-checks: cria o radar (criador = quem chama; status sempre "collecting"; resumo nunca vem do
     * corpo). Num radar existente só o criador/ADMIN pode chamar, e a única mudança aceita é encerrar (abas antigas
     * ainda mandam o board inteiro com status "finished": o resumo é recalculado aqui, o do corpo é descartado).
     */
    @Transactional
    public Saved<HealthCheckBoard> saveOrUpdateBoard(HealthCheckBoard body, CeremonyCaller caller) {
        requireAuthenticated(caller);
        if (body == null) {
            throw badRequest("Corpo da requisição vazio.");
        }
        String id = body.getId() == null ? "" : body.getId().strip();
        if (id.length() > 100) {
            throw badRequest("Identificador inválido.");
        }
        if (!id.isEmpty()) {
            Optional<HealthCheckBoard> existing = boardRepository.findById(id);
            if (existing.isPresent()) {
                HealthCheckBoard board = existing.get();
                if (!isFacilitator(board, caller)) {
                    throw forbidden("Apenas o organizador pode alterar o radar.");
                }
                if (STATUS_FINISHED.equals(body.getStatus())) {
                    return new Saved<>(finish(id, caller), false);
                }
                return new Saved<>(board, false);
            }
        }
        String scale = body.getScaleType() == null || body.getScaleType().isBlank() ? "traffic_light" : body.getScaleType();
        if (!SCALES.contains(scale)) {
            throw badRequest("Escala de votação inválida.");
        }
        HealthCheckBoard board = HealthCheckBoard.builder()
                .id(id.isEmpty() ? UUID.randomUUID().toString() : id)
                .creatorId(caller.id())
                .status(STATUS_COLLECTING)
                .createdAt(now())
                .scaleType(scale)
                .sprintName(clean(body.getSprintName(), MAX_TITLE, "O nome da sprint", false))
                .team(clean(body.getTeam(), MAX_TITLE, "A squad", false))
                .dimensions(validDimensions(body.getDimensions()))
                .participantIds(JsonNodeFactory.instance.arrayNode().add(caller.id()))
                .build();
        HealthCheckBoard saved = withProgress(boardRepository.save(board));
        publish(saved.getId(), "BOARD_UPDATED", saved);
        return new Saved<>(saved, true);
    }

    public record Saved<T>(T value, boolean created) {
    }

    private static JsonNode validDimensions(JsonNode dimensions) {
        if (dimensions == null || !dimensions.isArray() || dimensions.isEmpty()) {
            throw badRequest("Informe ao menos uma dimensão para avaliar.");
        }
        if (dimensions.size() > MAX_DIMENSIONS) {
            throw badRequest("No máximo " + MAX_DIMENSIONS + " dimensões.");
        }
        ArrayNode clean = JsonNodeFactory.instance.arrayNode();
        Set<String> keys = new HashSet<>();
        for (JsonNode dim : dimensions) {
            String key = clean(dim.path("key").asText(null), 100, "A chave da dimensão", true);
            String title = clean(dim.path("title").asText(null), MAX_TITLE, "O título da dimensão", true);
            String description = clean(dim.path("description").asText(null), MAX_COMMENT, "A descrição da dimensão", false);
            if (!keys.add(key)) {
                throw badRequest("Há dimensões repetidas.");
            }
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("key", key);
            node.put("title", title);
            node.put("description", description == null ? "" : description);
            clean.add(node);
        }
        return clean;
    }

    @Transactional
    public void deleteBoard(String id, CeremonyCaller caller) {
        requireAuthenticated(caller);
        HealthCheckBoard board = requireBoardForUpdate(id);
        if (!isFacilitator(board, caller)) {
            throw forbidden("Apenas o organizador pode apagar o radar.");
        }
        voteRepository.deleteByBoardId(id);
        participantRepository.deleteByBoardId(id);
        boardRepository.deleteById(id);
        publish(id, "BOARD_DELETED", Map.of("boardId", id));
    }

    // --- Participantes ---

    @Transactional(readOnly = true)
    public List<HealthCheckParticipant> getParticipants(String boardId) {
        return participantRepository.findByBoardIdOrderByNicknameAsc(boardId);
    }

    /** Entrada na sala: o id é sempre o de quem chama. Quem já entrou mantém o papel (os votos usam ele). */
    @Transactional
    public HealthCheckParticipant joinBoard(String boardId, HealthCheckParticipant body, CeremonyCaller caller) {
        requireAuthenticated(caller);
        requireBoard(boardId);
        Optional<HealthCheckParticipant> existing = participantRepository.findByBoardIdAndId(boardId, caller.id());
        if (existing.isPresent()) {
            return existing.get();
        }
        String nickname = body == null ? null : clean(body.getNickname(), MAX_NICKNAME, "O apelido", false);
        String role = body != null && body.getRole() != null && TEAM_ROLES.contains(body.getRole()) ? body.getRole() : "OUTRO";
        HealthCheckParticipant participant = HealthCheckParticipant.builder()
                .dbId(boardId + "_" + caller.id())
                .id(caller.id())
                .boardId(boardId)
                .nickname(nickname != null ? nickname : "Participante")
                .role(role)
                .globalRole(body == null ? null : clean(body.getGlobalRole(), MAX_NICKNAME, "O papel", false))
                .build();
        HealthCheckParticipant saved = participantRepository.save(participant);
        publish(boardId, "PARTICIPANT_JOINED", saved);
        return saved;
    }

    @Transactional
    public void leaveBoard(String boardId, String userId, CeremonyCaller caller) {
        requireAuthenticated(caller);
        HealthCheckBoard board = requireBoard(boardId);
        if (!caller.is(userId) && !isFacilitator(board, caller)) {
            throw forbidden("Acesso restrito ao próprio usuário ou ao organizador.");
        }
        participantRepository.deleteByBoardIdAndId(boardId, userId);
        publish(boardId, "PARTICIPANT_LEFT", Map.of("userId", userId));
    }

    // --- Votos ---

    /**
     * Coleta aberta: só os votos de quem chama. Encerrado: todos, sem identificar o votante (a identidade do
     * votante não faz parte do resultado e deixar vazar quebra a promessa de anonimato da tela).
     */
    @Transactional(readOnly = true)
    public List<HealthCheckVote> getVotes(String boardId, CeremonyCaller caller) {
        requireAuthenticated(caller);
        HealthCheckBoard board = requireBoard(boardId);
        if (STATUS_FINISHED.equals(board.getStatus())) {
            List<HealthCheckVote> all = voteRepository.findByBoardId(boardId);
            List<HealthCheckVote> anonymous = new ArrayList<>(all.size());
            int index = 0;
            for (HealthCheckVote vote : all) {
                anonymous.add(anonymize(vote, ++index));
            }
            return anonymous;
        }
        return voteRepository.findByBoardIdAndParticipantId(boardId, caller.id());
    }

    private static HealthCheckVote anonymize(HealthCheckVote vote, int index) {
        return HealthCheckVote.builder()
                .id(vote.getDimensionKey() + "#" + index)
                .boardId(vote.getBoardId())
                .participantId(null)
                .participantRole(null)
                .dimensionKey(vote.getDimensionKey())
                .value(vote.getValue())
                .comment(vote.getComment())
                .timestamp(vote.getTimestamp())
                .build();
    }

    /** Registra/atualiza o voto de quem chama numa dimensão. O board é travado: nada entra depois do encerramento. */
    @Transactional
    public HealthCheckVote saveVote(String boardId, HealthCheckVote body, CeremonyCaller caller) {
        requireAuthenticated(caller);
        if (body == null) {
            throw badRequest("Corpo da requisição vazio.");
        }
        HealthCheckBoard board = requireBoardForUpdate(boardId);
        if (!STATUS_COLLECTING.equals(board.getStatus())) {
            throw conflict("A votação deste radar já foi encerrada.");
        }
        HealthCheckParticipant participant = participantRepository.findByBoardIdAndId(boardId, caller.id())
                .orElseThrow(() -> forbidden("Entre na sala antes de votar."));
        String dimensionKey = clean(body.getDimensionKey(), 100, "A dimensão", true);
        if (!dimensionExists(board, dimensionKey)) {
            throw badRequest("Dimensão inexistente neste radar.");
        }
        String value = body.getValue() == null ? "" : body.getValue().strip();
        if (!validValues(board.getScaleType()).contains(value)) {
            throw badRequest("Valor de voto inválido para a escala deste radar.");
        }
        String comment = clean(body.getComment(), MAX_COMMENT, "O comentário", false);

        String id = boardId + "_" + caller.id() + "_" + dimensionKey;
        Optional<HealthCheckVote> existingVote = voteRepository.findById(id);
        int votedBefore = existingVote.isPresent() ? -1 : distinctDimensionsVoted(boardId, caller.id());
        HealthCheckVote vote = existingVote.orElseGet(() -> HealthCheckVote.builder()
                .id(id)
                .boardId(boardId)
                .participantId(caller.id())
                .dimensionKey(dimensionKey)
                .build());
        vote.setParticipantRole(participant.getRole());
        vote.setValue(value);
        vote.setComment(comment == null ? "" : comment);
        vote.setTimestamp(now());
        HealthCheckVote saved = voteRepository.save(vote);
        publishToUser(boardId, "VOTE_SAVED", saved, caller.id());
        publishProgressIfChanged(board, votedBefore);
        return saved;
    }

    private int distinctDimensionsVoted(String boardId, String userId) {
        List<HealthCheckVote> own = voteRepository.findByBoardIdAndParticipantId(boardId, userId);
        return own == null ? 0 : (int) own.stream().map(HealthCheckVote::getDimensionKey).distinct().count();
    }

    /**
     * Reaproveita o BOARD_UPDATED: avisa a sala só quando este voto muda "quantos responderam" (a primeira resposta da
     * pessoa) ou "quantos completaram" (a última dimensão). Trocar um voto já dado ou votar em mais uma dimensão não
     * gera evento. {@code votedBefore} = dimensões já votadas pela pessoa antes deste voto, ou -1 se ele só reescreveu
     * um voto existente.
     */
    private void publishProgressIfChanged(HealthCheckBoard board, int votedBefore) {
        if (votedBefore < 0) {
            return;
        }
        int total = dimensionCount(board);
        boolean firstAnswer = votedBefore == 0;
        boolean completes = total > 0 && votedBefore + 1 == total;
        if (firstAnswer || completes) {
            publish(board.getId(), "BOARD_UPDATED", withProgress(board));
        }
    }

    private static boolean dimensionExists(HealthCheckBoard board, String key) {
        JsonNode dims = board.getDimensions();
        if (dims == null || !dims.isArray()) {
            return false;
        }
        for (JsonNode dim : dims) {
            if (key.equals(dim.path("key").asText())) {
                return true;
            }
        }
        return false;
    }

    static Set<String> validValues(String scaleType) {
        return switch (scaleType == null ? "traffic_light" : scaleType) {
            case "numbers_5" -> Set.of("1", "2", "3", "4", "5");
            case "emojis" -> Set.of("happy", "neutral", "sad");
            default -> Set.of("green", "yellow", "red");
        };
    }

    // --- Encerramento ---

    /**
     * Encerra a coleta e grava o resumo. Só criador/ADMIN. Idempotente: encerrar de novo devolve o radar como está.
     * Exige ao menos um voto.
     */
    @Transactional
    public HealthCheckBoard finish(String boardId, CeremonyCaller caller) {
        requireAuthenticated(caller);
        HealthCheckBoard board = requireBoardForUpdate(boardId);
        if (!isFacilitator(board, caller)) {
            throw forbidden("Apenas o organizador pode encerrar a votação.");
        }
        if (STATUS_FINISHED.equals(board.getStatus())) {
            return board;
        }
        return finish(board);
    }

    private HealthCheckBoard finish(HealthCheckBoard board) {
        List<HealthCheckVote> votes = voteRepository.findByBoardId(board.getId());
        if (votes.isEmpty()) {
            throw conflict("É necessário pelo menos um voto para encerrar.");
        }
        board.setSummary(buildSummary(board, votes));
        board.setStatus(STATUS_FINISHED);
        board.setFinishedAt(now());
        boolean hadStage = board.getPresentationStage() != null;
        board.setPresentationStage(null);
        HealthCheckBoard saved = withProgress(boardRepository.save(board));
        publish(saved.getId(), "BOARD_UPDATED", saved);
        if (hadStage) {
            publishStage(saved.getId(), null);
        }
        return saved;
    }

    // --- Apresentação sincronizada ---

    /** PUT /{id}/presentation: o facilitador publica a etapa atual. Só depois de encerrado (a apresentação mostra o resultado). */
    @Transactional
    public HealthCheckBoard setPresentationStage(String boardId, String stage, CeremonyCaller caller) {
        requireAuthenticated(caller);
        String clean = stage == null ? "" : stage.strip();
        if (!STAGE_FORMAT.matcher(clean).matches()) {
            throw badRequest("Etapa inválida: use de 1 a 40 caracteres (letras, números, _ - .).");
        }
        HealthCheckBoard board = requireBoardForUpdate(boardId);
        if (!isFacilitator(board, caller)) {
            throw forbidden("Apenas o organizador pode conduzir a apresentação.");
        }
        if (!STATUS_FINISHED.equals(board.getStatus())) {
            throw conflict("A apresentação só começa depois que o radar for encerrado.");
        }
        return storeStage(board, clean);
    }

    /** DELETE /{id}/presentation: o facilitador encerra a apresentação. */
    @Transactional
    public HealthCheckBoard clearPresentationStage(String boardId, CeremonyCaller caller) {
        requireAuthenticated(caller);
        HealthCheckBoard board = requireBoardForUpdate(boardId);
        if (!isFacilitator(board, caller)) {
            throw forbidden("Apenas o organizador pode conduzir a apresentação.");
        }
        return storeStage(board, null);
    }

    private HealthCheckBoard storeStage(HealthCheckBoard board, String stage) {
        board.setPresentationStage(stage);
        HealthCheckBoard saved = withProgress(boardRepository.save(board));
        publishStage(saved.getId(), stage);
        return saved;
    }

    /** Evento opt-in (?presentation=1): estado completo da etapa. Não usa o BOARD_UPDATED para não acordar clientes antigos a cada clique. */
    private void publishStage(String boardId, String stage) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", stage);
        AfterCommit.run(() -> webSocketHandler.broadcastPresentationStage(boardId, payload));
    }

    /** Nota numérica de um voto: verde/feliz = 3, amarelo/neutro = 2, vermelho/triste = 1, ou o próprio número (1..5). */
    static int score(String value) {
        return switch (value) {
            case "green", "happy" -> 3;
            case "yellow", "neutral" -> 2;
            case "red", "sad" -> 1;
            default -> {
                try {
                    yield Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    yield 0;
                }
            }
        };
    }

    /**
     * Resumo no formato que o frontend lê: {@code results} por dimensão (contagem por valor e média) e as
     * dimensões em destaque. Os limites seguem os da tela de resultados (escala 1–5: forte ≥ 4, fraca < 2,5;
     * demais escalas: forte ≥ 2,5, fraca < 1,8).
     */
    static JsonNode buildSummary(HealthCheckBoard board, List<HealthCheckVote> votes) {
        boolean numeric = "numbers_5".equals(board.getScaleType());
        double strong = numeric ? 4 : 2.5;
        double weak = numeric ? 2.5 : 1.8;

        record Row(String key, String title, ObjectNode node, double average) {
        }
        List<Row> rows = new ArrayList<>();
        ArrayNode results = JsonNodeFactory.instance.arrayNode();
        for (JsonNode dim : board.getDimensions()) {
            String key = dim.path("key").asText();
            String title = dim.path("title").asText(key);
            Map<String, Integer> counts = new LinkedHashMap<>();
            int total = 0;
            int count = 0;
            for (HealthCheckVote vote : votes) {
                if (!key.equals(vote.getDimensionKey())) {
                    continue;
                }
                counts.merge(vote.getValue(), 1, Integer::sum);
                int score = score(vote.getValue());
                if (score > 0) {
                    total += score;
                    count++;
                }
            }
            double average = count > 0 ? (double) total / count : 0;
            ObjectNode values = JsonNodeFactory.instance.objectNode();
            counts.forEach(values::put);
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("dimensionKey", key);
            node.set("values", values);
            node.put("average", average);
            results.add(node);
            rows.add(new Row(key, title, node, average));
        }
        ArrayNode top = JsonNodeFactory.instance.arrayNode();
        rows.stream().filter(r -> r.average() >= strong)
                .sorted(Comparator.comparingDouble(Row::average).reversed())
                .limit(3).forEach(r -> top.add(r.title()));
        ArrayNode low = JsonNodeFactory.instance.arrayNode();
        rows.stream().filter(r -> r.average() > 0 && r.average() < weak)
                .sorted(Comparator.comparingDouble(Row::average))
                .limit(3).forEach(r -> low.add(r.title()));

        ObjectNode summary = JsonNodeFactory.instance.objectNode();
        summary.set("topMetrics", top);
        summary.set("lowMetrics", low);
        summary.set("results", results);
        return summary;
    }
}
