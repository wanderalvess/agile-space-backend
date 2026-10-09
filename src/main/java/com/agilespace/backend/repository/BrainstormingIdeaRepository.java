package com.agilespace.backend.repository;

import com.agilespace.backend.domain.BrainstormingIdea;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BrainstormingIdeaRepository extends JpaRepository<BrainstormingIdea, String> {
    List<BrainstormingIdea> findByBoardId(String boardId);
    List<BrainstormingIdea> findByBoardIdAndParentId(String boardId, String parentId);
    List<BrainstormingIdea> findByBoardIdAndGroupId(String boardId, String groupId);
    void deleteByBoardId(String boardId);

    /** Lock pessimista na ideia: voto, fusão e edição parcial não se sobrescrevem. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from BrainstormingIdea i where i.id = :id")
    Optional<BrainstormingIdea> findByIdForUpdate(@Param("id") String id);
}
