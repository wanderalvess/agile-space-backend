package com.agilespace.backend.controller;

import com.agilespace.backend.domain.ActionPlan;
import com.agilespace.backend.domain.ActionPlanTask;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.ActionPlanService;
import com.agilespace.backend.service.ActionPlanSourceConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import com.agilespace.backend.service.CeremonyCaller;
import com.agilespace.backend.service.SquadAccessService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/action-plans")
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class ActionPlanController {

    private final ActionPlanService actionPlanService;
    private final SquadAccessService squadAccessService;

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
        // Só quem lê a squad cria plano nela (antes da trava de origem: quem não é da squad nunca vê o 409).
        // team de texto livre não é squad e não pede nada. sourceId é só rótulo: não concede acesso.
        String squadId = actionPlanService.resolveSquadId(board);
        if (squadId != null) {
            squadAccessService.requireSquadReadAccess(squadId, request);
        }
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(actionPlanService.createBoard(board));
        } catch (DataIntegrityViolationException e) {
            // Corrida: duas criações da mesma origem ao mesmo tempo; o índice único parcial barrou a segunda.
            ActionPlan existing = actionPlanService
                    .findExistingBySource(board.getSquadId(), board.getSourceType(), board.getSourceId())
                    .orElse(null);
            if (existing == null) {
                throw e;
            }
            throw new ActionPlanSourceConflictException(existing);
        }
    }

    private static boolean canSee(CeremonyCaller caller, ActionPlan b) {
        return caller.isAdmin() || Boolean.TRUE.equals(b.getIsPublic()) || caller.is(b.getCreatorId())
                || (caller.isAuthenticated() && b.getParticipantIds().contains(caller.id()));
    }

    /** Listagem sem filtro de origem (o comportamento de antes da V78). */
    public ResponseEntity<List<ActionPlan>> listBoards(String sprintId, String squadId, HttpServletRequest request) {
        return listBoards(sprintId, squadId, null, null, request);
    }

    @GetMapping
    public ResponseEntity<List<ActionPlan>> listBoards(
            @RequestParam(value = "sprintId", required = false) String sprintId,
            @RequestParam(value = "squadId", required = false) String squadId,
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestParam(value = "sourceId", required = false) String sourceId,
            HttpServletRequest request) {
        boolean hasSourceType = sourceType != null && !sourceType.isBlank();
        boolean hasSourceId = sourceId != null && !sourceId.isBlank();
        if (hasSourceId && !hasSourceType) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceId exige sourceType.");
        }
        // Por squad: exige acesso à squad (planos privados só aparecem para criador/participante/ADMIN).
        if (squadId != null && !squadId.isBlank()) {
            squadAccessService.requireSquadReadAccess(squadId, request);
            CeremonyCaller caller = caller(request);
            List<ActionPlan> boards = (hasSourceType
                    ? actionPlanService.listBoardsBySquad(squadId, sourceType, sourceId)
                    : actionPlanService.listBoardsBySquad(squadId)).stream()
                    .filter(b -> canSee(caller, b))
                    .toList();
            return ResponseEntity.ok(boards);
        }
        if (hasSourceType && hasSourceId && (sprintId == null || sprintId.isBlank())) {
            // "O plano deste radar" sem saber a squad: só o que o chamador vê, e de squad que ele lê (sem squad entra).
            CeremonyCaller caller = caller(request);
            List<ActionPlan> boards = actionPlanService.listBoardsBySource(sourceType, sourceId).stream()
                    .filter(b -> canSee(caller, b))
                    .filter(b -> b.getSquadId() == null || squadAccessService.matchesSquad(b.getSquadId(), request))
                    .toList();
            return ResponseEntity.ok(boards);
        }
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

    /** Editar/apagar o board em si: só o criador ou ADMIN (participante comum edita tarefas, não o board). */
    private ActionPlan requireBoardOwner(UUID boardId, HttpServletRequest request) {
        ActionPlan board = requireBoardAccess(boardId, request);
        CeremonyCaller caller = caller(request);
        if (!caller.isAdmin() && !caller.is(board.getCreatorId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Apenas o criador do plano ou um administrador pode alterá-lo.");
        }
        return board;
    }

    @RequestMapping(value = "/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public ResponseEntity<ActionPlan> updateBoard(
            @PathVariable("id") UUID id,
            @RequestBody ActionPlan body,
            HttpServletRequest request) {
        requireBoardOwner(id, request);
        return ResponseEntity.ok(actionPlanService.updateBoard(id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBoard(@PathVariable("id") UUID id, HttpServletRequest request) {
        requireBoardOwner(id, request);
        actionPlanService.deleteBoard(id);
        return ResponseEntity.noContent().build();
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
        return ResponseEntity.ok(actionPlanService.updateTask(taskId, task, caller(request).id()));
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

    /**
     * 409 de origem repetida: {@code existingPlanId} só vai se quem chama pode ver o plano existente (um privado
     * alheio não vaza o id).
     */
    @ExceptionHandler(ActionPlanSourceConflictException.class)
    public ResponseEntity<Map<String, String>> handleSourceConflict(ActionPlanSourceConflictException ex, HttpServletRequest request) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", ex.getStatusCode().toString());
        body.put("message", ex.getReason());
        ActionPlan existing = ex.getExisting();
        if (existing != null && existing.getId() != null && canSee(caller(request), existing)) {
            body.put("existingPlanId", existing.getId().toString());
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /** Em produção o Spring omite a mensagem das respostas de erro; as dos fluxos (em português) precisam chegar à tela. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "error", ex.getStatusCode().toString(),
                "message", ex.getReason() == null ? "Não foi possível concluir a operação." : ex.getReason()));
    }
}
