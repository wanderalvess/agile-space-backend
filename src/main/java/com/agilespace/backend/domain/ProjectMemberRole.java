package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Papéis e cargos oficiais de membros em um Projeto / Squad (vindos do Jira Profields ou atribuídos).
 */
@Entity
@Table(name = "project_member_roles", indexes = {
    @Index(name = "idx_pmr_project", columnList = "project_id"),
    @Index(name = "idx_pmr_email", columnList = "email"),
    @Index(name = "idx_pmr_jira_account", columnList = "jira_account_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectMemberRole {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "project_id", nullable = false, length = 50)
    private String projectId; // FK para ProjectConfig (ex: "DDWMISSI")

    @Column(name = "role_name", nullable = false)
    private String roleName; // Ex: "Agile Master", "Agile Coach", "Product Owner", "Product Manager", "People Lead", "Tribe Lead", "Team Lead", "Arquiteto Digital", "Dev Team"

    @Column(name = "role_key", nullable = false, length = 50)
    private String roleKey; // Ex: "AGILE_MASTER", "AGILE_COACH", "PRODUCT_OWNER", "PRODUCT_MANAGER", "PEOPLE_LEAD", "TRIBE_LEAD", "TEAM_LEAD", "DIGITAL_ARCHITECT", "DEV_TEAM"

    @Column(name = "jira_account_id")
    private String jiraAccountId;

    @Column(name = "display_name", nullable = false)
    private String displayName; // Ex: "Marielen Cristine de Almeida Leite"

    @Column(name = "email")
    private String email; // Ex: "marielen.leite@totvs.com.br"

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "user_id")
    private String userId; // FK opcional para User quando cadastrado

    @Column(name = "is_leadership", nullable = false)
    private boolean isLeadership; // Indica se é papel de liderança ou transversal

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
