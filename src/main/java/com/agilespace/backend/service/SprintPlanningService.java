package com.agilespace.backend.service;

import com.agilespace.backend.domain.SprintPlanning;
import com.agilespace.backend.domain.SprintPlanningMember;
import com.agilespace.backend.domain.SprintPlanningSubtask;
import com.agilespace.backend.domain.SprintPlanningTask;
import com.agilespace.backend.repository.SprintPlanningMemberRepository;
import com.agilespace.backend.repository.SprintPlanningRepository;
import com.agilespace.backend.repository.SprintPlanningSubtaskRepository;
import com.agilespace.backend.repository.SprintPlanningTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SprintPlanningService {

    // Mesma classe de proteção que ShowcaseSessionService aplica a tasks/members — os dois
    // são conteúdo sujeito ao mesmo risco de payload sem limite.
    private static final int MAX_TASKS = 2000;
    private static final int MAX_MEMBERS = 200;

    private final SprintPlanningRepository sprintPlanningRepository;
    private final SprintPlanningTaskRepository taskRepository;
    private final SprintPlanningSubtaskRepository subtaskRepository;
    private final SprintPlanningMemberRepository memberRepository;

    @Transactional(readOnly = true)
    public Optional<SprintPlanning> getPlanner(String id) {
        return sprintPlanningRepository.findById(id).map(this::attachChildren);
    }

    @Transactional
    public SprintPlanning saveOrUpdatePlanner(SprintPlanning planner, String callerId, boolean isAdmin) {
        ValidationSupport.requireNonBlank(planner.getTitle(), "title");
        ValidationSupport.requireMaxSize(planner.getTasks(), MAX_TASKS, "tasks");
        ValidationSupport.requireMaxSize(planner.getMembers(), MAX_MEMBERS, "members");

        Optional<SprintPlanning> existing = planner.getId() != null && !planner.getId().trim().isEmpty()
                ? sprintPlanningRepository.findById(planner.getId())
                : Optional.empty();
        if (existing.isPresent()) {
            requireOwnerOrAdmin(existing.get(), callerId, isAdmin);
            // Planejamento legado sem dono (createdBy vazio/"anonymous") é assumido por quem salvar primeiro.
            planner.setCreatedBy(hasOwner(existing.get()) ? existing.get().getCreatedBy() : callerId);
            planner.setCreatedAt(existing.get().getCreatedAt());
        } else {
            if (planner.getId() == null || planner.getId().trim().isEmpty()) {
                planner.setId(UUID.randomUUID().toString());
            }
            // Autor vem sempre do token validado, nunca do corpo — senão qualquer chamador
            // criaria um planejamento em nome de outra pessoa.
            planner.setCreatedBy(callerId);
        }
        if (planner.getCreatedAt() == null || planner.getCreatedAt().trim().isEmpty()) {
            planner.setCreatedAt(new java.util.Date().toString());
        }
        planner.setUpdatedAt(new java.util.Date().toString());

        SprintPlanning saved = sprintPlanningRepository.save(planner);
        replaceChildren(saved.getId(), planner.getTasks(), planner.getMembers());
        return attachChildren(saved);
    }

    @Transactional
    public void deletePlanner(String id, String callerId, boolean isAdmin) {
        SprintPlanning existing = sprintPlanningRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Planejamento não encontrado."));
        requireOwnerOrAdmin(existing, callerId, isAdmin);

        List<SprintPlanningTask> existingTasks = taskRepository.findByPlanningIdOrderByOrderAsc(id);
        if (!existingTasks.isEmpty()) {
            subtaskRepository.deleteByTaskIdIn(existingTasks.stream().map(SprintPlanningTask::getId).toList());
        }
        taskRepository.deleteByPlanningId(id);
        memberRepository.deleteByPlanningId(id);
        sprintPlanningRepository.deleteById(id);
    }

    /**
     * Leitura é por link (o UUID é o convite, igual a board público de Action Plan), mas
     * escrita é só do autor ou de um ADMIN. Antes disso o "somente leitura" existia só no
     * navegador — qualquer autenticado sobrescrevia ou apagava o planejamento alheio
     * mandando o id direto pra API.
     */
    private void requireOwnerOrAdmin(SprintPlanning planning, String callerId, boolean isAdmin) {
        if (isAdmin || !hasOwner(planning)) {
            return;
        }
        if (callerId == null || !callerId.equals(planning.getCreatedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Apenas o autor ou um administrador pode alterar este planejamento.");
        }
    }

    private boolean hasOwner(SprintPlanning planning) {
        String createdBy = planning.getCreatedBy();
        return createdBy != null && !createdBy.isBlank() && !"anonymous".equalsIgnoreCase(createdBy.trim());
    }

    private static final int MAX_LIST_LIMIT = 100;

    /**
     * Planejamentos prontos para o Poker. Planejamento não tem squad; para não expor o de outras pessoas,
     * quem não é admin só lista os que ele mesmo criou. O limite tem teto (antes: qualquer número).
     */
    @Transactional(readOnly = true)
    public List<SprintPlanning> listReadyForPoker(int limit, String callerId, boolean isAdmin) {
        PageRequest page = PageRequest.of(0, Math.min(MAX_LIST_LIMIT, Math.max(1, limit)));
        List<SprintPlanning> found = isAdmin
                ? sprintPlanningRepository.findReadyForPoker(page)
                : (callerId == null ? List.of() : sprintPlanningRepository.findReadyForPokerByCreator(callerId, page));
        return found.stream().map(this::attachChildren).toList();
    }

    // --- Montagem/gravação das tabelas filhas (sem relação JPA, mesmo padrão de ActionPlanTask/boardId) ---

    private SprintPlanning attachChildren(SprintPlanning planning) {
        List<SprintPlanningTask> tasks = taskRepository.findByPlanningIdOrderByOrderAsc(planning.getId());
        if (!tasks.isEmpty()) {
            List<String> taskIds = tasks.stream().map(SprintPlanningTask::getId).toList();
            List<SprintPlanningSubtask> allSubtasks = subtaskRepository.findByTaskIdInOrderByOrderAsc(taskIds);
            for (SprintPlanningTask task : tasks) {
                task.setSubtasks(allSubtasks.stream().filter(s -> s.getTaskId().equals(task.getId())).toList());
            }
        }
        planning.setTasks(tasks);
        planning.setMembers(memberRepository.findByPlanningIdOrderByOrderAsc(planning.getId()));
        return planning;
    }

    private static <T> Set<String> idsAlreadyTaken(List<String> sentIds, java.util.function.Function<List<String>, List<T>> finder,
                                                    java.util.function.Function<T, String> idOf) {
        List<String> ids = sentIds.stream().filter(i -> i != null && !i.isBlank()).distinct().toList();
        if (ids.isEmpty()) return Set.of();
        Set<String> taken = new HashSet<>();
        for (T found : finder.apply(ids)) taken.add(idOf.apply(found));
        return taken;
    }

    private void replaceChildren(String planningId, List<SprintPlanningTask> tasks, List<SprintPlanningMember> members) {
        List<SprintPlanningTask> existingTasks = taskRepository.findByPlanningIdOrderByOrderAsc(planningId);
        if (!existingTasks.isEmpty()) {
            subtaskRepository.deleteByTaskIdIn(existingTasks.stream().map(SprintPlanningTask::getId).toList());
        }
        taskRepository.deleteByPlanningId(planningId);
        memberRepository.deleteByPlanningId(planningId);

        List<SprintPlanningTask> safeTasks = tasks != null ? tasks : Collections.emptyList();
        // Depois do delete acima, qualquer id que ainda exista pertence a OUTRO planejamento: o save o
        // sobrescreveria e o tiraria de lá. Ids que colidem ganham um id novo.
        Set<String> takenTaskIds = idsAlreadyTaken(safeTasks.stream().map(SprintPlanningTask::getId).toList(), taskRepository::findByIdIn, SprintPlanningTask::getId);
        Set<String> takenSubtaskIds = idsAlreadyTaken(safeTasks.stream().filter(t -> t.getSubtasks() != null)
                .flatMap(t -> t.getSubtasks().stream()).map(SprintPlanningSubtask::getId).toList(), subtaskRepository::findByIdIn, SprintPlanningSubtask::getId);
        List<SprintPlanningSubtask> subtasksToSave = new ArrayList<>();
        int taskOrder = 0;
        for (SprintPlanningTask task : safeTasks) {
            if (task.getId() == null || task.getId().isBlank() || takenTaskIds.contains(task.getId())) {
                task.setId(UUID.randomUUID().toString());
            }
            task.setPlanningId(planningId);
            task.setOrder(taskOrder++);
            List<SprintPlanningSubtask> subtasks = task.getSubtasks();
            task.setSubtasks(null); // campo @Transient, não faz parte do INSERT da própria task
            taskRepository.save(task);

            if (subtasks != null) {
                int subtaskOrder = 0;
                for (SprintPlanningSubtask subtask : subtasks) {
                    if (subtask.getId() == null || subtask.getId().isBlank() || takenSubtaskIds.contains(subtask.getId())) {
                        subtask.setId(UUID.randomUUID().toString());
                    }
                    subtask.setTaskId(task.getId());
                    subtask.setOrder(subtaskOrder++);
                    subtasksToSave.add(subtask);
                }
            }
            task.setSubtasks(subtasks);
        }
        if (!subtasksToSave.isEmpty()) {
            subtaskRepository.saveAll(subtasksToSave);
        }

        List<SprintPlanningMember> safeMembers = members != null ? members : Collections.emptyList();
        Set<String> takenMemberIds = idsAlreadyTaken(safeMembers.stream().map(SprintPlanningMember::getId).toList(), memberRepository::findByIdIn, SprintPlanningMember::getId);
        int memberOrder = 0;
        for (SprintPlanningMember member : safeMembers) {
            if (member.getId() == null || member.getId().isBlank() || takenMemberIds.contains(member.getId())) {
                member.setId(UUID.randomUUID().toString());
            }
            member.setPlanningId(planningId);
            member.setOrder(memberOrder++);
        }
        if (!safeMembers.isEmpty()) {
            memberRepository.saveAll(safeMembers);
        }
    }
}
