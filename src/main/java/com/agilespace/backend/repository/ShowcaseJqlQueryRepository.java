package com.agilespace.backend.repository;

import com.agilespace.backend.domain.ShowcaseJqlQuery;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShowcaseJqlQueryRepository extends JpaRepository<ShowcaseJqlQuery, String> {

    /** As da squad que a pessoa pode ver: SQUAD de todos mais as PRIVATE dela. */
    @Query("SELECT q FROM ShowcaseJqlQuery q WHERE q.squadId = :squadId AND (q.visibility = 'SQUAD' OR q.createdBy = :userId) "
            + "ORDER BY q.createdAt DESC")
    List<ShowcaseJqlQuery> findVisible(@Param("squadId") String squadId, @Param("userId") String userId, Pageable pageable);

    boolean existsBySquadIdAndCreatedByAndNameIgnoreCase(String squadId, String createdBy, String name);

    long countBySquadIdAndCreatedBy(String squadId, String createdBy);
}
