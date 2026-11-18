package com.agilespace.backend.repository;

import com.agilespace.backend.domain.PokerRoom;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Collection;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PokerRoomRepository extends JpaRepository<PokerRoom, String> {
    Page<PokerRoom> findByTeamIgnoreCaseOrderByCreatedAtDesc(String team, Pageable pageable);

    // Lock de escrita: várias pessoas editam as notas do refinamento ao mesmo tempo e cada
    // edição é um read-modify-write no JSON issues_queue; sem lock uma sobrescreveria a outra.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PokerRoom r where r.id = :id")
    Optional<PokerRoom> findByIdForUpdate(@Param("id") String id);

    /** Salas síncronas iniciadas e ainda não encerradas, das mais antigas para as mais novas (candidatas ao encerramento na virada do dia). */
    @Query("SELECT r FROM PokerRoom r WHERE r.sessionStartedAt IS NOT NULL AND r.sessionStartedAt <> '' "
            + "AND (r.sessionEndedAt IS NULL OR r.sessionEndedAt = '') AND (r.mode IS NULL OR LOWER(r.mode) <> 'async') "
            + "ORDER BY r.sessionStartedAt ASC")
    List<PokerRoom> findStartedAndOpen(Pageable pageable);

    /** Home: salas não encerradas das squads (ids em MAIÚSCULAS) criadas desde {@code since} (ISO). */
    @Query("SELECT r FROM PokerRoom r WHERE UPPER(r.team) IN :teams AND (r.sessionEndedAt IS NULL OR r.sessionEndedAt = '') "
            + "AND (r.createdAt >= :since OR r.sessionStartedAt >= :since) ORDER BY r.createdAt DESC")
    List<PokerRoom> findOpenByTeams(@Param("teams") Collection<String> upperTeams, @Param("since") String sinceIso, Pageable pageable);
}
