package com.agilespace.backend.repository;

import com.agilespace.backend.domain.HealthCheckVote;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HealthCheckVoteRepository extends JpaRepository<HealthCheckVote, String> {
    List<HealthCheckVote> findByBoardId(String boardId);
    List<HealthCheckVote> findByBoardIdAndParticipantId(String boardId, String participantId);
    void deleteByBoardId(String boardId);

    /** [boardId, pessoas que responderam]. */
    @Query("SELECT v.boardId, COUNT(DISTINCT v.participantId) FROM HealthCheckVote v WHERE v.boardId IN :boardIds GROUP BY v.boardId")
    List<Object[]> countRespondentsByBoard(@Param("boardIds") Collection<String> boardIds);

    /** [boardId, participantId, quantas dimensões distintas a pessoa votou]. Só contagem: nunca o valor nem o comentário. */
    @Query("SELECT v.boardId, v.participantId, COUNT(DISTINCT v.dimensionKey) FROM HealthCheckVote v "
            + "WHERE v.boardId IN :boardIds GROUP BY v.boardId, v.participantId")
    List<Object[]> countDimensionsByParticipant(@Param("boardIds") Collection<String> boardIds);
}
