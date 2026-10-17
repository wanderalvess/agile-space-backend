package com.agilespace.backend.websocket;

import java.util.Optional;
import java.util.Set;

/**
 * Decide quem pode receber o "escrevendo…" (TYPING) de uma pessoa na Retro, com a mesma regra de acesso do quadro e do
 * chat. Fica numa interface para o WebSocket não depender do serviço (o serviço já depende do WebSocket).
 */
public interface RetroTypingGate {

    /**
     * @param audience  userIds que podem receber o evento; {@code null} = a sala inteira
     * @param anonymous true quando o remetente NÃO pode ser identificado (card antes da revelação dos autores)
     */
    record Route(Set<String> audience, boolean anonymous) {
    }

    /** Vazio = descartar o evento (remetente sem acesso ao quadro/canal, quadro inexistente). */
    Optional<Route> route(String boardId, String userId, String role, String scope, String channelId);
}
