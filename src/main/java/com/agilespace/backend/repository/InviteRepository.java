package com.agilespace.backend.repository;

import com.agilespace.backend.domain.Invite;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Consome 1 uso de um convite de equipe de forma atômica: o teto, a validade e a revogação são checados na própria
     * UPDATE, então duas pessoas disputando o último uso nunca passam as duas (a segunda recebe 0 linhas).
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Invite i SET i.usesCount = i.usesCount + 1 WHERE i.id = :id AND i.multiUse = true AND i.status = 'PENDING' "
            + "AND i.revokedAt IS NULL AND i.expiresAt > :now AND (i.maxUses IS NULL OR i.usesCount < i.maxUses)")
    int consumeUse(@Param("id") String id, @Param("now") java.time.LocalDateTime now);
}
