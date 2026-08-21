package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Representa um Projeto / Squad com os metadados de Governança corporativa do Jira Profields (TOTVS).
 */
@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectConfig {

    @Id
    @Column(name = "id", length = 50)
    private String id; // Ex: "DDWMISSI" (Chave do Projeto Jira)

    @Column(nullable = false)
    private String name; // Nome do Projeto

    @Column(name = "segment_name")
    private String segmentName; // Ex: "Distribuição TOTVS Goiânia"

    @Column(name = "tribe_name")
    private String tribeName; // Ex: "Distribuição"

    @Column(name = "locality")
    private String locality; // Ex: "Goiânia"

    @Column(name = "vice_president")
    private String vicePresident; // Ex: "Marcelo Eduardo Sant'anna Cosentino"

    @Column(name = "vp_area")
    private String vpArea; // Ex: "Torres"

    @Column(name = "dev_team_size")
    private Integer devTeamSize; // Número de Pessoas no DevTeam

    @Column(name = "status")
    private String status; // Ex: "EM ANDAMENTO"

    @Column(name = "creation_date")
    private String creationDate; // Ex: "19/06/24"

    // ----- Campos de Validações de Fluxo -----

    @Column(name = "auto_tdn_doc")
    private Boolean autoTdnDoc; // Documentação Automática TDN

    @Column(name = "disable_auto_subtasks")
    private Boolean disableAutoSubtasks; // Desativar Sub-tarefa Automática

    @Column(name = "specific_subtasks", columnDefinition = "TEXT")
    private String specificSubtasks; // Cria sub-tarefa específica

    @Column(name = "saas_expedition")
    private Boolean saasExpedition; // Expedição SAAS

    @Column(name = "engineering_only_expedition")
    private Boolean engineeringOnlyExpedition; // Expedição Exclusiva Engenharia

    @Column(name = "optional_worklog")
    private Boolean optionalWorklog; // Apontamento Opcional

    @Column(name = "profields_raw_json", columnDefinition = "TEXT")
    private String profieldsRawJson; // JSON bruto do Profields para compatibilidade com novos campos futuros

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
