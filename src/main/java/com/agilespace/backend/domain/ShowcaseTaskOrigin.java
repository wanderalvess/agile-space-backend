package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** De onde veio o texto de um campo do card (Problema, Solução, Critérios) e o que o Jira tinha. Um por card e campo. */
@Entity
@Table(name = "showcase_task_origins")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShowcaseTaskOrigin {

    @Id
    private String id;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "field_name", nullable = false)
    private String field;

    @Column(name = "source_kind", nullable = false)
    private String sourceKind;

    @Column(name = "source_label", nullable = false)
    private String sourceLabel;

    @Column(name = "original_text", columnDefinition = "TEXT", nullable = false)
    private String originalText;

    @Column(name = "imported_by")
    private String importedBy;

    @Column(name = "imported_at", nullable = false)
    private LocalDateTime importedAt;
}
