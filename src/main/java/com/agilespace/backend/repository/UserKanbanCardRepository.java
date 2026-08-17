package com.agilespace.backend.repository;

import com.agilespace.backend.domain.UserKanbanCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface UserKanbanCardRepository extends JpaRepository<UserKanbanCard, String> {
    List<UserKanbanCard> findByUserIdOrderByUpdatedAtDesc(String userId);
}
