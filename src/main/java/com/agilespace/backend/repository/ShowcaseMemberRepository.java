package com.agilespace.backend.repository;

import com.agilespace.backend.domain.ShowcaseMember;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShowcaseMemberRepository extends JpaRepository<ShowcaseMember, String> {

    List<ShowcaseMember> findBySessionIdOrderByOrderAsc(String sessionId);

    void deleteBySessionId(String sessionId);

    @Query("SELECT m.sessionId, COUNT(m) FROM ShowcaseMember m WHERE m.sessionId IN :sessionIds GROUP BY m.sessionId")
    List<Object[]> countBySession(@Param("sessionIds") Collection<String> sessionIds);
}
