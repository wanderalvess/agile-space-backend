package com.agilespace.backend.repository;

import com.agilespace.backend.domain.LinkRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LinkRequestRepository extends JpaRepository<LinkRequest, String> {

    boolean existsByRequesterUserIdAndProjectKeyAndStatus(String requesterUserId, String projectKey, String status);

    long countByRequesterUserIdAndStatus(String requesterUserId, String status);

    List<LinkRequest> findByRequesterUserIdOrderByCreatedAtDesc(String requesterUserId);

    List<LinkRequest> findAllByOrderByCreatedAtDesc();

    List<LinkRequest> findByStatusOrderByCreatedAtDesc(String status);

    /** Trava a linha para decidir: duas decisões simultâneas do mesmo pedido não passam as duas. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM LinkRequest r WHERE r.id = :id")
    Optional<LinkRequest> findByIdForUpdate(@Param("id") String id);
}
