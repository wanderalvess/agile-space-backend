package com.agilespace.backend.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "brainstorming_ideas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BrainstormingIdea {

    @Id
    private String id; // UUID da ideia

    @Column(name = "board_id", nullable = false)
    private String boardId;

    @Column(columnDefinition = "TEXT")
    private String content;

    private String authorId;

    private String parentId;

    private String groupId;

    private String createdAt;

    private String color;

    /** Última alteração do TEXTO (ISO-8601); nulo = nunca editada. Mover/agrupar/priorizar não conta. */
    @Column(name = "edited_at", length = 40)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String editedAt;

    /** userId (do token) de quem alterou o texto por último. Saída só em mural não anônimo. */
    @Column(name = "edited_by")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String editedBy;

    /** Só na saída: apelido do autor (mural não anônimo e ideia legível para quem lê). Não é gravado. */
    @Transient
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String authorName;

    /** Só na saída: apelido de quem editou (mural não anônimo). Não é gravado. */
    @Transient
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String editedByName;

    /** Só na saída: ideia redigida para quem ainda não pode ler o texto (não é gravada no banco). */
    @Transient
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean hidden;

    // --- JSONB Fields mapping via Hibernate 6 JSON type ---
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "votes", columnDefinition = "jsonb")
    private JsonNode votes; // Array de UIDs de votos

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "position", columnDefinition = "jsonb")
    private JsonNode position; // Coordenadas {x, y}

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "qualifiers", columnDefinition = "jsonb")
    private JsonNode qualifiers; // {roi, effort}
}
