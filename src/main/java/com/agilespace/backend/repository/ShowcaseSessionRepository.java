package com.agilespace.backend.repository;

import com.agilespace.backend.domain.ShowcaseSession;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.Collection;
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

    /** Home: Reviews não encerradas das squads (squadName guarda o id da squad, em MAIÚSCULAS aqui) desde {@code since}. */
    @Query("SELECT s FROM ShowcaseSession s WHERE UPPER(s.squadName) IN :squadIds AND LOWER(s.status) <> 'finished' "
            + "AND s.createdAt >= :since ORDER BY s.createdAt DESC")
    List<ShowcaseSession> findOpenBySquads(@Param("squadIds") Collection<String> upperSquadIds, @Param("since") LocalDateTime since, Pageable pageable);
}
