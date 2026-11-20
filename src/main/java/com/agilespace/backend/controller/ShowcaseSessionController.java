package com.agilespace.backend.controller;

import com.agilespace.backend.domain.ShowcaseSession;
import com.agilespace.backend.domain.ShowcaseTask;
import com.agilespace.backend.dto.ShowcasePresenterDto;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.ShowcaseSessionService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/showcase-sessions")
public class ShowcaseSessionController {

    @Autowired
    private ShowcaseSessionService service;

    @Autowired
    private com.agilespace.backend.service.SquadAccessService squadAccessService;

    /** Corpo de {@code PUT /{id}/presenter} (docs/ceremony-review-api.md). */
    public record PresenterRequest(String cardId, String step) {
    }

    @GetMapping
    public ResponseEntity<List<ShowcaseSession>> getSessions(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam("squadId") String squadId,
            HttpServletRequest request) {
        squadAccessService.requireSquadReadAccess(squadId, request);
        return ResponseEntity.ok(service.getLatestSessions(limit, squadId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ShowcaseSession> getSession(@PathVariable String id) {
        ShowcaseSession session = service.getSession(id);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(session);
    }

    @PostMapping
    public ResponseEntity<ShowcaseSession> saveSession(@RequestBody ShowcaseSession session, HttpServletRequest request) {
        String callerId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        ShowcaseSession saved = service.saveSession(session, callerId);
        return ResponseEntity.ok(saved);
    }

    /** Edição parcial de um card, com controle otimista por card ({@code version} no corpo). */
    @PatchMapping("/{id}/cards/{cardId}")
    public ResponseEntity<ShowcaseTask> patchCard(@PathVariable String id, @PathVariable String cardId,
                                                  @RequestBody JsonNode body, HttpServletRequest request) {
        String callerId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        return ResponseEntity.ok(service.patchCard(id, cardId, body, callerId));
    }

    @PutMapping("/{id}/presenter")
    public ResponseEntity<ShowcasePresenterDto> publishPresenter(@PathVariable String id,
                                                                 @RequestBody(required = false) PresenterRequest body,
                                                                 HttpServletRequest request) {
        String callerId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        PresenterRequest b = body != null ? body : new PresenterRequest(null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.publishPresenter(id, callerId, b.cardId(), b.step()));
    }

    @DeleteMapping("/{id}/presenter")
    public ResponseEntity<Void> clearPresenter(@PathVariable String id, HttpServletRequest request) {
        String callerId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        service.clearPresenter(id, callerId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/presenter")
    public ResponseEntity<ShowcasePresenterDto> getPresenter(@PathVariable String id) {
        return service.currentPresenter(id)
                .map(p -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(p))
                .orElseGet(() -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build());
    }

    @ExceptionHandler(ShowcaseSessionService.DecisionRestrictedException.class)
    public ResponseEntity<Map<String, Object>> onDecisionRestricted(ShowcaseSessionService.DecisionRestrictedException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", HttpStatus.FORBIDDEN.toString());
        body.put("message", ex.getReason());
        body.put("code", "DECISION_RESTRICTED");
        body.put("cardIds", ex.getCardIds());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(ShowcaseSessionService.CardVersionConflictException.class)
    public ResponseEntity<Map<String, Object>> onCardConflict(ShowcaseSessionService.CardVersionConflictException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", HttpStatus.CONFLICT.toString());
        body.put("message", ex.getReason());
        body.put("code", "CARD_VERSION_CONFLICT");
        body.put("card", ex.getCurrentCard());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
