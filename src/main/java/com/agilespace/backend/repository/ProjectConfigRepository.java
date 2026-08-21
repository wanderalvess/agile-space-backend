package com.agilespace.backend.repository;

import com.agilespace.backend.domain.ProjectConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectConfigRepository extends JpaRepository<ProjectConfig, String> {

    List<ProjectConfig> findBySegmentNameIgnoreCase(String segmentName);

    List<ProjectConfig> findByTribeNameIgnoreCase(String tribeName);

    @Query("SELECT DISTINCT p.segmentName FROM ProjectConfig p WHERE p.segmentName IS NOT NULL ORDER BY p.segmentName")
    List<String> findDistinctSegmentNames();

    @Query("SELECT DISTINCT p.tribeName FROM ProjectConfig p WHERE p.tribeName IS NOT NULL ORDER BY p.tribeName")
    List<String> findDistinctTribeNames();

    List<ProjectConfig> findAllByOrderBySegmentNameAscNameAsc();
}
