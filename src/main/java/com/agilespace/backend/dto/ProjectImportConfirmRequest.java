package com.agilespace.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Confirmação da importação do Jira (Profields) com o que o usuário conferiu e ajustou na prévia:
 * campos do projeto editados e a lista de pessoas SELECIONADAS, com cargo ajustado e vínculo "sou eu".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectImportConfirmRequest {
    /** Nome do time (o Jira guarda a chave, ex.: DDWMISSI, separada do nome, ex.: Projeto X - Y). */
    private String name;
    private String segmentName;
    private String tribeName;
    private String locality;
    private String vicePresident;
    private String vpArea;
    private String status;
    private String creationDate;
    private List<Member> members;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Member {
        private String jiraAccountId;
        private String email;
        private String displayName;
        /** Cargo escolhido na prévia. Cargos de liderança só valem se já vieram do Jira. */
        private String roleName;
        /** O usuário que está importando marcou esta pessoa como "sou eu". */
        private boolean linkToMe;
    }
}
