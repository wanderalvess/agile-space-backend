package com.agilespace.backend.repository;

import com.agilespace.backend.domain.JoltGlossaryTerm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JoltGlossaryTermRepository extends JpaRepository<JoltGlossaryTerm, UUID> {

    Optional<JoltGlossaryTerm> findByTermFromAndTermTo(String termFrom, String termTo);
}
