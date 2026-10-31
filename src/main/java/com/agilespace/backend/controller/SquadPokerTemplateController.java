package com.agilespace.backend.controller;

import com.agilespace.backend.domain.SquadPokerTemplate;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.SquadAccessService;
import com.agilespace.backend.service.SquadPokerTemplateService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Templates de sessão do Scrum Poker da squad. Listar e salvar: qualquer membro. Renomear/atualizar/excluir:
 * quem salvou ou a liderança da squad (a regra fica no serviço).
 */
@RestController
@RequestMapping("/api/squads/{squadId}/poker-templates")
@RequiredArgsConstructor
public class SquadPokerTemplateController {

    private final SquadPokerTemplateService service;
    private final SquadAccessService squadAccessService;

    @GetMapping
    public ResponseEntity<List<SquadPokerTemplate>> list(@PathVariable String squadId, HttpServletRequest request) {
        squadAccessService.requireSquadReadAccess(squadId, request);
        return ResponseEntity.ok(service.list(squadId));
    }

    @PostMapping
    public ResponseEntity<SquadPokerTemplate> create(@PathVariable String squadId,
                                                     @RequestBody SquadPokerTemplate body,
                                                     HttpServletRequest request) {
        squadAccessService.requireSquadReadAccess(squadId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(squadId, body, callerId(request)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<SquadPokerTemplate> update(@PathVariable String squadId, @PathVariable String id,
                                                     @RequestBody SquadPokerTemplate body,
                                                     HttpServletRequest request) {
        squadAccessService.requireSquadReadAccess(squadId, request);
        return ResponseEntity.ok(service.update(squadId, id, body, callerId(request), squadAccessService.canManageSquad(squadId, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String squadId, @PathVariable String id, HttpServletRequest request) {
        squadAccessService.requireSquadReadAccess(squadId, request);
        service.delete(squadId, id, callerId(request), squadAccessService.canManageSquad(squadId, request));
        return ResponseEntity.noContent().build();
    }

    private static String callerId(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
    }

    // Em produção o Spring oculta a razão das ResponseStatusException (include-message: never); aqui ela orienta
    // quem usa a tela (nome vazio, limite da squad, só quem salvou altera), então vai explícita no corpo.
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "error", String.valueOf(ex.getStatusCode().value()),
                "message", ex.getReason() == null ? "" : ex.getReason()
        ));
    }
}
