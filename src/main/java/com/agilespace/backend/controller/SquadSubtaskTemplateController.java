package com.agilespace.backend.controller;

import com.agilespace.backend.domain.SquadSubtaskTemplate;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.SquadAccessService;
import com.agilespace.backend.service.SquadSubtaskTemplateService;
import com.agilespace.backend.service.SubtaskTypeMappingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Sub-tarefas que a squad cria no Jira por tipo de issue pai. Leitura para qualquer membro (o Poker precisa
 * montar a prévia); escrita só para liderança da squad ou admin.
 */
@RestController
@RequestMapping("/api/squads/{squadId}/subtask-templates")
@RequiredArgsConstructor
public class SquadSubtaskTemplateController {

    private final SquadSubtaskTemplateService service;
    private final SquadAccessService squadAccessService;
    private final SubtaskTypeMappingService subtaskTypeMappingService;

    @GetMapping
    public ResponseEntity<List<SquadSubtaskTemplate>> list(@PathVariable String squadId, HttpServletRequest request) {
        squadAccessService.requireSquadReadAccess(squadId, request);
        return ResponseEntity.ok(service.list(squadId));
    }

    @PutMapping
    public ResponseEntity<List<SquadSubtaskTemplate>> replaceAll(@PathVariable String squadId,
                                                                 @RequestBody List<SquadSubtaskTemplate> templates,
                                                                 HttpServletRequest request) {
        requireManage(squadId, request);
        return ResponseEntity.ok(service.replaceAll(squadId, templates));
    }

    /** Tipos de issue do projeto no Jira (com a flag subtask), com o token Jira de quem pede. */
    @GetMapping("/jira-issue-types")
    public ResponseEntity<List<Map<String, Object>>> listJiraIssueTypes(@PathVariable String squadId, HttpServletRequest request) {
        squadAccessService.requireSquadReadAccess(squadId, request);
        String callerId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        return ResponseEntity.ok(service.listJiraIssueTypes(squadId, callerId));
    }

    /**
     * Papel que o Sprint Planner dá a cada tipo de sub-tarefa (mapeamento mais recente da squad). A tela usa como padrão
     * ao adicionar uma linha e para avisar quando o papel configurado aqui diverge.
     */
    @GetMapping("/suggested-roles")
    public ResponseEntity<List<SubtaskTypeMappingService.SuggestedRole>> suggestedRoles(@PathVariable String squadId, HttpServletRequest request) {
        squadAccessService.requireSquadReadAccess(squadId, request);
        return ResponseEntity.ok(subtaskTypeMappingService.suggestedRoles(squadId));
    }

    private void requireManage(String squadId, HttpServletRequest request) {
        if (!squadAccessService.canManageSquad(squadId, request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Só a liderança da squad ou administradores alteram as sub-tarefas do time.");
        }
    }

    // Em produção o Spring oculta a razão das ResponseStatusException (include-message: never);
    // aqui a mensagem é orientação ao usuário (ex.: configurar sub-tarefas), então devolvemos explícito.
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "error", String.valueOf(ex.getStatusCode().value()),
                "message", ex.getReason() == null ? "" : ex.getReason()
        ));
    }
}
