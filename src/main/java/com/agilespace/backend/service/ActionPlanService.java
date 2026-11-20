package com.agilespace.backend.service;

import com.agilespace.backend.domain.ActionPlan;
import com.agilespace.backend.domain.ActionPlanTask;
import com.agilespace.backend.repository.ActionPlanRepository;
import com.agilespace.backend.repository.ActionPlanTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Regras do Plano de Ação (5W2H). A edição de tarefa é parcial: campo ausente (null) não muda, texto vazio limpa.
 * Antes, editar um único campo na tabela enviava só aquele campo e o servidor apagava todos os outros.
 */
@Service
@RequiredArgsConstructor
public class ActionPlanService {

    static final int MAX_TITLE = 255;
    static final int MAX_TEXT = 5000;
    static final int MAX_SHORT = 255;
    static final int MAX_SOURCE_ID = 80;
    static final Set<String> STATUSES = Set.of("todo", "doing", "done", "blocked");
    static final String SOURCE_MANUAL = "MANUAL";
    /** Origens aceitas do plano (V78). */
    static final Set<String> SOURCE_TYPES = Set.of(SOURCE_MANUAL, "RETRO", "HEALTH_CHECK", "BRAINSTORMING", "SHOWCASE");

    private final ActionPlanRepository boardRepository;
    private final ActionPlanTaskRepository taskRepository;

    /** Opcionais (testes que montam o serviço à mão seguem sem eles). */
    private SquadAudienceService audienceService;
    private NotificationTriggers notificationTriggers;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAudienceService(SquadAudienceService audienceService) {
        this.audienceService = audienceService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setNotificationTriggers(NotificationTriggers notificationTriggers) {
        this.notificationTriggers = notificationTriggers;
    }

    static final int MAX_LIST = 100;

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    /** Texto aparado e dentro do limite; null continua null (= "não mexer"). */
    private static String text(String value, int max, String label) {
        if (value == null) {
            return null;
        }
        String clean = value.strip();
        if (clean.length() > max) {
            throw badRequest(label + " excede " + max + " caracteres.");
        }
        return clean;
    }

    private static String status(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!STATUSES.contains(normalized)) {
            throw badRequest("Status inválido.");
        }
        return normalized;
    }

    /** Tipo de origem em maiúsculas; null/vazio continua null (= "não informado"). Fora da lista: 400. */
    static String sourceType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!SOURCE_TYPES.contains(normalized)) {
            throw badRequest("Origem do plano inválida.");
        }
        return normalized;
    }

    /** Texto aparado e dentro do limite; vazio vira null (campo opcional que o cliente deixou em branco). */
    private static String optional(String value, int max, String label) {
        String clean = text(value, max, label);
        return clean == null || clean.isEmpty() ? null : clean;
    }

    /** Chave de escopo da trava de origem: a squad em maiúsculas, ou '' para plano sem squad (igual ao índice V78). */
    private static String scope(String squadId) {
        return squadId == null ? "" : squadId.toUpperCase(Locale.ROOT);
    }

    /** Valida e normaliza a origem do plano em criação (tipo, id, título). MANUAL não tem id. */
    private void applySource(ActionPlan board) {
        String type = sourceType(board.getSourceType());
        String sourceId = optional(board.getSourceId(), MAX_SOURCE_ID, "O id da origem");
        String sourceTitle = optional(board.getSourceTitle(), MAX_TITLE, "O título da origem");
        if (type == null || SOURCE_MANUAL.equals(type)) {
            if (sourceId != null) {
                throw badRequest("O id da origem só vale quando a origem do plano não é MANUAL.");
            }
            board.setSourceType(SOURCE_MANUAL);
            board.setSourceId(null);
            board.setSourceTitle(null);
            return;
        }
        if (sourceId == null) {
            throw badRequest("O id da origem é obrigatório para a origem " + type + ".");
        }
        board.setSourceType(type);
        board.setSourceId(sourceId);
        board.setSourceTitle(sourceTitle);
    }

    /** Plano já existente da mesma origem no mesmo escopo (squad ou "sem squad"), se houver. */
    @Transactional(readOnly = true)
    public Optional<ActionPlan> findExistingBySource(String squadId, String type, String sourceId) {
        if (type == null || sourceId == null) {
            return Optional.empty();
        }
        return boardRepository.findBySourceInScope(scope(squadId), type, sourceId).stream().findFirst();
    }

    @Transactional
    public ActionPlan createBoard(ActionPlan board) {
        String title = text(board.getTitle(), MAX_TITLE, "O título");
        if (title == null || title.isEmpty()) {
            throw badRequest("O título é obrigatório.");
        }
        board.setId(null); // o id é sempre gerado aqui: um id do corpo faria o save sobrescrever outro plano
        board.setTitle(title);
        board.setTeam(text(board.getTeam(), MAX_TITLE, "A squad"));
        board.setSprintId(text(board.getSprintId(), 100, "A sprint"));
        board.setSquadId(squadIdFor(board.getSquadId(), board.getTeam()));
        applySource(board);
        board.setTasksTotal(null);
        board.setTasksDone(null);
        boolean hasSource = board.getSourceId() != null;
        if (hasSource) {
            Optional<ActionPlan> existing = findExistingBySource(board.getSquadId(), board.getSourceType(), board.getSourceId());
            if (existing.isPresent()) {
                throw new ActionPlanSourceConflictException(existing.get());
            }
        }
        if (board.getIsPublic() == null) {
            board.setIsPublic(true);
        }
        if (board.getParticipantIds() == null) {
            board.setParticipantIds(new HashSet<>());
        }
        if (board.getCreatorId() != null) {
            board.getParticipantIds().add(board.getCreatorId());
        }
        ActionPlan saved = boardRepository.save(board);
        if (hasSource) {
            // Força o INSERT agora: duas criações simultâneas da mesma origem esbarram no índice único parcial aqui
            // (DataIntegrityViolationException), e não só no commit, onde o controller não consegue traduzir.
            boardRepository.flush();
        }
        return saved;
    }

    /** Edição parcial do board: título (obrigatório se enviado, mesmo limite da criação) e visibilidade. */
    @Transactional
    public ActionPlan updateBoard(UUID id, ActionPlan updated) {
        ActionPlan existing = getBoardById(id);
        if (updated.getTitle() != null) {
            String title = text(updated.getTitle(), MAX_TITLE, "O título");
            if (title.isEmpty()) {
                throw badRequest("O título é obrigatório.");
            }
            existing.setTitle(title);
        }
        if (updated.getIsPublic() != null) {
            existing.setIsPublic(updated.getIsPublic());
        }
        return boardRepository.save(existing);
    }

    /** Apaga o board com todas as tarefas. */
    @Transactional
    public void deleteBoard(UUID id) {
        if (!boardRepository.existsById(id)) {
            throw new IllegalArgumentException("Action Plan not found with id: " + id);
        }
        taskRepository.deleteByBoardId(id);
        boardRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public List<ActionPlan> listBoardsBySprintId(String sprintId) {
        return withCounts(boardRepository.findBySprintIdOrderByCreatedAtDesc(sprintId));
    }

    private static org.springframework.data.domain.Pageable page() {
        return org.springframework.data.domain.PageRequest.of(0, MAX_LIST);
    }

    /** Planos da squad, mais novos primeiro (limite {@value #MAX_LIST}), com {@code tasksTotal}/{@code tasksDone}. */
    @Transactional(readOnly = true)
    public List<ActionPlan> listBoardsBySquad(String squadId) {
        return withCounts(boardRepository.findBySquad(squadId, page()));
    }

    /**
     * Planos da squad filtrados por origem: só o tipo, ou tipo + id ("o plano deste radar"). {@code sourceId} sem tipo é 400;
     * os dois ausentes = a listagem normal.
     */
    @Transactional(readOnly = true)
    public List<ActionPlan> listBoardsBySquad(String squadId, String sourceType, String sourceId) {
        String type = sourceType(sourceType);
        String id = optional(sourceId, MAX_SOURCE_ID, "O id da origem");
        if (id != null && type == null) {
            throw badRequest("sourceId exige sourceType.");
        }
        if (type == null) {
            return listBoardsBySquad(squadId);
        }
        return withCounts(id == null
                ? boardRepository.findBySquadAndSourceType(squadId, type, page())
                : boardRepository.findBySquadAndSource(squadId, type, id, page()));
    }

    /** Planos de uma origem em qualquer squad (quem chama filtra visibilidade e acesso à squad). */
    @Transactional(readOnly = true)
    public List<ActionPlan> listBoardsBySource(String sourceType, String sourceId) {
        String type = sourceType(sourceType);
        String id = optional(sourceId, MAX_SOURCE_ID, "O id da origem");
        if (type == null || id == null) {
            throw badRequest("A busca por origem exige sourceType e sourceId.");
        }
        return withCounts(boardRepository.findBySource(type, id, page()));
    }

    /** Preenche {@code tasksTotal}/{@code tasksDone} de cada plano com uma única consulta agregada. */
    private List<ActionPlan> withCounts(List<ActionPlan> boards) {
        if (boards == null || boards.isEmpty()) {
            return boards;
        }
        Map<UUID, ActionPlanTaskRepository.BoardTaskCount> counts = new HashMap<>();
        for (ActionPlanTaskRepository.BoardTaskCount c : taskRepository.countByBoards(boards.stream().map(ActionPlan::getId).toList())) {
            counts.put(c.getBoardId(), c);
        }
        for (ActionPlan board : boards) {
            ActionPlanTaskRepository.BoardTaskCount c = counts.get(board.getId());
            board.setTasksTotal(c == null || c.getTotal() == null ? 0 : c.getTotal().intValue());
            board.setTasksDone(c == null || c.getDone() == null ? 0 : c.getDone().intValue());
        }
        return boards;
    }

    /** Squad que o plano terá (a do corpo ou o "team", se for o id de uma squad cadastrada); null = sem squad. */
    public String resolveSquadId(ActionPlan board) {
        return squadIdFor(text(board.getSquadId(), 100, "A squad"), text(board.getTeam(), MAX_TITLE, "A squad"));
    }

    /**
     * squadId do plano: o informado ou o "team", desde que seja o id de uma squad/projeto cadastrado (o controller já
     * conferiu o acesso do criador). Texto livre ("Squad Geral") não vira squad.
     */
    String squadIdFor(String explicitSquadId, String team) {
        if (audienceService == null) return null;
        for (String candidate : new String[]{explicitSquadId, team}) {
            if (candidate == null || candidate.isBlank()) continue;
            String id = candidate.trim();
            if (audienceService.squadNames(List.of(id)).containsKey(id)) return id;
        }
        return null;
    }

    @Transactional(readOnly = true)
    public ActionPlan getBoardById(UUID id) {
        return boardRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Action Plan not found with id: " + id));
    }

    @Transactional(readOnly = true)
    public List<ActionPlanTask> listTasks(UUID boardId) {
        return taskRepository.findByBoardIdOrderByOrderAsc(boardId);
    }

    /** Cria a tarefa no board da URL. Autor = quem chama; id, datas e board do corpo são ignorados. */
    @Transactional
    public ActionPlanTask createTask(UUID boardId, ActionPlanTask task, String authorId) {
        String what = text(task.getWhat(), MAX_TEXT, "O quê");
        if (what == null || what.isEmpty()) {
            throw badRequest("O campo \"O quê\" é obrigatório.");
        }
        ActionPlanTask clean = ActionPlanTask.builder()
                .boardId(boardId)
                .authorId(authorId)
                .what(what)
                .why(text(task.getWhy(), MAX_TEXT, "Por quê"))
                .where(text(task.getWhere(), MAX_TEXT, "Onde"))
                .when(text(task.getWhen(), MAX_TEXT, "Quando"))
                .who(text(task.getWho(), MAX_SHORT, "Quem"))
                .how(text(task.getHow(), MAX_TEXT, "Como"))
                .howMuch(text(task.getHowMuch(), MAX_SHORT, "Quanto"))
                .status(status(task.getStatus()) == null ? "todo" : status(task.getStatus()))
                .order(task.getOrder())
                .sourceItemId(optional(task.getSourceItemId(), MAX_SOURCE_ID, "O id do item de origem"))
                .sourceLabel(optional(task.getSourceLabel(), MAX_SHORT, "O rótulo do item de origem"))
                .dueDate(task.getDueDate())
                .build();
        // Se a ordem não for fornecida, vai para o final da lista
        if (clean.getOrder() == null) {
            clean.setOrder(listTasks(boardId).size());
        }
        ActionPlanTask saved = taskRepository.save(clean);
        notifyWho(saved, null, authorId);
        return saved;
    }

    @Transactional(readOnly = true)
    public UUID getTaskBoardId(UUID taskId) {
        return taskRepository.findById(taskId)
                .map(ActionPlanTask::getBoardId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found with id: " + taskId));
    }

    /** Edição parcial: só os campos enviados (não nulos) mudam; texto vazio limpa o campo. */
    @Transactional
    public ActionPlanTask updateTask(UUID taskId, ActionPlanTask updated) {
        return updateTask(taskId, updated, null);
    }

    /** Como {@link #updateTask(UUID, ActionPlanTask)}; {@code actorId} = quem edita (não é notificado de si mesmo). */
    @Transactional
    public ActionPlanTask updateTask(UUID taskId, ActionPlanTask updated, String actorId) {
        ActionPlanTask existing = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found with id: " + taskId));
        String previousWho = existing.getWho();

        if (updated.getWhat() != null) {
            String what = text(updated.getWhat(), MAX_TEXT, "O quê");
            if (what.isEmpty()) {
                throw badRequest("O campo \"O quê\" não pode ficar vazio.");
            }
            existing.setWhat(what);
        }
        if (updated.getWhy() != null) existing.setWhy(text(updated.getWhy(), MAX_TEXT, "Por quê"));
        if (updated.getWhere() != null) existing.setWhere(text(updated.getWhere(), MAX_TEXT, "Onde"));
        if (updated.getWhen() != null) existing.setWhen(text(updated.getWhen(), MAX_TEXT, "Quando"));
        if (updated.getWho() != null) existing.setWho(text(updated.getWho(), MAX_SHORT, "Quem"));
        if (updated.getHow() != null) existing.setHow(text(updated.getHow(), MAX_TEXT, "Como"));
        if (updated.getHowMuch() != null) existing.setHowMuch(text(updated.getHowMuch(), MAX_SHORT, "Quanto"));
        if (updated.getStatus() != null) existing.setStatus(status(updated.getStatus()));
        if (updated.getOrder() != null) existing.setOrder(updated.getOrder());
        // A origem da ação (sourceItemId/sourceLabel) é imutável; o prazo muda, e clearDueDate=true o limpa.
        if (Boolean.TRUE.equals(updated.getClearDueDate())) {
            existing.setDueDate(null);
        } else if (updated.getDueDate() != null) {
            existing.setDueDate(updated.getDueDate());
        }

        ActionPlanTask saved = taskRepository.save(existing);
        notifyWho(saved, previousWho, actorId);
        return saved;
    }

    /** Responsável ("quem") novo ou trocado: notifica a pessoa, se der para achá-la na squad do plano. */
    private void notifyWho(ActionPlanTask task, String previousWho, String actorId) {
        String who = task.getWho();
        if (notificationTriggers == null || who == null || who.isBlank()
                || who.trim().equalsIgnoreCase(previousWho == null ? "" : previousWho.trim())) {
            return;
        }
        ActionPlan plan = boardRepository.findById(task.getBoardId()).orElse(null);
        if (plan == null || plan.getSquadId() == null) return;
        notificationTriggers.actionPlanTaskAssigned(plan.getId().toString(), plan.getTitle(), plan.getSquadId(),
                String.valueOf(task.getId()), task.getWhat(), who, actorId);
    }

    @Transactional
    public void deleteTask(UUID taskId) {
        if (!taskRepository.existsById(taskId)) {
            throw new IllegalArgumentException("Task not found with id: " + taskId);
        }
        taskRepository.deleteById(taskId);
    }

    @Transactional
    public ActionPlan addParticipant(UUID boardId, String participantId) {
        ActionPlan board = getBoardById(boardId);
        board.getParticipantIds().add(participantId);
        return boardRepository.save(board);
    }
}
