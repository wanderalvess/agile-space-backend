package com.agilespace.backend.repository;

import com.agilespace.backend.domain.JiraDashSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JiraDashSnapshotRepository extends JpaRepository<JiraDashSnapshot, String> {
}
