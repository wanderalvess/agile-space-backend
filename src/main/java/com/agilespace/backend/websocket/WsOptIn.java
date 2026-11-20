package com.agilespace.backend.websocket;

import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Opt-in por parâmetro da URL do WebSocket (mesmo padrão do {@code ?presence=1}, docs/presence-api.md): eventos novos só
 * vão a quem pediu, porque o frontend antigo trata qualquer tipo desconhecido como "recarrega a sala inteira".
 * Aceita {@code 1} ou {@code true} (sem diferenciar caixa); qualquer outra coisa, ou ausência, = não recebe.
 */
final class WsOptIn {

    static final String TYPING = "typing";
    static final String PRESENTATION = "presentation";

    private WsOptIn() {
    }

    static boolean wants(WebSocketSession session, String param) {
        try {
            if (session == null || session.getUri() == null) return false;
            String v = UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams().getFirst(param);
            return "1".equals(v) || "true".equalsIgnoreCase(v);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
