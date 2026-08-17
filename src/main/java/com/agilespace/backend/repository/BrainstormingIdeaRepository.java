package com.agilespace.backend.repository;

import com.agilespace.backend.domain.BrainstormingIdea;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BrainstormingIdeaRepository extends JpaRepository<BrainstormingIdea, String> {
    List<BrainstormingIdea> findByBoardId(String boardId);
    void deleteByBoardId(String boardId);
}
