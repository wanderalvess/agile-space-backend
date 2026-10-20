package com.agilespace.backend.repository;

import com.agilespace.backend.domain.InviteRedemption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InviteRedemptionRepository extends JpaRepository<InviteRedemption, String> {
    boolean existsByInviteIdAndUserId(String inviteId, String userId);

    List<InviteRedemption> findByInviteIdOrderByCreatedAtAsc(String inviteId);
}
