package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;

/** Tipos de issue da instância do Jira da squad (GET /rest/api/2/issuetype), para a tela de mapeamento. */
@Entity
@Table(name = "jira_issue_types_cache")
@IdClass(JiraIssueTypeCache.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JiraIssueTypeCache {

    @Id
    @Column(name = "squad_id")
    private String squadId;

    @Id
    @Column(name = "type_id")
    private String typeId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "is_subtask", nullable = false)
    private boolean subtask;

    @Column(name = "fetched_at", nullable = false)
    private String fetchedAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private String squadId;
        private String typeId;
    }
}
