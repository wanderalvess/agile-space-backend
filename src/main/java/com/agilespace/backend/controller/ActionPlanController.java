package com.agilespace.backend.controller;

import com.agilespace.backend.domain.ActionPlan;
import com.agilespace.backend.domain.ActionPlanTask;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.ActionPlanService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/action-plans")
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class ActionPlanController {

    private final ActionPlanService actionPlanService;

    /**
     * Board público (padrão): qualquer autenticado edita. Board privado: só criador,
     * participante já adicionado ou ADMIN. Antes disso qualquer usuário autenticado
     * editava/apagava tarefa de qualquer board trocando o taskId.
     */
    private void requireBoardAccess(UUID boardId, HttpServletRequest request) {
        String role = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
        if ("ADMIN".equalsIgnoreCase(role)) {
            return;
        }
        ActionPlan board;
        try {
            board = actionPlanService.getBoardById(boardId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Action Plan not found with id: " + boardId);
        }
        if (Boolean.TRUE.equals(board.getIsPublic())) {
            return;
        }
        String callerId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        boolean isCreator = callerId != null && callerId.equals(board.getCreatorId());
        boolean isParticipant = callerId != null && board.getParticipantIds().contains(callerId);
        if (!isCreator && !isParticipant) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a participantes deste board.");
        }
    }

    @PostMapping
    public ResponseEntity<ActionPlan> createBoard(@Valid @RequestBody ActionPlan board) {
        return ResponseEntity.status(HttpStatus.CREATED).body(actionPlanService.createBoard(board));
    }

    @GetMapping
    public ResponseEntity<List<ActionPlan>> listBoards(
            @RequestParam(value = "sprintId", required = false) String sprintId) {
        if (sprintId == null || sprintId.isBlank()) {
            return ResponseEntity.ok(List.of());
        }
        // Listagem por sprintId é ampla (não pede o UUID do board), então só
        // devolve boards públicos aqui — igual a getBoardById/requireBoardAccess,
        // um board privado não deve ficar descobrível por enumeração de sprintId.
        List<ActionPlan> publicBoards = actionPlanService.listBoardsBySprintId(sprintId).stream()
                .filter(b -> Boolean.TRUE.equals(b.getIsPublic()))
                .toList();
        return ResponseEntity.ok(publicBoards);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ActionPlan> getBoardById(@PathVariable("id") UUID id) {
        try {
            return ResponseEntity.ok(actionPlanService.getBoardById(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/participants")
    public ResponseEntity<ActionPlan> addParticipant(
            @PathVariable("id") UUID id,
            @RequestParam("participantId") String participantId) {
        try {
            return ResponseEntity.ok(actionPlanService.addParticipant(id, participantId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // Task Mappings
    @GetMapping("/{id}/tasks")
    public ResponseEntity<List<ActionPlanTask>> listTasks(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(actionPlanService.listTasks(id));
    }

    @PostMapping("/{id}/tasks")
    public ResponseEntity<ActionPlanTask> createTask(
            @PathVariable("id") UUID id,
            @Valid @RequestBody ActionPlanTask task) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(actionPlanService.createTask(id, task));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/tasks/{taskId}")
    public ResponseEntity<ActionPlanTask> updateTask(
            @PathVariable("taskId") UUID taskId,
            @Valid @RequestBody ActionPlanTask task,
            HttpServletRequest request) {
        try {
            requireBoardAccess(actionPlanService.getTaskBoardId(taskId), request);
            return ResponseEntity.ok(actionPlanService.updateTask(taskId, task));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/tasks/{taskId}")
    public ResponseEntity<Void> deleteTask(@PathVariable("taskId") UUID taskId, HttpServletRequest request) {
        try {
            requireBoardAccess(actionPlanService.getTaskBoardId(taskId), request);
            actionPlanService.deleteTask(taskId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
