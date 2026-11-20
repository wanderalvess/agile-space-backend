package com.agilespace.backend.repository;

import com.agilespace.backend.domain.PromptFavorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface PromptFavoriteRepository extends JpaRepository<PromptFavorite, PromptFavorite.Key> {
    List<PromptFavorite> findByUserIdAndPromptIdIn(String userId, Collection<UUID> promptIds);
}
