package com.agilespace.backend.repository;

import com.agilespace.backend.domain.Invite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InviteRepository extends JpaRepository<Invite, String> {
    Optional<Invite> findByToken(String token);
    List<Invite> findBySquadIdOrderByCreatedAtDesc(String squadId);
}
