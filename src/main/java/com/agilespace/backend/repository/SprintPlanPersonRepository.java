package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SprintPlanPerson;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SprintPlanPersonRepository extends JpaRepository<SprintPlanPerson, SprintPlanPerson.Key> {
    List<SprintPlanPerson> findByPlanId(String planId);
}
