package com.agilespace.backend.repository;

import com.agilespace.backend.domain.Squad;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SquadRepository extends JpaRepository<Squad, String> {
}
