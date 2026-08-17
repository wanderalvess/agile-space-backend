package com.agilespace.backend.repository;

import com.agilespace.backend.domain.UserWorklog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserWorklogRepository extends JpaRepository<UserWorklog, String> {
    List<UserWorklog> findByUserIdAndDate(String userId, String date);
    List<UserWorklog> findByUserIdAndDateIn(String userId, List<String> dates);
}
