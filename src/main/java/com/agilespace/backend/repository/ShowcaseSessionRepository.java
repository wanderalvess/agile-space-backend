package com.agilespace.backend.repository;

import com.agilespace.backend.domain.ShowcaseSession;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShowcaseSessionRepository extends JpaRepository<ShowcaseSession, String> {

    @Query("SELECT s FROM ShowcaseSession s ORDER BY s.createdAt DESC")
    List<ShowcaseSession> findLatestSessions(Pageable pageable);
}
