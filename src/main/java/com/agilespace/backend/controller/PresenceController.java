package com.agilespace.backend.controller;

import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.BrainstormingService;
import com.agilespace.backend.service.HealthCheckService;
import com.agilespace.backend.service.PokerService;
import com.agilespace.backend.service.RetroCaller;
import com.agilespace.backend.service.RetroService;
import com.agilespace.backend.service.ShowcaseSessionService;
import com.agilespace.backend.websocket.PresenceModule;
import com.agilespace.backend.websocket.PresenceRegistry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Presença nas salas em tempo real (docs/presence-api.md). Fallback REST do evento {@code PRESENCE_UPDATED}: quem está com
 * conexão aberta na sala agora. Cada módulo usa a MESMA regra de acesso que já vale para abrir a sala, para a presença não
 * revelar nada que a sala não revelaria. Só userIds, como o cliente já os conhece pelos participantes.
 */
@RestController
@RequestMapping("/api/presence")
@RequiredArgsConstructor
public class PresenceController {

    /** Ids de sala reais são curtos (UUID/slug); o limite só evita consultas com lixo. */
    private static final int MAX_ROOM_ID_LENGTH = 200;

    private final PresenceRegistry registry;
    private final PokerService pokerService;
    private final RetroService retroService;
    private final BrainstormingService brainstormingService;
    private final HealthCheckService healthCheckService;
    private final ShowcaseSessionService showcaseSessionService;

    public record PresenceResponse(String module, String roomId, List<String> online, int total) {
    }

    @GetMapping("/{module}/{roomId}")
    public ResponseEntity<PresenceResponse> getPresence(
            @PathVariable("module") String modulePath,
            @PathVariable("roomId") String roomId,
            HttpServletRequest request) {
        String callerId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        if (callerId == null || callerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Faça login para ver a presença.");
        }
        PresenceModule module = PresenceModule.fromPath(modulePath)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Módulo desconhecido."));
        if (roomId == null || roomId.isBlank() || roomId.length() > MAX_ROOM_ID_LENGTH) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sala não encontrada.");
        }
        requireAccess(module, roomId, callerId, (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE));

        PresenceRegistry.Snapshot snapshot = registry.snapshot(module, roomId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new PresenceResponse(module.path(), roomId, snapshot.online(), snapshot.total()));
    }

    private void requireAccess(PresenceModule module, String roomId, String callerId, String callerRole) {
        switch (module) {
            // Poker: o link da sala dá acesso a qualquer autenticado (igual a GET /api/poker/{id} e ao handshake).
            case POKER -> requireExists(pokerService.getRoom(roomId).isPresent());
            case BRAINSTORMING -> requireExists(brainstormingService.boardExists(roomId));
            case HEALTH_CHECK -> requireExists(healthCheckService.boardExists(roomId));
            case SHOWCASE -> requireExists(showcaseSessionService.sessionExists(roomId));
            // Retro: o canal em tempo real só abre para criador, participante ou ADMIN (mesma checagem do handshake).
            case RETRO -> {
                requireExists(retroService.boardExists(roomId));
                if (!retroService.canAccessBoard(roomId, new RetroCaller(callerId, callerRole))) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a participantes deste board.");
                }
            }
        }
    }

    private static void requireExists(boolean exists) {
        if (!exists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sala não encontrada.");
        }
    }
}
