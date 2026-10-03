package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SquadPanel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SquadPanelRepository extends JpaRepository<SquadPanel, UUID> {
    List<SquadPanel> findBySquadIdAndVisibility(String squadId, String visibility);
    List<SquadPanel> findBySquadIdAndOwnerId(String squadId, String ownerId);
}
