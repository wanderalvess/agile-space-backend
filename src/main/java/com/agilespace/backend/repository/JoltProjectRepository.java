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

    /** Busca restrita ao que o usuário pode ler (antes varria todos os projetos, inclusive privados de outros). */
    @Query("SELECT p FROM JoltProject p WHERE (p.authorId = :userId OR p.isPublic = true OR (p.squadId IS NOT NULL AND p.squadId = :squadId)) "
            + "AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(p.description) LIKE LOWER(CONCAT('%', :search, '%'))) "
            + "ORDER BY p.updatedAt DESC")
    List<JoltProject> searchAccessibleProjects(@Param("userId") String userId, @Param("squadId") String squadId, @Param("search") String search);

    /**
     * Variante para a autorização por vínculos (app.membership.authz=new): o projeto é compartilhado com QUALQUER uma das
     * squads da pessoa. {@code upperSquadIds} em MAIÚSCULAS e nunca vazio (o chamador passa um sentinela).
     */
    @Query("SELECT p FROM JoltProject p WHERE p.authorId = :userId OR p.isPublic = true OR (p.squadId IS NOT NULL AND UPPER(p.squadId) IN :squadIds) ORDER BY p.updatedAt DESC")
    List<JoltProject> findAccessibleProjectsInSquads(@Param("userId") String userId, @Param("squadIds") java.util.Collection<String> upperSquadIds);

    @Query("SELECT p FROM JoltProject p WHERE (p.authorId = :userId OR p.isPublic = true OR (p.squadId IS NOT NULL AND UPPER(p.squadId) IN :squadIds)) "
            + "AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(p.description) LIKE LOWER(CONCAT('%', :search, '%'))) "
            + "ORDER BY p.updatedAt DESC")
    List<JoltProject> searchAccessibleProjectsInSquads(@Param("userId") String userId, @Param("squadIds") java.util.Collection<String> upperSquadIds,
                                                       @Param("search") String search);

    List<JoltProject> findByAuthorIdOrderByUpdatedAtDesc(String authorId);

    List<JoltProject> findByIsPublicTrueOrderByUpdatedAtDesc();
}
