package com.agilespace.backend.security;

import com.agilespace.backend.domain.SprintPlan;
import com.agilespace.backend.repository.SprintPlanRepository;
import com.agilespace.backend.service.SquadAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Autoriza /ws/sprint-plan/{planId} (depois do {@link JwtHandshakeInterceptor}): o plano precisa existir e a pessoa ter
 * acesso de leitura à squad dele (membro, liderança ou ADMIN), a mesma regra do GET da Distribuição.
 */
@Component
@RequiredArgsConstructor
public class SprintPlanHandshakeInterceptor implements HandshakeInterceptor {

    private final SprintPlanRepository planRepository;
    private final SquadAccessService squadAccessService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String path = request.getURI().getPath();
        String planId = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
        Object userId = attributes.get("userId");
        Object role = attributes.get("role");
        boolean ok = !planId.isBlank() && userId != null
                && planRepository.findById(planId)
                .map(SprintPlan::getSquadId)
                .filter(squadId -> squadAccessService.matchesSquad(squadId, userId.toString(), role == null ? null : role.toString()))
                .isPresent();
        if (!ok) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
