package com.agilespace.backend.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Uma única ação no Jira. Só existem três tipos — renomear a pai, criar sub-tarefa e comentar — e é isso que
 * limita o que o sistema consegue fazer no Jira: não há passo de apagar, transicionar ou editar outro campo.
 */
@Entity
@Table(name = "jira_publish_steps")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JiraPublishStep {

    public static final String RENAME_PARENT = "RENAME_PARENT";
    public static final String CREATE_SUBTASK = "CREATE_SUBTASK";
    public static final String ADD_COMMENT = "ADD_COMMENT";

    public static final String PENDING = "PENDING";
    public static final String DONE = "DONE";
    public static final String ERROR = "ERROR";
    public static final String SKIPPED = "SKIPPED";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "job_id", nullable = false, length = 36)
    private String jobId;

    @Column(nullable = false)
    private Integer position;

    @Column(nullable = false, length = 30)
    private String kind;

    /** Issue alvo. No comentário de uma sub recém-criada fica nulo até o passo de criação resolver a chave. */
    @Column(name = "target_key", length = 60)
    private String targetKey;

    /** Id do passo CREATE_SUBTASK cuja chave criada vira o alvo deste comentário. */
    @Column(name = "depends_on", length = 36)
    private String dependsOn;

    /**
     * RENAME_PARENT: {summary}. CREATE_SUBTASK: {projectKey, issueType, summary, estimateHours, role}.
     * ADD_COMMENT: {body}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(nullable = false, length = 20)
    private String status;

    /** Chave criada no Jira (CREATE_SUBTASK) ou id do comentário (ADD_COMMENT). */
    @Column(name = "result_key", length = 60)
    private String resultKey;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
