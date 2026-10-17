package com.agilespace.backend.controller;

import com.agilespace.backend.dto.ShowcaseJqlQueryDto;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.ShowcaseJqlService;
import com.agilespace.backend.service.SquadAccessService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Consultas JQL da Review compartilhadas com a squad (docs/ceremony-review-api.md, seção 6). Só guarda o texto: o servidor nunca
 * executa JQL. O caminho literal {@code /jql} vence o {@code /{id}} do ShowcaseSessionController.
 */
@RestController
@RequestMapping("/api/showcase-sessions/jql")
@RequiredArgsConstructor
public class ShowcaseJqlController {

    private final ShowcaseJqlService service;
    private final SquadAccessService squadAccessService;

    public record CreateRequest(String squadId, String name, String jql, String visibility) {
    }

    @GetMapping
    public ResponseEntity<List<ShowcaseJqlQueryDto>> list(@RequestParam("squadId") String squadId, HttpServletRequest request) {
        squadAccessService.requireSquadReadAccess(squadId, request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(squadId, caller(request)));
    }

    @PostMapping
    public ResponseEntity<ShowcaseJqlQueryDto> create(@RequestBody CreateRequest body, HttpServletRequest request) {
        squadAccessService.requireSquadReadAccess(body.squadId() == null ? "" : body.squadId(), request);
        return ResponseEntity.ok(service.create(body.squadId(), body.name(), body.jql(), body.visibility(), caller(request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, HttpServletRequest request) {
        service.delete(id, caller(request));
        return ResponseEntity.noContent().build();
    }

    private static String caller(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
    }
}
