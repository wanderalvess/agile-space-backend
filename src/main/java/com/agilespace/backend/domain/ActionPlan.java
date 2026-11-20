package com.agilespace.backend.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "action_plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String creatorId;

    @Column(nullable = false)
    private String title;

    private String team;

    /**
     * Squad dona do plano (V73). Antes só havia "team", texto livre; ele vira squadId quando é o id de uma squad/projeto
     * cadastrado (backfill na migration e na criação). Null = plano sem squad identificada (não entra na listagem por squad).
     */
    @Column(name = "squad_id", length = 100)
    private String squadId;

    @Column(length = 100)
    private String sprintId;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @Builder.Default
    private Boolean isPublic = true;

    /**
     * Origem do plano (V78): MANUAL, RETRO, HEALTH_CHECK, BRAINSTORMING ou SHOWCASE. Nulo no banco = legado = MANUAL
     * (o getter normaliza). Imutável depois da criação.
     */
    @Column(name = "source_type", length = 20)
    private String sourceType;

    /** Id do que originou o plano (radar, retro, board de brainstorming...). Só um rótulo: não concede acesso a nada. */
    @Column(name = "source_id", length = 80)
    private String sourceId;

    @Column(name = "source_title")
    private String sourceTitle;

    /** Só nas listagens (consulta agregada): total de ações e ações concluídas. Não persistidos; ignorados no corpo. */
    @Transient
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer tasksTotal;

    @Transient
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer tasksDone;

    /** Plano anterior à V78 (nulo no banco) é MANUAL. */
    public String getSourceType() {
        return sourceType == null ? "MANUAL" : sourceType;
    }

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "action_plan_participants", joinColumns = @JoinColumn(name = "board_id"))
    @Column(name = "participant_id")
    @Builder.Default
    private Set<String> participantIds = new HashSet<>();
}
