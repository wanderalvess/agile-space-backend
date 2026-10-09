package com.agilespace.backend.repository;

import com.agilespace.backend.domain.JoltProjectVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface JoltProjectVersionRepository extends JpaRepository<JoltProjectVersion, UUID> {
    List<JoltProjectVersion> findByProjectIdOrderByVersionNumberDesc(UUID projectId);

    int countByProjectId(UUID projectId);

    /** Linhas [projectId, quantidade]. */
    @Query("SELECT v.project.id, COUNT(v) FROM JoltProjectVersion v WHERE v.project.id IN :ids GROUP BY v.project.id")
    List<Object[]> countByProjectIds(@Param("ids") Collection<UUID> ids);
}
