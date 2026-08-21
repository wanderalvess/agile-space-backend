package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    private String id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String role;

    @Column(name = "jira_account_id")
    private String jiraAccountId;

    @Column(name = "sso_id", unique = true)
    private String ssoId;

    @Column(name = "squad_id")
    private String squadId;

    @Column(name = "avatar_seed")
    private String avatarSeed;

    @Column(name = "daily_hours")
    private Integer dailyHours;

    @Column(name = "is_guest", nullable = false)
    private boolean isGuest;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

