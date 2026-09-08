package com.agilespace.backend.repository;

import com.agilespace.backend.domain.JoltProjectVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface JoltProjectVersionRepository extends JpaRepository<JoltProjectVersion, UUID> {
    List<JoltProjectVersion> findByProjectIdOrderByVersionNumberDesc(UUID projectId);
}
