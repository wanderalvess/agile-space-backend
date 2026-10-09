package com.agilespace.backend.repository;

import com.agilespace.backend.domain.PokerParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PokerParticipantRepository extends JpaRepository<PokerParticipant, String> {
    List<PokerParticipant> findByRoomIdOrderByNicknameAsc(String roomId);
    Optional<PokerParticipant> findByRoomIdAndId(String roomId, String id);
    void deleteByRoomIdAndId(String roomId, String id);
    void deleteByRoomId(String roomId);

    /** Heartbeat: grava só last_seen, sem reescrever o resto da linha (papel/facilitador). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PokerParticipant p set p.lastSeen = :lastSeen where p.dbId = :dbId")
    int updateLastSeen(@Param("dbId") String dbId, @Param("lastSeen") String lastSeen);

    /** Define a flag de facilitador explicitamente (o upsert só promove, nunca rebaixa). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PokerParticipant p set p.isFacilitator = :value where p.dbId = :dbId")
    int setFacilitator(@Param("dbId") String dbId, @Param("value") boolean value);

    /**
     * Entrada idempotente na sala. Dois POSTs simultâneos (ex.: join + sincronização de perfil)
     * não geram mais violação da PK: o segundo apenas atualiza. A flag de facilitador nunca é rebaixada.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO poker_participants (db_id, user_id, room_id, nickname, email, role, is_facilitator, global_role, last_seen)
            VALUES (:dbId, :userId, :roomId, :nickname, :email, :role, :isFacilitator, :globalRole, :lastSeen)
            ON CONFLICT (db_id) DO UPDATE SET
                nickname = EXCLUDED.nickname,
                email = EXCLUDED.email,
                role = EXCLUDED.role,
                global_role = EXCLUDED.global_role,
                is_facilitator = COALESCE(poker_participants.is_facilitator, false) OR COALESCE(EXCLUDED.is_facilitator, false),
                last_seen = EXCLUDED.last_seen
            """, nativeQuery = true)
    void upsertParticipant(@Param("dbId") String dbId,
                           @Param("userId") String userId,
                           @Param("roomId") String roomId,
                           @Param("nickname") String nickname,
                           @Param("email") String email,
                           @Param("role") String role,
                           @Param("isFacilitator") Boolean isFacilitator,
                           @Param("globalRole") String globalRole,
                           @Param("lastSeen") String lastSeen);
}
