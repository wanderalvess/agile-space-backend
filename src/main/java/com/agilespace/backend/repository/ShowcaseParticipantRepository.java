package com.agilespace.backend.repository;

import com.agilespace.backend.domain.ShowcaseParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ShowcaseParticipantRepository extends JpaRepository<ShowcaseParticipant, String> {

    List<ShowcaseParticipant> findBySessionIdOrderBySortOrderAsc(String sessionId);

    List<ShowcaseParticipant> findBySessionIdInOrderBySortOrderAsc(Collection<String> sessionIds);

    void deleteBySessionId(String sessionId);
}
