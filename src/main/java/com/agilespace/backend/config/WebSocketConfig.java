package com.agilespace.backend.config;

import com.agilespace.backend.security.JwtHandshakeInterceptor;
import com.agilespace.backend.websocket.BrainstormingWebSocketHandler;
import com.agilespace.backend.websocket.HealthCheckWebSocketHandler;
import com.agilespace.backend.websocket.PokerWebSocketHandler;
import com.agilespace.backend.websocket.RetroWebSocketHandler;
import com.agilespace.backend.websocket.ShowcaseWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

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

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Registra o handler na rota /ws/retro/{boardId} permitindo conexões de qualquer origem (CORS)
        registry.addHandler(retroWebSocketHandler, "/ws/retro/*")
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOrigins("*");
        // Registra o handler na rota /ws/poker/{boardId} permitindo conexões de qualquer origem (CORS)
        registry.addHandler(pokerWebSocketHandler, "/ws/poker/*")
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOrigins("*");
        // Registra o handler na rota /ws/health-check/{boardId} permitindo conexões de qualquer origem (CORS)
        registry.addHandler(healthCheckWebSocketHandler, "/ws/health-check/*")
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOrigins("*");
        // Registra o handler na rota /ws/brainstorming/{boardId} permitindo conexões de qualquer origem (CORS)
        registry.addHandler(brainstormingWebSocketHandler, "/ws/brainstorming/*")
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOrigins("*");
        // Registra o handler na rota /ws/showcase/{sessionId} permitindo conexões de qualquer origem (CORS)
        registry.addHandler(showcaseWebSocketHandler, "/ws/showcase/*")
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
