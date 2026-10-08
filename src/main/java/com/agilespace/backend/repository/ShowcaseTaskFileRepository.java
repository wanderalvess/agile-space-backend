package com.agilespace.backend.repository;

import com.agilespace.backend.domain.ShowcaseTaskFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShowcaseTaskFileRepository extends JpaRepository<ShowcaseTaskFile, String> {

    List<ShowcaseTaskFile> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    long countBySessionIdAndTaskId(String sessionId, String taskId);
}
