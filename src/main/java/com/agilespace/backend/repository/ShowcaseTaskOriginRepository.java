package com.agilespace.backend.repository;

import com.agilespace.backend.domain.ShowcaseTaskOrigin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShowcaseTaskOriginRepository extends JpaRepository<ShowcaseTaskOrigin, String> {

    List<ShowcaseTaskOrigin> findBySessionId(String sessionId);

    List<ShowcaseTaskOrigin> findBySessionIdAndTaskId(String sessionId, String taskId);
}
