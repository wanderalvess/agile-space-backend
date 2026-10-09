package com.agilespace.backend.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.function.Predicate;

/**
 * Roda depois do {@link JwtHandshakeInterceptor} (que já autenticou e preencheu userId): a sala precisa existir.
 * O link da sala dá acesso a qualquer autenticado, então não há checagem de squad aqui (decisão de produto).
 */
public class BoardExistsHandshakeInterceptor implements HandshakeInterceptor {

    private final Predicate<String> boardExists;

    public BoardExistsHandshakeInterceptor(Predicate<String> boardExists) {
        this.boardExists = boardExists;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String path = request.getURI().getPath();
        String boardId = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
        if (boardId.isBlank() || attributes.get("userId") == null || !boardExists.test(boardId)) {
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
