package com.agilespace.backend.repository;

import com.agilespace.backend.domain.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, String> {

    Optional<Conversation> findByUserAIdAndUserBId(String userAId, String userBId);

    @Query("SELECT c FROM Conversation c WHERE c.userAId = :userId OR c.userBId = :userId ORDER BY c.updatedAt DESC")
    List<Conversation> findByParticipant(@Param("userId") String userId);
}
