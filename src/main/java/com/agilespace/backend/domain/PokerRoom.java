package com.agilespace.backend.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "poker_rooms")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PokerRoom {

    @Id
    private String id; // ID da sala

    @Column(length = 50)
    private String deckType;

    @Builder.Default
    private Boolean votesRevealed = false;

    private String creatorId; // Removido nullable=false para resiliência a atualizações parciais

    private String currentTopic;

    private String activeIssueId;

    private String title;

    private String team;

    private String sessionStartedAt;

    private String sessionEndedAt;

    private String createdAt;

    @Column(length = 50)
    private String mode; // sync, async

    private String selectiveRevotingRole;

    @Builder.Default
    private Integer participantsCount = 0;

    // --- JSONB Fields mapping via Hibernate 6 JSON type ---
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "timer", columnDefinition = "jsonb")
    private JsonNode timer;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "issues_queue", columnDefinition = "jsonb")
    private JsonNode issuesQueue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "participant_ids", columnDefinition = "jsonb")
    private JsonNode participantIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary", columnDefinition = "jsonb")
    private JsonNode summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "revealed_issues", columnDefinition = "jsonb")
    private JsonNode revealedIssues;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reference_baseline", columnDefinition = "jsonb")
    private JsonNode referenceBaseline;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings", columnDefinition = "jsonb")
    private JsonNode settings;
}
