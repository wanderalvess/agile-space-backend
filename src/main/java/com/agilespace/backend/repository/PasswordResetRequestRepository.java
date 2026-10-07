package com.agilespace.backend.repository;

import com.agilespace.backend.domain.PasswordResetRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PasswordResetRequestRepository extends JpaRepository<PasswordResetRequest, String> {
    List<PasswordResetRequest> findByStatusOrderByRequestedAtDesc(String status);
    List<PasswordResetRequest> findAllByOrderByRequestedAtDesc();
}
