package com.agilespace.backend.repository;

import com.agilespace.backend.domain.HealthCheckBoard;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HealthCheckBoardRepository extends JpaRepository<HealthCheckBoard, String> {
    List<HealthCheckBoard> findAllByOrderByCreatedAtDesc();
    List<HealthCheckBoard> findByTeamIgnoreCaseOrderByCreatedAtDesc(String team);

    /** Lock pessimista no board: um voto não entra depois que o encerramento já calculou o resumo. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from HealthCheckBoard b where b.id = :id")
    Optional<HealthCheckBoard> findByIdForUpdate(@Param("id") String id);
}
