package com.agilespace.backend.repository;

import com.agilespace.backend.domain.BrainstormingGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BrainstormingGroupRepository extends JpaRepository<BrainstormingGroup, String> {
    List<BrainstormingGroup> findByBoardIdOrderByOrderAsc(String boardId);
    void deleteByBoardId(String boardId);
}
