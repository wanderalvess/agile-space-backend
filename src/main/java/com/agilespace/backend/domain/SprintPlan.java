package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Plano de sprint do Sprint Planner (V86). Um por squad+sprint do Jira (decisão 6). Papel/dias/horas por pessoa NÃO
 * ficam aqui: vêm de squad_person_configs via SquadCapacityService, com sprintKey = jiraSprintId, ou "plan:{id}"
 * enquanto o plano não tem sprint do Jira.
 */
@Entity
@Table(name = "sprint_plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SprintPlan {

    public static final String STATUS_RASCUNHO = "RASCUNHO";
    public static final String STATUS_ESCOPO = "ESCOPO";
    public static final String STATUS_DISTRIBUICAO = "DISTRIBUICAO";
    public static final String STATUS_REVISAO = "REVISAO";
    public static final String STATUS_ENVIADO = "ENVIADO";
    public static final List<String> STATUS_ORDER =
            List.of(STATUS_RASCUNHO, STATUS_ESCOPO, STATUS_DISTRIBUICAO, STATUS_REVISAO, STATUS_ENVIADO);

    public static final String RESERVE_HORAS = "HORAS";
    public static final String RESERVE_FOCO = "FOCO";

    @Id
    private String id;

    @Column(name = "squad_id", nullable = false)
    private String squadId;

    @Column(name = "jira_sprint_id", length = 64)
    private String jiraSprintId;

    @Column(name = "sprint_name", nullable = false)
    private String sprintName;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "goal", columnDefinition = "TEXT")
    private String goal;

    /** Meta da sprint como está no Jira (lida ao criar a partir da próxima sprint e pelo sync do backlog). */
    @Column(name = "jira_goal", columnDefinition = "TEXT")
    private String jiraGoal;

    @Column(name = "reserve_mode", nullable = false, length = 16)
    private String reserveMode;

    @Column(name = "reserve_pct", precision = 5, scale = 2)
    private BigDecimal reservePct;

    @Column(name = "focus_factor_pct", precision = 5, scale = 2)
    private BigDecimal focusFactorPct;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Chave de sprint usada em squad_person_configs para este plano. */
    public String sprintKey() {
        return jiraSprintId != null && !jiraSprintId.isBlank() ? jiraSprintId : "plan:" + id;
    }
}
