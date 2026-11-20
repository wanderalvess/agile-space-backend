package com.agilespace.backend.repository;

import com.agilespace.backend.domain.JiraPublishStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JiraPublishStepRepository extends JpaRepository<JiraPublishStep, String> {
    List<JiraPublishStep> findByJobIdOrderByPositionAsc(String jobId);
    List<JiraPublishStep> findByJobIdInOrderByJobIdAscPositionAsc(List<String> jobIds);
}
