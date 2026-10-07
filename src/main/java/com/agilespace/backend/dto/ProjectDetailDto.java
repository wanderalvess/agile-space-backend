package com.agilespace.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO que consolida todas as informações do Projeto / Governança (Profields TOTVS).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectDetailDto {

    private String id; // Ex: "DDWMISSI"
    private String name;

    // ----- 1. Informações Gerais -----
    private String segmentName; // Ex: "Distribuição TOTVS Goiânia"
    private String tribeName;   // Ex: "Distribuição"
    private String locality;    // Ex: "Goiânia"
    private String vicePresident; // Ex: "Marcelo Eduardo Sant'anna Cosentino"
    private String vpArea;      // Ex: "Torres"

    // ----- 2. Pessoas & Lideranças -----
    private List<ProjectMemberRoleDto> members;

    // ----- 3. Status e Números -----
    private Integer devTeamSize; // Número de Pessoas no DevTeam
    private String status;       // Ex: "EM ANDAMENTO"
    private String creationDate; // Ex: "19/06/24"

    // ----- 4. Campos de Validações de Fluxo -----
    private Boolean autoTdnDoc;                 // Documentação Automática TDN
    private Boolean disableAutoSubtasks;        // Desativar Sub-tarefa Automática
    private String specificSubtasks;            // Cria sub-tarefa específica
    private Boolean saasExpedition;             // Expedição SAAS
    private Boolean engineeringOnlyExpedition;  // Expedição Exclusiva Engenharia
    private Boolean optionalWorklog;            // Apontamento Opcional

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
