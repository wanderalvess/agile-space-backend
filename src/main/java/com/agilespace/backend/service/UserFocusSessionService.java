package com.agilespace.backend.service;

import com.agilespace.backend.domain.UserFocusSession;
import com.agilespace.backend.repository.UserFocusSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserFocusSessionService {

    private final UserFocusSessionRepository repository;

    @Transactional(readOnly = true)
    public List<UserFocusSession> getSessions(String userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public UserFocusSession saveSession(String userId, UserFocusSession session) {
        session.setUserId(userId);
        if (session.getId() == null || session.getId().isEmpty()) {
            session.setId(UUID.randomUUID().toString());
        }
        session.setCreatedAt(LocalDateTime.now());
        return repository.save(session);
    }
}
