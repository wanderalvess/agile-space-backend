package com.agilespace.backend.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_user_email", columnList = "email"),
    @Index(name = "idx_user_jira_account", columnList = "jira_account_id"),
    @Index(name = "idx_user_sso", columnList = "sso_id")
})
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

    // Nunca deve ir em resposta HTTP nenhuma (é o hash PBKDF2 da senha).
    @JsonIgnore
    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "auth_provider")
    @Builder.Default
    private String authProvider = "LOCAL"; // "LOCAL", "SSO"

    public String getAuthProvider() {
        return authProvider != null ? authProvider : "LOCAL";
    }

    /** Tier de autorização do sistema. Único uso: liberar /api/admin/** (ver JwtAuthenticationFilter).
     *  Nunca confundir com o cargo de negócio do usuário — isso vive em {@link #jobTitle}. */
    @Column(name = "role")
    @Builder.Default
    private String role = "MEMBER"; // "ADMIN", "LEAD", "MEMBER" — ver UserRole

    public String getRole() {
        return role != null ? role : "MEMBER";
    }

    /** Cargo de negócio autodeclarado (ex: "Tech Lead", "Product Owner"). Campo de auto-serviço,
     *  sem qualquer papel em autorização — não usar em checagem de acesso. */
    @Column(name = "job_title")
    private String jobTitle;

    @Column(name = "jira_account_id")
    private String jiraAccountId;

    @Column(name = "sso_id", unique = true)
    private String ssoId;

    @Column(name = "default_project_id")
    private String defaultProjectId; // FK lógica para ProjectConfig

    @Column(name = "squad_id")
    private String squadId;

    @Column(name = "segment_name")
    private String segmentName; // Ex: "Canal Corporativo"

    @Column(name = "tribe_name")
    private String tribeName; // Ex: "Distribuição"

    @Column(name = "avatar_seed")
    private String avatarSeed;

    @Column(name = "daily_hours")
    private Integer dailyHours;

    @Column(name = "is_guest")
    @Builder.Default
    private boolean isGuest = false;

    @Column(name = "is_active")
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void onPrePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void onPreUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
