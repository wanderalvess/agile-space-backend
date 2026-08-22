package com.agilespace.backend.repository;

import com.agilespace.backend.domain.UserSnippet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface UserSnippetRepository extends JpaRepository<UserSnippet, String> {
    List<UserSnippet> findByUserIdOrderByCreatedAtDesc(String userId);
}
