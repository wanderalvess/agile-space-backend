package com.agilespace.backend.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "poker_rounds")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PokerRound {

    @Id
    private String id; // UUID gerado ou informado

    @Column(name = "room_id", nullable = false)
    private String roomId;

    private String topic;

    private String issueId;

    @Column(length = 50)
    private String deckType;

    @Column(name = "round_timestamp") // Mapeado para evitar palavra reservada 'timestamp' do Postgres
    private String timestamp;

    @Builder.Default
    private Boolean skipped = false;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(length = 50)
    private String devPoints;

    @Column(length = 50)
    private String qaPoints;

    // --- JSONB Fields mapping via Hibernate 6 JSON type ---
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "votes", columnDefinition = "jsonb")
    private JsonNode votes; // Lista de votos na rodada

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stats", columnDefinition = "jsonb")
    private JsonNode stats; // Estatísticas gerais da rodada

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "role_points", columnDefinition = "jsonb")
    private JsonNode rolePoints; // Pontos por papel
}
