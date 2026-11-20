package com.agilespace.backend.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Template de sessão do Scrum Poker, compartilhado pela squad: baralho, configurações da sala e uma fila de
 * tarefas para começar. settings e backlog têm o mesmo formato que a sala usa no frontend.
 */
@Entity
@Table(name = "squad_poker_templates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SquadPokerTemplate {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "squad_id", nullable = false, length = 100)
    private String squadId;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(name = "deck_type", nullable = false, length = 40)
    private String deckType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings", columnDefinition = "jsonb", nullable = false)
    private JsonNode settings;

    /** [{title, jiraLink?, type?}] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "backlog", columnDefinition = "jsonb", nullable = false)
    private JsonNode backlog;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    /** Nome de quem salvou, no momento em que salvou (só para exibir). */
    @Column(name = "created_by_name")
    private String createdByName;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
