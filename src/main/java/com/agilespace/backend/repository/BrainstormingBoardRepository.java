package com.agilespace.backend.repository;

import com.agilespace.backend.domain.BrainstormingBoard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BrainstormingBoardRepository extends JpaRepository<BrainstormingBoard, String> {
    List<BrainstormingBoard> findAllByOrderByCreatedAtDesc();
}
