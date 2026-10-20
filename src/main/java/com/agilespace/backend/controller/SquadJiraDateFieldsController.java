package com.agilespace.backend.controller;

import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.SquadAccessService;
import com.agilespace.backend.service.SquadJiraDateFieldsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Campos do Jira com as datas e horas planejadas que alimentam o cronograma da squad.
 * Leitura: qualquer membro; escrita: só liderança da squad (ou admin), como o resto da configuração.
 */
@RestController
@RequestMapping("/api/squads/{squadId}/jira-date-fields")
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class SquadJiraDateFieldsController {

    public record UpdateRequest(String startFieldId, String endFieldId) {}

    private final SquadJiraDateFieldsService service;
    private final SquadAccessService squadAccessService;

    @GetMapping
    public ResponseEntity<SquadJiraDateFieldsService.Response> get(@PathVariable String squadId, HttpServletRequest request) {
        squadAccessService.requireSquadReadAccess(squadId, request);
        return ResponseEntity.ok(service.get(squadId, callerId(request)));
    }

    @PutMapping
    public ResponseEntity<SquadJiraDateFieldsService.Response> put(
            @PathVariable String squadId, @RequestBody UpdateRequest body, HttpServletRequest request) {
        if (!squadAccessService.canManageSquad(squadId, request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Só a liderança da squad (Agile Master, People Lead, Tech Lead, PO…) ou um administrador pode fazer isso.");
        }
        return ResponseEntity.ok(service.update(squadId, callerId(request), body.startFieldId(), body.endFieldId()));
    }

    private String callerId(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
    }
}
