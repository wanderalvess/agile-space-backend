package com.agilespace.backend.repository;

import com.agilespace.backend.domain.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface FeedbackRepository extends JpaRepository<Feedback, String> {
    List<Feedback> findByOrderByCreatedAtDesc();
}
