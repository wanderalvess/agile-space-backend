package com.agilespace.backend.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

// Cache compartilhado do resultado de uma consulta JQL do JiraDash. Chave é a
// JQL normalizada (não a squad — o "squad" do JiraDash é só um agrupamento
// local por navegador, sem identidade compartilhada; a JQL é o que de fato
// identifica "os mesmos dados" entre usuários diferentes). Sem TTL: quem
// decide buscar de novo no Jira é sempre o cliente (botão "Atualizar").
@Entity
@Table(name = "jiradash_snapshots")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class JiraDashSnapshot {

    @Id
    @Column(name = "id")
    private String id; // UUID determinístico derivado da JQL normalizada

    @Column(name = "jql", columnDefinition = "TEXT", nullable = false)
    private String jql; // texto original, pra debug/inspeção

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private JsonNode payload; // { allIssues, sprintDates, sprintInfo, sprintRemoved }

    @Column(name = "fetched_at")
    private LocalDateTime fetchedAt;

    @Column(name = "fetched_by_user_id")
    private String fetchedByUserId;

    @Column(name = "fetched_by_name")
    private String fetchedByName;

    @PrePersist
    @PreUpdate
    void touch() {
        fetchedAt = LocalDateTime.now();
    }
}
