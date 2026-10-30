package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Sub-tarefa que a squad cria no Jira para cada issue refinada (Codificação, Code Review, Code Review QA...).
 * O nome do tipo é o do Jira daquele projeto, definido na tela de configuração — nada fixo no código.
 * Compartilhado por Poker e Sprint Planner.
 */
@Entity
@Table(name = "squad_subtask_templates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SquadSubtaskTemplate {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "squad_id", nullable = false, length = 100)
    private String squadId;

    /** Tipo da issue pai a que se aplica (ex.: "História", "Manutenção"). Nulo/vazio = qualquer tipo. */
    @Column(name = "parent_issue_type", length = 100)
    private String parentIssueType;

    /** Nome exato do tipo de sub-tarefa no Jira do projeto. */
    @Column(name = "jira_issue_type_name", nullable = false, length = 100)
    private String jiraIssueTypeName;

    /** DEV ou QA recebem as horas refinadas daquele papel; NONE não recebe estimativa. */
    @Column(nullable = false, length = 10)
    private String role;

    /** Variáveis: {pai} {resumo} {tipo} {horas} {papel}. */
    @Column(name = "title_template", nullable = false, columnDefinition = "TEXT")
    private String titleTemplate;

    /** Comentário publicado NA sub-tarefa criada. Mesmas variáveis mais {notas}. Vazio = não comenta. */
    @Column(name = "comment_template", columnDefinition = "TEXT")
    private String commentTemplate;

    @Column(nullable = false)
    @Builder.Default
    private Integer position = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
