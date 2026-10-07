package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SupportTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SupportTicketRepository extends JpaRepository<SupportTicket, UUID> {
    List<SupportTicket> findByRequesterIdOrderByCreatedAtDesc(String requesterId);
    List<SupportTicket> findByStatusOrderByCreatedAtDesc(String status);
    List<SupportTicket> findAllByOrderByCreatedAtDesc();
}
