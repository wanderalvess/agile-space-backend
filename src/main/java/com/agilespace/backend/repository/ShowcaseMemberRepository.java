package com.agilespace.backend.repository;

import com.agilespace.backend.domain.ShowcaseMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShowcaseMemberRepository extends JpaRepository<ShowcaseMember, String> {

    List<ShowcaseMember> findBySessionIdOrderByOrderAsc(String sessionId);

    void deleteBySessionId(String sessionId);
}
