package com.agilespace.backend.repository;

import com.agilespace.backend.domain.Invite;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InviteRepository extends JpaRepository<Invite, String> {
    Optional<Invite> findByToken(String token);

    /** Aceitar convite trava a linha: dois aceites simultâneos do mesmo link não podem passar os dois. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Invite i WHERE i.token = :token")
    Optional<Invite> findByTokenForUpdate(@Param("token") String token);
    List<Invite> findBySquadIdOrderByCreatedAtDesc(String squadId);
}
