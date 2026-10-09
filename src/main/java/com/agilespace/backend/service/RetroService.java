package com.agilespace.backend.service;

import com.agilespace.backend.domain.RetroBoard;
import com.agilespace.backend.domain.RetroCard;
import com.agilespace.backend.domain.RetroChatMessage;
import com.agilespace.backend.domain.RetroColumnDef;
import com.agilespace.backend.domain.RetroParticipant;
import com.agilespace.backend.repository.RetroBoardRepository;
import com.agilespace.backend.repository.RetroCardRepository;
import com.agilespace.backend.repository.RetroChatMessageRepository;
import com.agilespace.backend.repository.RetroParticipantRepository;
import com.agilespace.backend.websocket.RetroWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class RetroService {

    private final RetroBoardRepository boardRepository;
    private final RetroParticipantRepository participantRepository;
    private final RetroCardRepository cardRepository;
    private final RetroChatMessageRepository chatMessageRepository;
    private final RetroWebSocketHandler webSocketHandler;
    private final SquadAccessService squadAccessService;

    /** Resultado de um upsert: {@code created} decide 201 (criou) x 200 (atualizou) no controller. */
    public record Saved<T>(T value, boolean created) { }

    static final Set<String> VOTING_STATUSES = Set.of("disabled", "active", "finished");
    static final Set<String> TIMER_STATUSES = Set.of("stopped", "running", "paused");
    static final Set<String> REACTION_TYPES = Set.of("up", "love", "wow", "concern");
    static final int MAX_TITLE = 255;
    static final int MAX_COLUMN_KEY = 50;
    static final int MAX_CARD_CONTENT = 5000;
    static final int MAX_SHORT_TEXT = 255;
    static final int MAX_QUESTION = 2000;
    static final int MAX_COLUMNS = 20;
    static final int MAX_ORIGINAL_TEXTS = 100;
    static final int MAX_VOTES_LIMIT = 100;
    static final int MAX_IMPORT = 200;

    // --- Publicação de eventos ---

    /**
     * Publica no WebSocket só depois do commit: dentro da transação o cliente recebia o evento,
     * relia pela API e via o estado antigo (ou um estado que depois sofria rollback). Fora de
     * transação (ex.: métodos sem @Transactional) publica na hora.
     */
    private void publish(String boardId, String eventType, Object payload) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    webSocketHandler.broadcastEvent(boardId, eventType, payload);
                }
            });
        } else {
            webSocketHandler.broadcastEvent(boardId, eventType, payload);
        }
    }

    // --- Autorização ---

    private static ResponseStatusException forbidden(String msg) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, msg);
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static ResponseStatusException conflict(String msg) {
        return new ResponseStatusException(HttpStatus.CONFLICT, msg);
    }

    private static boolean isCreator(RetroBoard board, RetroCaller caller) {
        return caller != null && caller.id() != null && caller.id().equals(board.getCreatorId());
    }

    private static boolean isCreatorOrAdmin(RetroBoard board, RetroCaller caller) {
        return caller != null && (caller.isAdmin() || isCreator(board, caller));
    }

    /** Participante, criador ou ADMIN: pode escrever no board. */
    private boolean isMember(RetroBoard board, RetroCaller caller) {
        if (caller == null || caller.id() == null || caller.id().isBlank()) return false;
        if (isCreatorOrAdmin(board, caller)) return true;
        return participantRepository.findByBoardIdAndId(board.getId(), caller.id()).isPresent();
    }

    private void requireMember(RetroBoard board, RetroCaller caller) {
        if (!isMember(board, caller)) {
            throw forbidden("Acesso restrito a participantes deste board.");
        }
    }

    /**
     * Leitura / entrada na sala: membro, OU (para o fluxo de convite por link, em que a pessoa lê o
     * board antes de virar participante) alguém com acesso à squad do board. Board sem squadId
     * (legado, só "team") não tem como ser verificado e continua aberto a qualquer autenticado.
     */
    boolean canRead(RetroBoard board, RetroCaller caller) {
        if (caller == null || caller.id() == null || caller.id().isBlank()) return false;
        if (isMember(board, caller)) return true;
        String squadId = board.getSquadId();
        if (squadId == null || squadId.isBlank()) return true;
        return squadAccessService != null && squadAccessService.matchesSquad(squadId, caller.id(), caller.role());
    }

    private void requireRead(RetroBoard board, RetroCaller caller) {
        if (!canRead(board, caller)) {
            throw forbidden("Acesso restrito a membros da squad deste board.");
        }
    }

    /** Usado no handshake do WebSocket: o board existe e o usuário pode lê-lo. */
    @Transactional(readOnly = true)
    public boolean canAccessBoard(String boardId, RetroCaller caller) {
        return boardRepository.findById(boardId).map(b -> canRead(b, caller)).orElse(false);
    }

    private RetroBoard requireBoard(String boardId) {
        return boardRepository.findById(boardId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Board não encontrado"));
    }

    private RetroBoard requireBoardForUpdate(String boardId) {
        return boardRepository.findByIdForUpdate(boardId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Board não encontrado"));
    }

    // --- Board Logic ---
    @Transactional(readOnly = true)
    public List<RetroBoard> listBoardsBySprintId(String sprintId) {
        return boardRepository.findBySprintIdOrderByCreatedAtDesc(sprintId);
    }

    /** Lista da sprint filtrada pelos boards que o chamador pode ler. */
    @Transactional(readOnly = true)
    public List<RetroBoard> listBoardsBySprintId(String sprintId, RetroCaller caller) {
        return boardRepository.findBySprintIdOrderByCreatedAtDesc(sprintId).stream()
                .filter(b -> canRead(b, caller))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RetroBoard> listBoardsByTeam(String team) {
        return boardRepository.findByTeamIgnoreCaseOrderByCreatedAtDesc(team);
    }

    @Transactional(readOnly = true)
    public List<RetroBoard> listBoardsBySquadId(String squadId) {
        return boardRepository.findBySquadIdIgnoreCaseOrderByCreatedAtDesc(squadId);
    }

    @Transactional(readOnly = true)
    public Optional<RetroBoard> getBoard(String boardId) {
        return boardRepository.findById(boardId);
    }

    /** Leitura autorizada: vazio se não existe, 403 se o chamador não pode ler. */
    @Transactional(readOnly = true)
    public Optional<RetroBoard> getBoard(String boardId, RetroCaller caller) {
        Optional<RetroBoard> board = boardRepository.findById(boardId);
        board.ifPresent(b -> requireRead(b, caller));
        return board;
    }

    /**
     * POST /api/retros (compatível com o frontend antigo, que grava o board inteiro):
     * - board novo: criador passa a ser o chamador (creatorId do corpo é ignorado); exige acesso à squad;
     * - board existente: criador/ADMIN aplica os campos enviados (nunca creatorId/squadId);
     * - participante que envia creatorId = ele mesmo é o "assumir controle" legado (= transfer-control);
     * - qualquer outro participante: o corpo é ignorado e devolve-se o board atual (o frontend antigo
     *   regrava o board inteiro ao entrar na sala; antes isso sobrescrevia o estado de todos).
     */
    @Transactional
    public Saved<RetroBoard> saveOrUpdateBoard(RetroBoard incoming, RetroCaller caller) {
        if (caller == null || caller.id() == null || caller.id().isBlank()) {
            throw forbidden("Usuário não identificado.");
        }
        Optional<RetroBoard> existingOpt = incoming.getId() == null || incoming.getId().isBlank()
                ? Optional.empty() : boardRepository.findById(incoming.getId());

        if (existingOpt.isEmpty()) {
            String squadId = incoming.getSquadId();
            if (squadId != null && !squadId.isBlank() && !caller.isAdmin()
                    && (squadAccessService == null
                        || !squadAccessService.matchesSquad(squadId, caller.id(), caller.role()))) {
                throw forbidden("Acesso restrito a membros desta squad ou administradores.");
            }
            validateBoardFields(incoming);
            if (incoming.getId() == null || incoming.getId().isBlank()) {
                incoming.setId(UUID.randomUUID().toString());
            }
            incoming.setVersion(null);
            incoming.setCreatorId(caller.id());
            RetroBoard saved = boardRepository.save(incoming);
            publish(saved.getId(), "BOARD_UPDATED", saved);
            return new Saved<>(saved, true);
        }

        RetroBoard existing = existingOpt.get();
        if (isCreatorOrAdmin(existing, caller)) {
            validateBoardFields(incoming);
            applyBoardFields(existing, incoming);
            RetroBoard saved = boardRepository.save(existing);
            publish(saved.getId(), "BOARD_UPDATED", saved);
            return new Saved<>(saved, false);
        }

        requireMember(existing, caller);
        if (caller.id().equals(incoming.getCreatorId()) && !caller.id().equals(existing.getCreatorId())) {
            return new Saved<>(doTransferControl(existing, caller), false);
        }
        return new Saved<>(existing, false);
    }

    private void validateBoardFields(RetroBoard b) {
        if (b.getTitle() == null || b.getTitle().isBlank()) throw badRequest("O título do board é obrigatório.");
        validateOptionalBoardFields(b);
    }

    /** Validações dos campos opcionais (também usadas pelo PATCH, onde o título pode estar ausente). */
    private void validateOptionalBoardFields(RetroBoard b) {
        if (b.getTitle() != null && b.getTitle().length() > MAX_TITLE) throw badRequest("O título excede " + MAX_TITLE + " caracteres.");
        if (b.getVotingStatus() != null && !VOTING_STATUSES.contains(b.getVotingStatus())) {
            throw badRequest("votingStatus inválido.");
        }
        if (b.getTimerStatus() != null && !TIMER_STATUSES.contains(b.getTimerStatus())) {
            throw badRequest("Status do timer inválido.");
        }
        if (b.getActiveColumnKey() != null && b.getActiveColumnKey().length() > MAX_TITLE) throw badRequest("activeColumnKey muito longo.");
        if (b.getHealthCheckQuestion() != null && b.getHealthCheckQuestion().length() > MAX_QUESTION) throw badRequest("Pergunta do check-in muito longa.");
        if (b.getTeam() != null && b.getTeam().length() > MAX_TITLE) throw badRequest("team muito longo.");
        if (b.getTemplateKey() != null && b.getTemplateKey().length() > 50) throw badRequest("templateKey muito longo.");
        if (b.getMaxVotesPerParticipant() != null
                && (b.getMaxVotesPerParticipant() < 0 || b.getMaxVotesPerParticipant() > MAX_VOTES_LIMIT)) {
            throw badRequest("maxVotesPerParticipant deve estar entre 0 e " + MAX_VOTES_LIMIT + ".");
        }
        if (b.getTimerInitialDuration() != null && b.getTimerInitialDuration() < 0) throw badRequest("Duração do timer inválida.");
        if (b.getTimerRemainingOnPause() != null && b.getTimerRemainingOnPause() < 0) throw badRequest("Tempo restante do timer inválido.");
        if (b.getTimerEndTime() != null && b.getTimerEndTime().length() > 50) throw badRequest("endTime inválido.");
        if (b.getColumns() != null) {
            if (b.getColumns().size() > MAX_COLUMNS) throw badRequest("Colunas demais (máx. " + MAX_COLUMNS + ").");
            for (RetroColumnDef c : b.getColumns()) {
                if (c.getId() == null || c.getId().isBlank() || c.getId().length() > MAX_COLUMN_KEY) throw badRequest("Id de coluna inválido.");
                if (c.getTitle() != null && c.getTitle().length() > MAX_TITLE) throw badRequest("Título de coluna muito longo.");
                if (c.getTheme() != null && c.getTheme().length() > 50) throw badRequest("Tema de coluna inválido.");
            }
        }
    }

    /** Copia só os campos presentes (não nulos) — nunca id, creatorId, squadId, sprintId, createdAt, version. */
    private void applyBoardFields(RetroBoard target, RetroBoard src) {
        if (src.getTitle() != null) target.setTitle(src.getTitle());
        if (src.getTeam() != null) target.setTeam(src.getTeam());
        if (src.getTemplateKey() != null) target.setTemplateKey(src.getTemplateKey());
        if (src.getIsCardsRevealed() != null) target.setIsCardsRevealed(src.getIsCardsRevealed());
        if (src.getIsAuthorsRevealed() != null) target.setIsAuthorsRevealed(src.getIsAuthorsRevealed());
        if (src.getVotingStatus() != null) target.setVotingStatus(src.getVotingStatus());
        if (src.getSyncStageEnabled() != null) target.setSyncStageEnabled(src.getSyncStageEnabled());
        if (src.getActiveColumnKey() != null) target.setActiveColumnKey(src.getActiveColumnKey());
        if (src.getAutoRevealOnTimerEnd() != null) target.setAutoRevealOnTimerEnd(src.getAutoRevealOnTimerEnd());
        if (src.getAutoSortOnVoteEnd() != null) target.setAutoSortOnVoteEnd(src.getAutoSortOnVoteEnd());
        if (src.getMaxVotesPerParticipant() != null) target.setMaxVotesPerParticipant(src.getMaxVotesPerParticipant());
        if (src.getHealthCheckEnabled() != null) target.setHealthCheckEnabled(src.getHealthCheckEnabled());
        if (src.getHealthCheckQuestion() != null) target.setHealthCheckQuestion(src.getHealthCheckQuestion());
        if (src.getTimerStatus() != null) target.setTimerStatus(src.getTimerStatus());
        if (src.getTimerInitialDuration() != null) target.setTimerInitialDuration(src.getTimerInitialDuration());
        if (src.getTimerRemainingOnPause() != null) target.setTimerRemainingOnPause(src.getTimerRemainingOnPause());
        if (src.getTimerEndTime() != null) target.setTimerEndTime(src.getTimerEndTime());
        if (src.getColumns() != null && !src.getColumns().isEmpty()) {
            target.getColumns().clear();
            target.getColumns().addAll(src.getColumns());
        }
    }

    /**
     * PATCH /api/retros/{id}: atualização parcial. Só os campos presentes no JSON são tocados.
     * Campos de controle exigem criador/ADMIN; participantIds/summary/columnSorts são aceitos e
     * ignorados (não existem colunas para eles — a lista real de participantes é /participants).
     */
    @Transactional
    public RetroBoard patchBoard(String boardId, JsonNode patch, RetroCaller caller) {
        if (patch == null || !patch.isObject()) throw badRequest("Corpo inválido.");
        RetroBoard board = requireBoardForUpdate(boardId);
        requireMember(board, caller);

        RetroBoard delta = new RetroBoard();
        // o construtor vazio aplica os @Builder.Default; aqui "ausente" tem de ser null
        delta.setIsCardsRevealed(null); delta.setIsAuthorsRevealed(null); delta.setVotingStatus(null);
        delta.setTeam(null); delta.setSyncStageEnabled(null); delta.setAutoRevealOnTimerEnd(null);
        delta.setAutoSortOnVoteEnd(null); delta.setMaxVotesPerParticipant(null); delta.setHealthCheckEnabled(null);
        delta.setTimerStatus(null); delta.setTimerInitialDuration(null); delta.setTimerRemainingOnPause(null);
        delta.setColumns(null);

        boolean touchesControl = false;
        boolean clearEndTime = false;
        boolean clearQuestion = false;
        boolean clearActiveColumn = false;
        Iterator<Map.Entry<String, JsonNode>> it = patch.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String k = e.getKey();
            JsonNode v = e.getValue();
            switch (k) {
                case "title" -> { delta.setTitle(textOrNull(v)); touchesControl = true; }
                case "team" -> { delta.setTeam(textOrNull(v)); touchesControl = true; }
                case "templateKey" -> { delta.setTemplateKey(textOrNull(v)); touchesControl = true; }
                case "activeColumnKey" -> {
                    delta.setActiveColumnKey(textOrNull(v)); clearActiveColumn = v.isNull(); touchesControl = true;
                }
                case "votingStatus" -> { delta.setVotingStatus(textOrNull(v)); touchesControl = true; }
                case "isCardsRevealed" -> { delta.setIsCardsRevealed(boolOrNull(v)); touchesControl = true; }
                case "isAuthorsRevealed" -> { delta.setIsAuthorsRevealed(boolOrNull(v)); touchesControl = true; }
                case "syncStageEnabled" -> { delta.setSyncStageEnabled(boolOrNull(v)); touchesControl = true; }
                case "autoRevealOnTimerEnd" -> { delta.setAutoRevealOnTimerEnd(boolOrNull(v)); touchesControl = true; }
                case "autoSortOnVoteEnd" -> { delta.setAutoSortOnVoteEnd(boolOrNull(v)); touchesControl = true; }
                case "healthCheckEnabled" -> { delta.setHealthCheckEnabled(boolOrNull(v)); touchesControl = true; }
                case "healthCheckQuestion" -> {
                    delta.setHealthCheckQuestion(textOrNull(v)); clearQuestion = v.isNull(); touchesControl = true;
                }
                case "maxVotesPerParticipant" -> {
                    delta.setMaxVotesPerParticipant(v.isNumber() ? v.intValue() : null); touchesControl = true;
                }
                case "timer" -> {
                    if (v.isObject()) {
                        if (v.hasNonNull("status")) delta.setTimerStatus(v.get("status").asText());
                        if (v.has("endTime")) {
                            if (v.get("endTime").isNull()) clearEndTime = true;
                            else delta.setTimerEndTime(v.get("endTime").asText());
                        }
                        if (v.hasNonNull("initialDuration") && v.get("initialDuration").isNumber()) delta.setTimerInitialDuration(v.get("initialDuration").intValue());
                        if (v.hasNonNull("remainingOnPause") && v.get("remainingOnPause").isNumber()) delta.setTimerRemainingOnPause(v.get("remainingOnPause").intValue());
                        touchesControl = true;
                    }
                }
                case "columns" -> {
                    if (v.isArray()) {
                        List<RetroColumnDef> cols = new ArrayList<>();
                        int i = 0;
                        for (JsonNode c : v) {
                            Integer order = c.hasNonNull("order") ? c.get("order").asInt()
                                    : c.hasNonNull("columnOrder") ? c.get("columnOrder").asInt() : i;
                            cols.add(RetroColumnDef.builder()
                                    .id(textOrNull(c.get("id"))).title(textOrNull(c.get("title")))
                                    .theme(textOrNull(c.get("theme"))).columnOrder(order).build());
                            i++;
                        }
                        delta.setColumns(cols);
                        touchesControl = true;
                    }
                }
                default -> { /* creatorId, squadId, sprintId, id, participantIds, summary...: ignorados */ }
            }
        }

        if (touchesControl && !isCreatorOrAdmin(board, caller)) {
            throw forbidden("Apenas o facilitador do board pode alterar estas configurações.");
        }
        if (touchesControl) {
            validateOptionalBoardFields(delta);
            applyBoardFields(board, delta);
            if (clearEndTime) board.setTimerEndTime(null);
            if (clearQuestion) board.setHealthCheckQuestion(null);
            if (clearActiveColumn) board.setActiveColumnKey(null);
        }
        RetroBoard saved = boardRepository.save(board);
        publish(boardId, "BOARD_UPDATED", saved);
        return saved;
    }

    private static String textOrNull(JsonNode v) {
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Boolean boolOrNull(JsonNode v) {
        return v == null || !v.isBoolean() ? null : v.booleanValue();
    }

    /**
     * "Assumir controle": o chamador (participante) vira o criador e a flag isCreator dos demais
     * é limpa — uma única flag no board. Mantém o comportamento do produto: qualquer participante pode assumir.
     */
    @Transactional
    public RetroBoard transferControl(String boardId, RetroCaller caller) {
        RetroBoard board = requireBoardForUpdate(boardId);
        requireMember(board, caller);
        return doTransferControl(board, caller);
    }

    private RetroBoard doTransferControl(RetroBoard board, RetroCaller caller) {
        board.setCreatorId(caller.id());
        RetroBoard saved = boardRepository.save(board);
        List<RetroParticipant> participants = participantRepository.findByBoardId(board.getId());
        if (participants != null) {
            for (RetroParticipant p : participants) {
                boolean shouldBeCreator = caller.id().equals(p.getId());
                if (!Boolean.valueOf(shouldBeCreator).equals(p.getIsCreator())) {
                    p.setIsCreator(shouldBeCreator);
                    publish(board.getId(), "PARTICIPANT_JOINED", participantRepository.save(p));
                }
            }
        }
        publish(board.getId(), "BOARD_UPDATED", saved);
        return saved;
    }

    /** Remove o board e tudo que depende dele (não há FK/cascade no banco). */
    @Transactional
    public void deleteBoard(String boardId, RetroCaller caller) {
        RetroBoard board = requireBoard(boardId);
        if (!isCreatorOrAdmin(board, caller)) {
            throw forbidden("Apenas o facilitador do board pode excluí-lo.");
        }
        cardRepository.deleteByBoardId(boardId);
        participantRepository.deleteByBoardId(boardId);
        chatMessageRepository.deleteByBoardId(boardId);
        boardRepository.delete(board);
        publish(boardId, "BOARD_DELETED", Map.of("boardId", boardId));
    }

    // --- Participants Logic ---
    @Transactional(readOnly = true)
    public List<RetroParticipant> getParticipants(String boardId) {
        return participantRepository.findByBoardId(boardId);
    }

    @Transactional(readOnly = true)
    public List<RetroParticipant> getParticipants(String boardId, RetroCaller caller) {
        RetroBoard board = requireBoard(boardId);
        requireRead(board, caller);
        return participantRepository.findByBoardId(boardId);
    }

    // Sem @Transactional de propósito: cada chamada a save()/findById() abaixo
    // já abre sua própria transação via Spring Data. Isso é o que permite o
    // catch abaixo tentar de novo depois de uma falha — dentro de UMA
    // transação compartilhada, uma DataIntegrityViolationException marca a
    // transação como rollback-only e qualquer save() seguinte no mesmo método
    // falharia ao tentar comitar, mesmo capturando a exceção.
    /**
     * Entrada/atualização do PRÓPRIO participante: o id é sempre o do chamador (o corpo não escolhe
     * quem entra), isCreator é derivado do board e nunca vem do corpo. role/globalRole continuam
     * vindos do corpo (rótulos de exibição do perfil; nada no backend decide permissão por eles).
     */
    public Saved<RetroParticipant> addOrUpdateParticipant(RetroParticipant incoming, RetroCaller caller) {
        if (caller == null || caller.id() == null || caller.id().isBlank()) {
            throw forbidden("Usuário não identificado.");
        }
        RetroBoard board = requireBoard(incoming.getBoardId());
        requireRead(board, caller);
        incoming.setId(caller.id());
        incoming.setIsCreator(null);
        if (incoming.getNickname() != null && (incoming.getNickname().isBlank() || incoming.getNickname().length() > 100)) {
            throw badRequest("Apelido inválido.");
        }
        if (incoming.getRole() != null && incoming.getRole().length() > 50) throw badRequest("role inválido.");
        if (incoming.getGlobalRole() != null && incoming.getGlobalRole().length() > 50) throw badRequest("globalRole inválido.");
        if (incoming.getHealthCheckAnswer() != null && incoming.getHealthCheckAnswer().length() > 50) throw badRequest("Resposta inválida.");

        // ID composto implícito para garantir unicidade na tabela e evitar conflitos de restrição
        String dbId = incoming.getBoardId() + "_" + incoming.getId();
        incoming.setDbId(dbId);

        Optional<RetroParticipant> existing = participantRepository.findById(dbId);
        if (existing != null && existing.isPresent()) {
            // Merge por campo, não replace total: o front dispara vários
            // "addOrUpdateParticipant" quase simultâneos pro mesmo participante
            // (auto-join da identidade global, sincronização de perfil, resposta
            // do check-in inicial) e cada um só sabe sobre o pedaço que está
            // mudando. Um save() do objeto inteiro faria o que chegasse por
            // último apagar campo que outro acabou de gravar (ex.: auto-join
            // sem healthCheckAnswer zerando a resposta que acabou de ser salva).
            RetroParticipant saved = participantRepository.save(mergeParticipant(existing.get(), incoming));
            publish(incoming.getBoardId(), "PARTICIPANT_JOINED", saved);
            return new Saved<>(saved, false);
        }

        if (incoming.getNickname() == null || incoming.getNickname().isBlank()) {
            throw badRequest("O apelido é obrigatório.");
        }
        incoming.setIsCreator(isCreator(board, caller));
        RetroParticipant saved;
        try {
            saved = participantRepository.save(incoming);
        } catch (DataIntegrityViolationException e) {
            // Corrida na criação: duas requisições viram "não existe ainda" no
            // findById acima e tentam INSERT ao mesmo tempo — a segunda esbarra
            // na PK que a primeira acabou de commitar. Refaz como merge sobre a
            // linha que já existe agora em vez de propagar 500 pro cliente.
            saved = participantRepository.findById(dbId)
                    .map(row -> participantRepository.save(mergeParticipant(row, incoming)))
                    .orElseThrow(() -> e);
        }
        publish(incoming.getBoardId(), "PARTICIPANT_JOINED", saved);
        return new Saved<>(saved, true);
    }

    private RetroParticipant mergeParticipant(RetroParticipant existing, RetroParticipant incoming) {
        if (incoming.getNickname() != null) existing.setNickname(incoming.getNickname());
        if (incoming.getRole() != null) existing.setRole(incoming.getRole());
        if (incoming.getGlobalRole() != null) existing.setGlobalRole(incoming.getGlobalRole());
        if (incoming.getHealthCheckAnswer() != null) existing.setHealthCheckAnswer(incoming.getHealthCheckAnswer());
        return existing;
    }

    /** Sai do board (o próprio usuário), ou é removido pelo criador/ADMIN. */
    @Transactional
    public void removeParticipant(String boardId, String userId, RetroCaller caller) {
        RetroBoard board = requireBoard(boardId);
        boolean self = caller != null && userId != null && userId.equals(caller.id());
        if (!self && !isCreatorOrAdmin(board, caller)) {
            throw forbidden("Acesso restrito ao próprio usuário ou ao facilitador.");
        }
        participantRepository.deleteByBoardIdAndId(boardId, userId);
        publish(boardId, "PARTICIPANT_LEFT", Map.of("userId", userId));
    }

    // --- Cards Logic ---
    @Transactional(readOnly = true)
    public List<RetroCard> getCards(String boardId) {
        List<RetroCard> cards = new ArrayList<>(cardRepository.findByBoardId(boardId));
        cards.sort(Comparator.comparing((RetroCard c) -> c.getOrder() == null ? 0L : c.getOrder())
                .thenComparing(c -> c.getId() == null ? "" : c.getId()));
        return cards;
    }

    @Transactional(readOnly = true)
    public List<RetroCard> getCards(String boardId, RetroCaller caller) {
        RetroBoard board = requireBoard(boardId);
        requireRead(board, caller);
        return getCards(boardId);
    }

    private static boolean isActionColumn(RetroBoard board, String columnKey) {
        return board.getColumns() != null && board.getColumns().stream()
                .anyMatch(c -> columnKey != null && columnKey.equals(c.getId()) && "action".equals(c.getTheme()));
    }

    private void validateCardFields(RetroBoard board, RetroCard c, boolean contentRequired) {
        if (contentRequired && (c.getContent() == null || c.getContent().isBlank())) {
            throw badRequest("O conteúdo do card não pode ser vazio.");
        }
        if (c.getContent() != null && c.getContent().length() > MAX_CARD_CONTENT) {
            throw badRequest("O conteúdo do card excede " + MAX_CARD_CONTENT + " caracteres.");
        }
        if (c.getColumnKey() != null) {
            if (c.getColumnKey().isBlank() || c.getColumnKey().length() > MAX_COLUMN_KEY) throw badRequest("columnKey inválido.");
            if (board.getColumns() != null && !board.getColumns().isEmpty()
                    && board.getColumns().stream().noneMatch(col -> c.getColumnKey().equals(col.getId()))) {
                throw badRequest("Coluna inexistente neste board.");
            }
        }
        if (c.getAssignee() != null && c.getAssignee().length() > MAX_SHORT_TEXT) throw badRequest("assignee muito longo.");
        if (c.getDueDate() != null && c.getDueDate().length() > MAX_SHORT_TEXT) throw badRequest("dueDate muito longo.");
        if (c.getCarriedFromBoardId() != null && c.getCarriedFromBoardId().length() > MAX_SHORT_TEXT) throw badRequest("carriedFromBoardId muito longo.");
        if (c.getCarriedFromBoardTitle() != null && c.getCarriedFromBoardTitle().length() > MAX_SHORT_TEXT) throw badRequest("carriedFromBoardTitle muito longo.");
        if (c.getParentId() != null && c.getParentId().length() > MAX_SHORT_TEXT) throw badRequest("parentId muito longo.");
        if (c.getOriginalTexts() != null) {
            if (c.getOriginalTexts().size() > MAX_ORIGINAL_TEXTS) throw badRequest("Histórico de fusão muito grande.");
            for (String t : c.getOriginalTexts()) {
                if (t != null && t.length() > MAX_CARD_CONTENT) throw badRequest("Texto do histórico muito longo.");
            }
        }
    }

    private void requireValidParent(String cardId, String parentId, String boardId) {
        if (parentId == null) return;
        if (parentId.equals(cardId)) throw badRequest("Um card não pode ser pai de si mesmo.");
        RetroCard parent = cardRepository.findById(parentId).orElse(null);
        if (parent == null || !boardId.equals(parent.getBoardId())) throw badRequest("Card pai inválido.");
    }

    /**
     * POST /api/retros/{id}/cards.
     * Criação: authorId = chamador, votos sempre vazios (voto só via /vote), id gerado se ausente.
     * Atualização: votes e authorId do corpo são ignorados (mantém o persistido). Autor/criador/ADMIN
     * editam tudo; outro participante só move (columnKey/order/parentId), marca feito e reage —
     * content/originalTexts/assignee/dueDate do corpo são descartados. Reações: cada um só altera as suas.
     */
    @Transactional
    public Saved<RetroCard> saveOrUpdateCard(RetroCard card, RetroCaller caller) {
        RetroBoard board = requireBoard(card.getBoardId());
        requireMember(board, caller);

        RetroCard existing = card.getId() == null || card.getId().isBlank()
                ? null : cardRepository.findById(card.getId()).orElse(null);
        if (existing != null && !existing.getBoardId().equals(card.getBoardId())) {
            throw forbidden("Cartão pertence a outro board.");
        }

        if (existing == null) {
            validateCardFields(board, card, true);
            if (card.getColumnKey() == null) throw badRequest("columnKey é obrigatório.");
            if (card.getId() == null || card.getId().isBlank()) card.setId(UUID.randomUUID().toString());
            requireValidParent(card.getId(), card.getParentId(), card.getBoardId());
            card.setVersion(null);
            card.setAuthorId(caller.id());
            card.setVotes(new LinkedHashSet<>());
            card.setReactions(mergeReactions(null, card.getReactions(), caller.id()));
            if (card.getOrder() == null) card.setOrder(0L);
            RetroCard saved = cardRepository.save(card);
            publish(card.getBoardId(), "CARD_SAVED", saved);
            return new Saved<>(saved, true);
        }

        boolean fullEdit = caller.id().equals(existing.getAuthorId()) || isCreatorOrAdmin(board, caller);
        validateCardFields(board, card, fullEdit);
        if (card.getColumnKey() != null) existing.setColumnKey(card.getColumnKey());
        if (card.getOrder() != null) existing.setOrder(card.getOrder());
        if (!Objects.equals(card.getParentId(), existing.getParentId())) {
            requireValidParent(existing.getId(), card.getParentId(), card.getBoardId());
            existing.setParentId(card.getParentId());
        }
        if (card.getIsDone() != null) existing.setIsDone(card.getIsDone());
        existing.setReactions(mergeReactions(existing.getReactions(), card.getReactions(), caller.id()));
        if (fullEdit) {
            if (card.getContent() != null) existing.setContent(card.getContent());
            existing.setAssignee(card.getAssignee());
            existing.setDueDate(card.getDueDate());
            if (card.getCarryCount() != null) existing.setCarryCount(card.getCarryCount());
            if (card.getCarriedFromBoardId() != null) existing.setCarriedFromBoardId(card.getCarriedFromBoardId());
            if (card.getCarriedFromBoardTitle() != null) existing.setCarriedFromBoardTitle(card.getCarriedFromBoardTitle());
            if (card.getOriginalTexts() != null) {
                existing.getOriginalTexts().clear();
                existing.getOriginalTexts().addAll(card.getOriginalTexts());
            }
        }
        RetroCard saved = cardRepository.save(existing);
        publish(card.getBoardId(), "CARD_SAVED", saved);
        return new Saved<>(saved, false);
    }

    /**
     * Reações de cada pessoa só mudam pela própria pessoa: parte do persistido, tira o chamador de
     * todos os tipos e o reaplica onde o corpo o inclui. Tipos desconhecidos são descartados.
     */
    static JsonNode mergeReactions(JsonNode stored, JsonNode incoming, String callerId) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        for (String type : List.of("up", "love", "wow", "concern")) {
            Set<String> users = new LinkedHashSet<>();
            if (stored != null && stored.has(type) && stored.get(type).isArray()) {
                stored.get(type).forEach(n -> users.add(n.asText()));
            }
            users.remove(callerId);
            if (incoming != null && incoming.has(type) && incoming.get(type).isArray()) {
                for (JsonNode n : incoming.get(type)) {
                    if (callerId.equals(n.asText())) users.add(callerId);
                }
            }
            var arr = result.putArray(type);
            users.forEach(arr::add);
        }
        return result;
    }

    @Transactional
    public void deleteCard(String boardId, String cardId, RetroCaller caller) {
        RetroBoard board = requireBoard(boardId);
        requireMember(board, caller);
        RetroCard card = cardRepository.findById(cardId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cartão não encontrado."));
        if (!card.getBoardId().equals(boardId)) {
            throw forbidden("Cartão não pertence a este board.");
        }
        if (!caller.id().equals(card.getAuthorId()) && !isCreatorOrAdmin(board, caller)) {
            throw forbidden("Só o autor ou o facilitador podem excluir este card.");
        }
        orphanChildren(boardId, cardId);
        cardRepository.deleteById(cardId);
        publish(boardId, "CARD_DELETED", Map.of("cardId", cardId));
    }

    /** Filhos de um grupo cujo pai sai ficam soltos (parentId null) em vez de órfãos. */
    private void orphanChildren(String boardId, String parentId) {
        List<RetroCard> children = cardRepository.findByBoardIdAndParentId(boardId, parentId);
        if (children == null) return;
        for (RetroCard child : children) {
            child.setParentId(null);
            publish(boardId, "CARD_SAVED", cardRepository.save(child));
        }
    }

    @Transactional
    public List<RetroCard> importActions(String boardId, List<RetroCard> cards, RetroCaller caller) {
        RetroBoard board = requireBoard(boardId);
        requireMember(board, caller);
        if (cards == null) throw badRequest("Corpo inválido.");
        if (cards.size() > MAX_IMPORT) throw badRequest("Máximo de " + MAX_IMPORT + " cards por importação.");
        List<RetroCard> result = new ArrayList<>();
        for (RetroCard card : cards) {
            card.setBoardId(boardId);
            validateCardFields(board, card, true);
            if (card.getColumnKey() == null) throw badRequest("columnKey é obrigatório.");
            RetroCard existing = card.getId() == null || card.getId().isBlank()
                    ? null : cardRepository.findById(card.getId()).orElse(null);
            if (existing != null) {
                // nunca sobrescreve/rouba card existente; reenvio da mesma importação é idempotente
                if (!existing.getBoardId().equals(boardId)) throw forbidden("Cartão pertence a outro board.");
                result.add(existing);
                continue;
            }
            if (card.getId() == null || card.getId().isBlank()) card.setId(UUID.randomUUID().toString());
            card.setParentId(null);
            card.setVersion(null);
            card.setAuthorId(caller.id());
            card.setVotes(new LinkedHashSet<>());
            card.setReactions(null);
            if (card.getOrder() == null) card.setOrder(0L);
            result.add(cardRepository.save(card));
        }
        publish(boardId, "CARDS_IMPORTED", result);
        return result;
    }

    // --- Votação ---

    /** Alterna o voto do chamador no card. Serializado pelo lock pessimista na linha do board. */
    @Transactional
    public RetroCard toggleVote(String boardId, String cardId, RetroCaller caller) {
        RetroBoard board = requireBoardForUpdate(boardId);
        requireMember(board, caller);
        RetroCard card = cardRepository.findById(cardId)
                .filter(c -> boardId.equals(c.getBoardId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cartão não encontrado."));
        if (!"active".equals(board.getVotingStatus())) {
            throw conflict("A votação não está aberta.");
        }
        if (isActionColumn(board, card.getColumnKey())) {
            throw conflict("Cards de ação não recebem votos.");
        }
        String uid = caller.id();
        if (card.getVotes().contains(uid)) {
            card.getVotes().remove(uid);
        } else {
            Integer max = board.getMaxVotesPerParticipant();
            if (max != null && max > 0) {
                long used = cardRepository.findByBoardId(boardId).stream()
                        .filter(c -> card.getColumnKey().equals(c.getColumnKey()) && c.getVotes().contains(uid))
                        .count();
                if (used >= max) {
                    throw conflict("Você já usou seus " + max + " votos neste painel.");
                }
            }
            card.getVotes().add(uid);
        }
        RetroCard saved = cardRepository.save(card);
        publish(boardId, "CARD_SAVED", saved);
        return saved;
    }

    /** Zera os votos de todos os cards e desativa a votação (criador/ADMIN). */
    @Transactional
    public RetroBoard resetVotes(String boardId, RetroCaller caller) {
        RetroBoard board = requireBoardForUpdate(boardId);
        if (!isCreatorOrAdmin(board, caller)) {
            throw forbidden("Apenas o facilitador do board pode zerar os votos.");
        }
        for (RetroCard card : cardRepository.findByBoardId(boardId)) {
            if (!card.getVotes().isEmpty()) {
                card.getVotes().clear();
                publish(boardId, "CARD_SAVED", cardRepository.save(card));
            }
        }
        board.setVotingStatus("disabled");
        RetroBoard saved = boardRepository.save(board);
        publish(boardId, "BOARD_UPDATED", saved);
        return saved;
    }

    /** Funde source em target atomicamente (histórico, votos sem repetição, filhos); apaga source. */
    @Transactional
    public RetroCard mergeCards(String boardId, String targetId, String sourceId, RetroCaller caller) {
        RetroBoard board = requireBoardForUpdate(boardId);
        requireMember(board, caller);
        if (targetId.equals(sourceId)) throw badRequest("Origem e destino são o mesmo card.");
        if (!Boolean.TRUE.equals(board.getIsCardsRevealed())) {
            throw conflict("Não é possível fundir cards durante a fase anônima.");
        }
        RetroCard target = cardRepository.findById(targetId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Cartão de destino não encontrado."));
        RetroCard source = cardRepository.findById(sourceId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Cartão de origem não encontrado."));
        if (!boardId.equals(target.getBoardId()) || !boardId.equals(source.getBoardId())) {
            throw forbidden("Cartão não pertence a este board.");
        }
        if (target.getColumnKey() == null || !target.getColumnKey().equals(source.getColumnKey())) {
            throw conflict("Só é possível fundir cards do mesmo painel.");
        }
        if (isActionColumn(board, target.getColumnKey())) {
            throw conflict("Cards de ação não podem ser fundidos.");
        }

        target.getOriginalTexts().add(source.getContent());
        target.getOriginalTexts().addAll(source.getOriginalTexts());
        target.getVotes().addAll(source.getVotes());
        if (sourceId.equals(target.getParentId())) target.setParentId(null);
        List<RetroCard> children = cardRepository.findByBoardIdAndParentId(boardId, sourceId);
        if (children != null) {
            for (RetroCard child : children) {
                if (child.getId().equals(targetId)) continue;
                child.setParentId(targetId);
                publish(boardId, "CARD_SAVED", cardRepository.save(child));
            }
        }
        cardRepository.deleteById(sourceId);
        RetroCard saved = cardRepository.save(target);
        publish(boardId, "CARD_DELETED", Map.of("cardId", sourceId));
        publish(boardId, "CARD_SAVED", saved);
        return saved;
    }

    // --- Chat do time ---
    static final int CHAT_MAX_TEXT = 8000;
    static final int CHAT_MAX_SENDER_NAME = 120;
    static final int CHAT_MAX_SENDER_CATEGORY = 255;
    static final int CHAT_HISTORY_LIMIT = 100;
    private static final Set<String> CHAT_KINDS = Set.of("text", "code");
    private static final Pattern CHAT_PUBLIC_CHANNEL = Pattern.compile("geral|role-(Developer|QA|UX|Designer|Management)");
    private static final java.time.format.DateTimeFormatter CHAT_TS_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC);

    @Transactional(readOnly = true)
    public List<RetroChatMessage> getChatMessages(String boardId, String channelId, String callerId) {
        RetroBoard board = requireChatBoard(boardId);
        requireChatParticipant(board, callerId);
        requireChatChannelAccess(board, channelId, callerId);
        // Só as últimas mensagens do canal (a carga inicial é uma chamada por canal).
        List<RetroChatMessage> latest = new java.util.ArrayList<>(chatMessageRepository
                .findByBoardIdAndChannelIdOrderByTsDesc(boardId, channelId,
                        org.springframework.data.domain.PageRequest.of(0, CHAT_HISTORY_LIMIT, org.springframework.data.domain.Sort.by("id").descending())));
        java.util.Collections.reverse(latest);
        return latest;
    }

    @Transactional
    public RetroChatMessage saveChatMessage(String boardId, RetroChatMessage message, String callerId) {
        if (message == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mensagem inválida.");
        }
        RetroBoard board = requireChatBoard(boardId);
        Optional<RetroParticipant> participant = requireChatParticipant(board, callerId);
        requireChatChannelAccess(board, message.getChannelId(), callerId);

        String text = message.getText();
        if (text == null || text.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A mensagem não pode ser vazia.");
        }
        if (text.length() > CHAT_MAX_TEXT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A mensagem excede " + CHAT_MAX_TEXT + " caracteres.");
        }
        String kind = message.getKind() == null || message.getKind().isBlank() ? "text" : message.getKind();
        if (!CHAT_KINDS.contains(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de mensagem inválido.");
        }
        // O nome exibido vem do cadastro do participante: o corpo da requisição não pode
        // fazer uma mensagem aparecer como se fosse de outra pessoa. Só o criador sem
        // registro de participante cai no nome informado (validado).
        String senderName = participant.map(RetroParticipant::getNickname)
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
        // id vindo do cliente nunca sobrescreve uma mensagem existente (de outro autor ou board)
        if (chatMessageRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma mensagem com este id.");
        }

        RetroChatMessage toSave = RetroChatMessage.builder()
                .id(id)
                .boardId(boardId)
                .channelId(message.getChannelId())
                .senderId(callerId) // sempre o caller autenticado, nunca o corpo
                .senderName(senderName)
                .senderCategory(message.getSenderCategory())
                .text(text)
                .kind(kind)
                .ts(CHAT_TS_FORMAT.format(java.time.Instant.now()))
                .build();
        RetroChatMessage saved = chatMessageRepository.save(toSave);
        publishChatEvent(board, "CHAT_MESSAGE_SAVED", saved, saved.getChannelId(), callerId);
        return saved;
    }

    @Transactional
    public void deleteChatMessage(String boardId, String messageId, String callerId) {
        RetroBoard board = requireChatBoard(boardId);
        requireChatParticipant(board, callerId);
        RetroChatMessage message = chatMessageRepository.findById(messageId)
                .filter(m -> boardId.equals(m.getBoardId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mensagem não encontrada"));

        boolean isAuthor = callerId.equals(message.getSenderId());
        boolean isDm = message.getChannelId() != null && message.getChannelId().startsWith("dm_");
        // Em DM só o autor apaga; nos canais públicos o criador do board também modera.
        boolean isModerator = !isDm && callerId.equals(board.getCreatorId());
        if (!isAuthor && !isModerator) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sem permissão para excluir esta mensagem.");
        }

        chatMessageRepository.delete(message);
        publishChatEvent(board, "CHAT_MESSAGE_DELETED", Map.of(
                "messageId", messageId,
                "channelId", message.getChannelId()
        ), message.getChannelId(), callerId);
    }

    private RetroBoard requireChatBoard(String boardId) {
        return boardRepository.findById(boardId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Board não encontrado"));
    }

    /** Exige que o caller seja o criador ou um participante; devolve o registro de participante, se houver. */
    private Optional<RetroParticipant> requireChatParticipant(RetroBoard board, String callerId) {
        if (callerId == null || callerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a participantes deste board.");
        }
        Optional<RetroParticipant> participant = participantRepository.findByBoardIdAndId(board.getId(), callerId);
        if (participant == null) participant = Optional.empty();
        if (participant.isEmpty() && !callerId.equals(board.getCreatorId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a participantes deste board.");
        }
        return participant;
    }

    private boolean isChatMember(RetroBoard board, String userId) {
        if (userId == null || userId.isBlank()) return false;
        if (userId.equals(board.getCreatorId())) return true;
        Optional<RetroParticipant> p = participantRepository.findByBoardIdAndId(board.getId(), userId);
        return p != null && p.isPresent();
    }

    /**
     * Par de um canal dm_{a}_{b} visto pelo caller. Com ids que contêm "_" o recorte é ambíguo
     * (caller pode ser a metade esquerda ou direita); o par é o candidato que é membro do board.
     * Devolve null se o caller não é uma das pontas ou nenhum candidato é membro.
     */
    private String resolveDmPeer(RetroBoard board, String channelId, String callerId) {
        String rest = channelId.substring(3);
        Set<String> candidates = new LinkedHashSet<>();
        String asFirst = callerId + "_";
        String asSecond = "_" + callerId;
        if (rest.startsWith(asFirst) && rest.length() > asFirst.length()) candidates.add(rest.substring(asFirst.length()));
        if (rest.endsWith(asSecond) && rest.length() > asSecond.length()) candidates.add(rest.substring(0, rest.length() - asSecond.length()));
        for (String peer : candidates) {
            if (isChatMember(board, peer)) return peer;
        }
        return null;
    }

    /**
     * Canais públicos vão para o board inteiro; DM só para as duas pessoas do canal
     * (o texto não pode trafegar para as sessões dos demais participantes).
     */
    private void publishChatEvent(RetroBoard board, String eventType, Object payload, String channelId, String callerId) {
        String boardId = board.getId();
        if (channelId != null && channelId.startsWith("dm_")) {
            String peer = resolveDmPeer(board, channelId, callerId);
            Set<String> users = new java.util.HashSet<>();
            users.add(callerId);
            if (peer != null) users.add(peer);
            publishToUsers(boardId, eventType, payload, users);
        } else {
            publish(boardId, eventType, payload);
        }
    }

    private void publishToUsers(String boardId, String eventType, Object payload, Set<String> users) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    webSocketHandler.broadcastEventToUsers(boardId, eventType, payload, users);
                }
            });
        } else {
            webSocketHandler.broadcastEventToUsers(boardId, eventType, payload, users);
        }
    }

    /** Canais válidos: geral, role-categoria e dm_uidA_uidB (só os dois uids acessam). */
    private void requireChatChannelAccess(RetroBoard board, String channelId, String callerId) {
        if (channelId == null || channelId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Canal inválido.");
        }
        if (CHAT_PUBLIC_CHANNEL.matcher(channelId).matches()) {
            return;
        }
        if (channelId.startsWith("dm_")) {
            if (resolveDmPeer(board, channelId, callerId) != null) {
                return;
            }
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sem acesso a esta conversa privada.");
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Canal inválido.");
    }
}
