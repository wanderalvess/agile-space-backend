package com.agilespace.backend.repository;

import com.agilespace.backend.domain.ShowcaseTask;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShowcaseTaskRepository extends JpaRepository<ShowcaseTask, String> {

    List<ShowcaseTask> findBySessionIdOrderByOrderAsc(String sessionId);

    void deleteBySessionId(String sessionId);

    /** [sessionId, decision, quantidade]. */
    @Query("SELECT t.sessionId, t.decision, COUNT(t) FROM ShowcaseTask t WHERE t.sessionId IN :sessionIds GROUP BY t.sessionId, t.decision")
    List<Object[]> countDecisionsBySession(@Param("sessionIds") Collection<String> sessionIds);
}
