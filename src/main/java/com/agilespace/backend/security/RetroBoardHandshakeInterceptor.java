package com.agilespace.backend.security;

import com.agilespace.backend.service.RetroCaller;
import com.agilespace.backend.service.RetroService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Autoriza o handshake de /ws/retro/{boardId} por board (roda depois do {@link JwtHandshakeInterceptor},
 * que já autenticou e preencheu userId/role): o board precisa existir e o usuário poder lê-lo.
 */
@Component
@RequiredArgsConstructor
public class RetroBoardHandshakeInterceptor implements HandshakeInterceptor {

    private final RetroService retroService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String path = request.getURI().getPath();
        String boardId = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
        Object userId = attributes.get("userId");
        Object role = attributes.get("role");
        if (boardId.isBlank() || userId == null
                || !retroService.canAccessBoard(boardId, new RetroCaller(userId.toString(), role == null ? null : role.toString()))) {
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
