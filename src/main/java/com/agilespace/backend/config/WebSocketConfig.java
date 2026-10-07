package com.agilespace.backend.config;

import com.agilespace.backend.security.JwtHandshakeInterceptor;
import com.agilespace.backend.websocket.BrainstormingWebSocketHandler;
import com.agilespace.backend.websocket.HealthCheckWebSocketHandler;
import com.agilespace.backend.websocket.PokerWebSocketHandler;
import com.agilespace.backend.websocket.RetroWebSocketHandler;
import com.agilespace.backend.websocket.ShowcaseWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.List;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final RetroWebSocketHandler retroWebSocketHandler;
    private final PokerWebSocketHandler pokerWebSocketHandler;
    private final HealthCheckWebSocketHandler healthCheckWebSocketHandler;
    private final BrainstormingWebSocketHandler brainstormingWebSocketHandler;
    private final ShowcaseWebSocketHandler showcaseWebSocketHandler;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;

    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins = allowedOrigins.toArray(new String[0]);
        // Mesma lista de origens do WebCorsConfig (ALLOWED_ORIGINS) — o handshake já é
        // autenticado por token (JwtHandshakeInterceptor), isso só evita que qualquer site
        // abra uma conexão de WebSocket contra a API a partir do browser de um usuário logado.
        registry.addHandler(retroWebSocketHandler, "/ws/retro/*")
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOrigins(origins);
        registry.addHandler(pokerWebSocketHandler, "/ws/poker/*")
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOrigins(origins);
        registry.addHandler(healthCheckWebSocketHandler, "/ws/health-check/*")
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOrigins(origins);
        registry.addHandler(brainstormingWebSocketHandler, "/ws/brainstorming/*")
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOrigins(origins);
        registry.addHandler(showcaseWebSocketHandler, "/ws/showcase/*")
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOrigins(origins);
    }
}
