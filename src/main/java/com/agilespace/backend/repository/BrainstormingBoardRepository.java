package com.agilespace.backend.repository;

import com.agilespace.backend.domain.BrainstormingBoard;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BrainstormingBoardRepository extends JpaRepository<BrainstormingBoard, String> {
    List<BrainstormingBoard> findAllByOrderByCreatedAtDesc();
    List<BrainstormingBoard> findByTeamIgnoreCaseOrderByCreatedAtDesc(String team);

    /** Lock pessimista na linha do board: serializa mudanças de fase/timer/configurações. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BrainstormingBoard b where b.id = :id")
    Optional<BrainstormingBoard> findByIdForUpdate(@Param("id") String id);
}
