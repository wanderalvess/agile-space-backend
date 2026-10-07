package com.agilespace.backend.repository;

import com.agilespace.backend.domain.PokerRoom;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PokerRoomRepository extends JpaRepository<PokerRoom, String> {
    Page<PokerRoom> findByTeamIgnoreCaseOrderByCreatedAtDesc(String team, Pageable pageable);

    // Lock de escrita: várias pessoas editam as notas do refinamento ao mesmo tempo e cada
    // edição é um read-modify-write no JSON issues_queue; sem lock uma sobrescreveria a outra.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PokerRoom r where r.id = :id")
    Optional<PokerRoom> findByIdForUpdate(@Param("id") String id);
}
