package com.agilespace.backend.repository;

import com.agilespace.backend.domain.JiraPublishEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JiraPublishEventRepository extends JpaRepository<JiraPublishEvent, String> {
    /** Mais antigo primeiro; o serviço limita o tamanho pelo Pageable. */
    List<JiraPublishEvent> findByJobIdOrderByCreatedAtAsc(String jobId, Pageable pageable);
}
