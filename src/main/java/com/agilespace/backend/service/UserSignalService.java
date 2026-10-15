package com.agilespace.backend.service;

import com.agilespace.backend.websocket.UserWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * Sinais do canal do usuário (/ws/user). Sempre depois do commit: o cliente reage relendo pela API e não pode
 * ver o estado antigo. Falha de envio nunca derruba a operação que o gerou.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserSignalService {

    public static final String NOTIFICATION = "NOTIFICATION";
    public static final String MESSAGE = "MESSAGE";
    public static final String ANNOUNCEMENT = "ANNOUNCEMENT";
    public static final String UNREAD = "UNREAD";

    private final UserWebSocketHandler handler;

    public void signal(Collection<String> userIds, String type) {
        signal(userIds, type, null);
    }

    public void signal(String userId, String type) {
        signal(userId == null ? List.of() : List.of(userId), type, null);
    }

    public void signal(Collection<String> userIds, String type, String conversationId) {
        if (userIds == null || userIds.isEmpty()) return;
        List<String> targets = List.copyOf(userIds);
        AfterCommit.run(() -> {
            try {
                handler.sendSignal(targets, type, conversationId);
            } catch (RuntimeException e) {
                log.warn("Falha ao enviar sinal {} no canal do usuário", type, e);
            }
        });
    }

    /** Para todos os conectados (aviso com público ALL). */
    public void signalAllConnected(String type) {
        AfterCommit.run(() -> {
            try {
                handler.sendSignal(List.copyOf(handler.connectedUserIds()), type, null);
            } catch (RuntimeException e) {
                log.warn("Falha ao enviar sinal {} no canal do usuário", type, e);
            }
        });
    }
}
