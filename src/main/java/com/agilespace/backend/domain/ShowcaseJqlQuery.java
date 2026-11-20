package com.agilespace.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.LocalDateTime;

/** Consulta JQL salva da Review, por squad (V81). O servidor só guarda o texto; nunca executa. */
@Entity
@Table(name = "showcase_jql_queries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShowcaseJqlQuery {

    public static final String VISIBILITY_SQUAD = "SQUAD";
    public static final String VISIBILITY_PRIVATE = "PRIVATE";

    @Id
    private String id;

    /** Em MAIÚSCULAS. */
    @Column(name = "squad_id", nullable = false)
    private String squadId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String jql;

    @Column(nullable = false, length = 10)
    private String visibility;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
