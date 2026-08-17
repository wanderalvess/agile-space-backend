package com.agilespace.backend.repository;

import com.agilespace.backend.domain.UserFocusSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface UserFocusSessionRepository extends JpaRepository<UserFocusSession, String> {
    List<UserFocusSession> findByUserIdOrderByCreatedAtDesc(String userId);
}
