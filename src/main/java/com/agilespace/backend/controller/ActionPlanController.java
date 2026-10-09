package com.agilespace.backend.controller;

import com.agilespace.backend.domain.ActionPlan;
import com.agilespace.backend.domain.ActionPlanTask;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.ActionPlanService;
import com.agilespace.backend.service.CeremonyCaller;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/action-plans")
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class ActionPlanController {

    private final ActionPlanService actionPlanService;

    private static CeremonyCaller caller(HttpServletRequest request) {
        return new CeremonyCaller(
                (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID),
                (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE));
    }

    /**
     * Board público (padrão): qualquer autenticado com o link lê e edita. Board privado: só criador,
     * participante já adicionado ou ADMIN. Antes disso qualquer usuário autenticado
     * editava/apagava tarefa de qualquer board trocando o taskId.
     */
    private ActionPlan requireBoardAccess(UUID boardId, HttpServletRequest request) {
        ActionPlan board;
        try {
            board = actionPlanService.getBoardById(boardId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plano de ação não encontrado.");
        }
        CeremonyCaller caller = caller(request);
        if (caller.isAdmin() || Boolean.TRUE.equals(board.getIsPublic())) {
            return board;
        }
        boolean isCreator = caller.is(board.getCreatorId());
        boolean isParticipant = caller.isAuthenticated() && board.getParticipantIds().contains(caller.id());
        if (!isCreator && !isParticipant) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a participantes deste board.");
        }
        return board;
    }

    @PostMapping
    public ResponseEntity<ActionPlan> createBoard(@RequestBody ActionPlan board, HttpServletRequest request) {
        // creatorId vem sempre do token validado, nunca do corpo — senão qualquer
        // chamador autenticado poderia criar um board se passando por outra pessoa.
        board.setCreatorId(caller(request).id());
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
    public ResponseEntity<ActionPlan> getBoardById(@PathVariable("id") UUID id, HttpServletRequest request) {
        return ResponseEntity.ok(requireBoardAccess(id, request));
    }

    /**
     * Entrar no plano: o participante adicionado é sempre quem chama. ADMIN e o criador podem informar outro
     * {@code participantId}. Em board privado exige acesso prévio (senão seria a porta dos fundos do check).
     */
    @PostMapping("/{id}/participants")
    public ResponseEntity<ActionPlan> addParticipant(
            @PathVariable("id") UUID id,
            @RequestParam(value = "participantId", required = false) String participantId,
            HttpServletRequest request) {
        ActionPlan board = requireBoardAccess(id, request);
        CeremonyCaller caller = caller(request);
        String target = participantId == null || participantId.isBlank() ? caller.id() : participantId;
        if (!caller.is(target) && !caller.isAdmin() && !caller.is(board.getCreatorId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Só é possível adicionar a si mesmo.");
        }
        return ResponseEntity.ok(actionPlanService.addParticipant(id, target));
    }

    // Task Mappings
    @GetMapping("/{id}/tasks")
    public ResponseEntity<List<ActionPlanTask>> listTasks(@PathVariable("id") UUID id, HttpServletRequest request) {
        requireBoardAccess(id, request);
        return ResponseEntity.ok(actionPlanService.listTasks(id));
    }

    @PostMapping("/{id}/tasks")
    public ResponseEntity<ActionPlanTask> createTask(
            @PathVariable("id") UUID id,
            @RequestBody ActionPlanTask task,
            HttpServletRequest request) {
        requireBoardAccess(id, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(actionPlanService.createTask(id, task, caller(request).id()));
    }

    @PutMapping("/tasks/{taskId}")
    public ResponseEntity<ActionPlanTask> updateTask(
            @PathVariable("taskId") UUID taskId,
            @RequestBody ActionPlanTask task,
            HttpServletRequest request) {
        requireBoardAccess(taskBoardId(taskId), request);
        return ResponseEntity.ok(actionPlanService.updateTask(taskId, task));
    }

    @DeleteMapping("/tasks/{taskId}")
    public ResponseEntity<Void> deleteTask(@PathVariable("taskId") UUID taskId, HttpServletRequest request) {
        requireBoardAccess(taskBoardId(taskId), request);
        actionPlanService.deleteTask(taskId);
        return ResponseEntity.noContent().build();
    }

    private UUID taskBoardId(UUID taskId) {
        try {
            return actionPlanService.getTaskBoardId(taskId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tarefa não encontrada.");
        }
    }

    /** Em produção o Spring omite a mensagem das respostas de erro; as dos fluxos (em português) precisam chegar à tela. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "error", ex.getStatusCode().toString(),
                "message", ex.getReason() == null ? "Não foi possível concluir a operação." : ex.getReason()));
    }
}
