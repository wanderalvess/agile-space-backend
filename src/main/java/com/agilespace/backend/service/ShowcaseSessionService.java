package com.agilespace.backend.service;

import com.agilespace.backend.domain.ShowcaseSession;
import com.agilespace.backend.repository.ShowcaseSessionRepository;
import com.agilespace.backend.websocket.ShowcaseWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class ShowcaseSessionService {

    @Autowired
    private ShowcaseSessionRepository repository;

    @Autowired
    private ShowcaseWebSocketHandler webSocketHandler;

    @Transactional(readOnly = true)
    public ShowcaseSession getSession(String id) {
        return repository.findById(id).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<ShowcaseSession> getLatestSessions(int limit) {
        int safeLimit = limit > 0 ? limit : 50;
        return repository.findLatestSessions(PageRequest.of(0, safeLimit));
    }

    @Transactional
    public ShowcaseSession saveSession(ShowcaseSession session, String callerId) {
        if (session.getId() != null && !session.getId().isEmpty()) {
            repository.findById(session.getId()).ifPresent(existing -> {
                session.setCreatedBy(existing.getCreatedBy() != null ? existing.getCreatedBy() : callerId);
                session.setCreatedAt(existing.getCreatedAt());
            });
        } else {
            session.setId(UUID.randomUUID().toString());
        }
        if (session.getCreatedBy() == null || session.getCreatedBy().isEmpty()) {
            session.setCreatedBy(callerId);
        }
        if (session.getCreatedAt() == null) {
            session.setCreatedAt(LocalDateTime.now());
        }

        ShowcaseSession saved = repository.save(session);

        // Dispara evento em tempo real para os clientes conectados com o payload da sessão
        webSocketHandler.broadcastEvent(saved.getId(), "SESSION_UPDATED", saved);

        return saved;
    }
}
