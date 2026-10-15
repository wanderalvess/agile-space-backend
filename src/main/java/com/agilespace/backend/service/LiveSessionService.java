package com.agilespace.backend.service;

import com.agilespace.backend.dto.LiveSessionDto;
import com.agilespace.backend.dto.RitualSessionDto;
import com.agilespace.backend.service.SessionStatusResolver.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/** Porta de entrada de /api/live-sessions e /api/rituals/{tool}/history: resolve as squads e confere o acesso. */
@Service
@RequiredArgsConstructor
public class LiveSessionService {

    private final SquadAudienceService audienceService;
    private final SquadAccessService squadAccessService;
    private final RitualSessionService ritualSessionService;

    /** Sem {@code squadId}: todas as squads acessíveis. Com: exige acesso a ela (403). */
    @Transactional(readOnly = true)
    public List<LiveSessionDto> live(String userId, String role, String squadId) {
        SquadAudienceService.UserContext ctx = context(userId, role);
        List<String> scope;
        if (squadId != null && !squadId.isBlank()) {
            requireAccess(ctx, squadId, userId, role);
            scope = List.of(squadId.trim());
        } else {
            scope = ctx.squadIds();
        }
        return ritualSessionService.live(scope, Instant.now());
    }

    /** {@code squadId} omitido = squad da sessão; 400 se o usuário não tem nenhuma. */
    @Transactional(readOnly = true)
    public List<RitualSessionDto> history(String userId, String role, String toolPath, String squadId, Integer limit) {
        Tool tool = Tool.fromPath(toolPath)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ritual desconhecido."));
        SquadAudienceService.UserContext ctx = context(userId, role);
        String squad = squadId != null && !squadId.isBlank() ? squadId.trim() : ctx.currentSquadId();
        if (squad == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe a squad.");
        }
        requireAccess(ctx, squad, userId, role);
        return ritualSessionService.history(tool, squad, limit, Instant.now());
    }

    private SquadAudienceService.UserContext context(String userId, String role) {
        return audienceService.contextFor(userId, role)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida."));
    }

    private void requireAccess(SquadAudienceService.UserContext ctx, String squadId, String userId, String role) {
        if (!ctx.hasSquad(squadId) && !squadAccessService.matchesSquad(squadId, userId, role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a membros desta squad ou administradores.");
        }
    }
}
