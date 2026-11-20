package com.agilespace.backend.repository;

import com.agilespace.backend.domain.UserQuickLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface UserQuickLinkRepository extends JpaRepository<UserQuickLink, String> {
    long countByUserId(String userId);
    List<UserQuickLink> findByUserIdOrderByCreatedAtDesc(String userId);
}
