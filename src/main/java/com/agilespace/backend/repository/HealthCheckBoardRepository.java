package com.agilespace.backend.repository;

import com.agilespace.backend.domain.HealthCheckBoard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HealthCheckBoardRepository extends JpaRepository<HealthCheckBoard, String> {
    List<HealthCheckBoard> findAllByOrderByCreatedAtDesc();
}
