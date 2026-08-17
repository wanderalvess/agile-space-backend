package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SystemConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SystemConfigRepository extends JpaRepository<SystemConfig, String> {
}
