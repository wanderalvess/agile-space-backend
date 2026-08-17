package com.agilespace.backend.service;

import com.agilespace.backend.domain.ActionPlan;
import com.agilespace.backend.domain.ActionPlanTask;
import com.agilespace.backend.repository.ActionPlanRepository;
import com.agilespace.backend.repository.ActionPlanTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ActionPlanService {

    private final ActionPlanRepository boardRepository;
    private final ActionPlanTaskRepository taskRepository;

    @Transactional
    public ActionPlan createBoard(ActionPlan board) {
        if (board.getIsPublic() == null) {
            board.setIsPublic(true);
        }
        return boardRepository.save(board);
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

    @Transactional
    public ActionPlanTask createTask(UUID boardId, ActionPlanTask task) {
        // Garantir que a tarefa está associada ao board
        task.setBoardId(boardId);
        
        // Se a ordem não for fornecida, calcula como o final da lista
        if (task.getOrder() == null) {
            List<ActionPlanTask> existing = listTasks(boardId);
            task.setOrder(existing.size());
        }

        return taskRepository.save(task);
    }

    @Transactional
    public ActionPlanTask updateTask(UUID taskId, ActionPlanTask updated) {
        ActionPlanTask existing = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found with id: " + taskId));
        
        existing.setWhat(updated.getWhat());
        existing.setWhy(updated.getWhy());
        existing.setWhere(updated.getWhere());
        existing.setWhen(updated.getWhen());
        existing.setWho(updated.getWho());
        existing.setHow(updated.getHow());
        existing.setHowMuch(updated.getHowMuch());
        existing.setStatus(updated.getStatus());
        existing.setOrder(updated.getOrder());
        
        return taskRepository.save(existing);
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
