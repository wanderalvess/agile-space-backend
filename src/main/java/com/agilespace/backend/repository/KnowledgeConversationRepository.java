package com.agilespace.backend.repository;

import com.agilespace.backend.domain.KnowledgeConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface KnowledgeConversationRepository extends JpaRepository<KnowledgeConversation, UUID> {

    List<KnowledgeConversation> findByUserIdOrderByUpdatedAtDesc(String userId);
}
