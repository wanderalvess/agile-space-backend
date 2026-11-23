package com.agilespace.backend.controller;

import com.agilespace.backend.domain.SquadBoardConfig;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.SquadAccessService;
import com.agilespace.backend.service.SquadBoardConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Personalização do quadro Scrum da squad (filtros rápidos, raias, limites de WIP). Ler: qualquer membro da squad.
 * Gravar: só a liderança, porque a mudança vale para todos que abrem o quadro.
 */
@RestController
@RequestMapping("/api/squads/{squadId}/board-config")
@RequiredArgsConstructor
public class SquadBoardConfigController {

    private final SquadBoardConfigService service;
    private final SquadAccessService squadAccessService;

    @GetMapping
    public ResponseEntity<SquadBoardConfig> get(@PathVariable String squadId, HttpServletRequest request) {
        squadAccessService.requireSquadReadAccess(squadId, request);
        return ResponseEntity.ok(service.get(squadId));
    }

    @PatchMapping
    public ResponseEntity<SquadBoardConfig> patch(@PathVariable String squadId, @RequestBody JsonNode body, HttpServletRequest request) {
        squadAccessService.requireSquadReadAccess(squadId, request);
        if (!squadAccessService.canManageSquad(squadId, request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Só a liderança da squad altera o quadro para todos. Peça ao Agile Master ou ao Product Leader.");
        }
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        return ResponseEntity.ok(service.patch(squadId, body, userId));
    }

    // Em produção o Spring oculta a razão das ResponseStatusException (include-message: never); aqui ela orienta
    // quem usa a tela, então vai explícita no corpo.
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "error", String.valueOf(ex.getStatusCode().value()),
                "message", ex.getReason() == null ? "" : ex.getReason()
        ));
    }
}
