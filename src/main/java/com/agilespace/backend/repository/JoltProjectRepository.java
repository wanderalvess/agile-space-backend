package com.agilespace.backend.repository;

import com.agilespace.backend.domain.JoltProject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface JoltProjectRepository extends JpaRepository<JoltProject, UUID> {

    @Query("SELECT p FROM JoltProject p WHERE p.authorId = :userId OR p.isPublic = true OR (p.squadId IS NOT NULL AND p.squadId = :squadId) ORDER BY p.updatedAt DESC")
    List<JoltProject> findAccessibleProjects(@Param("userId") String userId, @Param("squadId") String squadId);

    List<JoltProject> findByAuthorIdOrderByUpdatedAtDesc(String authorId);

    List<JoltProject> findByIsPublicTrueOrderByUpdatedAtDesc();

    @Query("SELECT p FROM JoltProject p WHERE LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(p.description) LIKE LOWER(CONCAT('%', :search, '%')) ORDER BY p.updatedAt DESC")
    List<JoltProject> searchProjects(@Param("search") String search);
}
