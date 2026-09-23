package com.agilespace.backend.repository;

import com.agilespace.backend.domain.ShowcaseTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShowcaseTaskRepository extends JpaRepository<ShowcaseTask, String> {

    List<ShowcaseTask> findBySessionIdOrderByOrderAsc(String sessionId);

    void deleteBySessionId(String sessionId);
}
