package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Aviso do portal. v2 (V72): tipo, público (ALL / SQUADS / ROLES), vigência e link.
 * Datas em UTC (LocalDateTime da JVM, ver JacksonUtcConfig).
 */
@Entity
@Table(name = "global_announcements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GlobalAnnouncement {

    @Id
    private String id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** INFO | IMPORTANT | MAINTENANCE */
    @Column(name = "type", nullable = false, length = 20)
    @Builder.Default
    private String type = "INFO";

    /** ALL | SQUADS | ROLES */
    @Column(name = "audience_type", nullable = false, length = 10)
    @Builder.Default
    private String audienceType = "ALL";

    @Column(name = "valid_from", nullable = false)
    private LocalDateTime validFrom;

    @Column(name = "valid_until")
    private LocalDateTime validUntil;

    @Column(name = "link", length = 500)
    private String link;

    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @CollectionTable(name = "global_announcement_squads", joinColumns = @JoinColumn(name = "announcement_id"))
    @Column(name = "squad_id", length = 100)
    @Builder.Default
    private Set<String> audienceSquadIds = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @CollectionTable(name = "global_announcement_roles", joinColumns = @JoinColumn(name = "announcement_id"))
    @Column(name = "role", length = 50)
    @Builder.Default
    private Set<String> audienceRoles = new LinkedHashSet<>();
}
