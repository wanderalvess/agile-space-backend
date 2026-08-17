package com.agilespace.backend.repository;

import com.agilespace.backend.domain.RetroBoard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RetroBoardRepository extends JpaRepository<RetroBoard, String> {
}
