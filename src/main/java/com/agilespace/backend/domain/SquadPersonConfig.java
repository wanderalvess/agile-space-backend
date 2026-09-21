package com.agilespace.backend.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

/**
 * Papel (DEV/QA) e capacidade por pessoa, com herança sprint atual → sprint anterior mais
 * recente do squad → default global do squad — transliterado de
 * agile-space-frontend/public/jiradash/assets/js/domain/person-config.js, mas sem a
 * maquinaria de escopo/reconciliação/redação de identidade daquele arquivo: aqui a pessoa
 * já é identificada por jiraAccountId estável (SquadMember), não por displayName cru, que é
 * exatamente o problema que aquela maquinaria existia para contornar (ver plano de
 * unificação Squad Pulse + jiradash, Fase 6).
 */
@Entity
@Table(name = "squad_person_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class SquadPersonConfig {

    // Sentinela pro default do squad (fallback final, sem sprint específica) — mesma
    // convenção de sentinela de string já usada em UNMAPPED_SPRINT_ID (SquadSyncService).
    public static final String GLOBAL_SPRINT_ID = "GLOBAL";

    @Id
    @Column(name = "db_id")
    private String dbId; // PK = {squadId}_{sprintId}_{jiraAccountId} (sprintId = GLOBAL_SPRINT_ID pro default)

    @Column(name = "squad_id", nullable = false)
    private String squadId;

    @Column(name = "sprint_id", nullable = false)
    private String sprintId;

    @Column(name = "jira_account_id", nullable = false)
    private String jiraAccountId;

    @Column(name = "papel")
    private String papel; // "DEV" | "QA" | "" | null (sem configuração)

    @Column(name = "dias_codificacao_teste")
    private Integer diasCodificacaoTeste;

    @Column(name = "dias_regressivo")
    private Integer diasRegressivo;

    @Column(name = "horas_produtivas")
    private Double horasProdutivas;

    @Column(name = "updated_at")
    private String updatedAt;
}
