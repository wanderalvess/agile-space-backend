package com.agilespace.backend.repository;

import com.agilespace.backend.domain.UserTdnConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserTdnConfigRepository extends JpaRepository<UserTdnConfig, String> {
}
