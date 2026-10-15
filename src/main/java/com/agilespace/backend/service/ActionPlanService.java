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

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
    static final Set<String> STATUSES = Set.of("todo", "doing", "done", "blocked");

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
        if (board.getIsPublic() == null) {
            board.setIsPublic(true);
        }
        if (board.getParticipantIds() == null) {
            board.setParticipantIds(new HashSet<>());
        }
        if (board.getCreatorId() != null) {
            board.getParticipantIds().add(board.getCreatorId());
        }
        return boardRepository.save(board);
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
        return boardRepository.findBySprintIdOrderByCreatedAtDesc(sprintId);
    }

    /** Planos da squad, mais novos primeiro (limite {@value #MAX_LIST}). */
    @Transactional(readOnly = true)
    public List<ActionPlan> listBoardsBySquad(String squadId) {
        return boardRepository.findBySquad(squadId, org.springframework.data.domain.PageRequest.of(0, MAX_LIST));
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
