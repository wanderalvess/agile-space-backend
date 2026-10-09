package com.agilespace.backend.repository;

import com.agilespace.backend.domain.ShowcaseSession;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

@Repository
public interface ShowcaseSessionRepository extends JpaRepository<ShowcaseSession, String> {

    /** Trava a linha até o fim da transação: saves da mesma Review passam um de cada vez. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ShowcaseSession s WHERE s.id = :id")
    Optional<ShowcaseSession> findByIdForUpdate(@Param("id") String id);

    @Query("SELECT s FROM ShowcaseSession s ORDER BY s.createdAt DESC")
    List<ShowcaseSession> findLatestSessions(Pageable pageable);

    @Query("SELECT s FROM ShowcaseSession s WHERE LOWER(s.squadName) = LOWER(:squadId) ORDER BY s.createdAt DESC")
    List<ShowcaseSession> findLatestSessionsBySquad(@Param("squadId") String squadId, Pageable pageable);
}
