package com.agilespace.backend.repository;

import com.agilespace.backend.domain.UserJiraConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserJiraConfigRepository extends JpaRepository<UserJiraConfig, String> {
}
