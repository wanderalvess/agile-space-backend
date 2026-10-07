package com.agilespace.backend.repository;

import com.agilespace.backend.domain.UserRoleHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface UserRoleHistoryRepository extends JpaRepository<UserRoleHistory, String> {
    List<UserRoleHistory> findByUserIdOrderByChangedAtDesc(String userId);
}
