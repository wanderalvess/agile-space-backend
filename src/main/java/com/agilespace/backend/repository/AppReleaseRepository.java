package com.agilespace.backend.repository;

import com.agilespace.backend.domain.AppRelease;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AppReleaseRepository extends JpaRepository<AppRelease, String> {

    List<AppRelease> findByIsPublishedTrueOrderByCreatedAtDesc();

    List<AppRelease> findAllByOrderByCreatedAtDesc();

    Optional<AppRelease> findFirstByIsPublishedTrueOrderByCreatedAtDesc();

    Optional<AppRelease> findByTag(String tag);

    boolean existsByTag(String tag);
}
