package com.agilespace.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/** Contratos da publicação do Poker no Jira: prévia (editável), pedido de envio e fila. */
public final class JiraPublishDtos {

    private JiraPublishDtos() {}

    /**
     * Um passo como o cliente vê e edita. `index` é a posição na lista; `dependsOnIndex` aponta o passo
     * CREATE_SUBTASK cuja chave criada é o alvo de um ADD_COMMENT.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Step {
        private Integer index;
        private String kind;            // RENAME_PARENT | CREATE_SUBTASK | ADD_COMMENT
        private String targetKey;       // pai (RENAME_PARENT) ou chave já conhecida
        private Integer dependsOnIndex; // ADD_COMMENT: índice do CREATE_SUBTASK alvo
        private String summary;         // RENAME_PARENT: novo título da pai; CREATE_SUBTASK: título da sub
        private String issueType;       // CREATE_SUBTASK: nome do tipo no Jira
        private Double estimateHours;   // CREATE_SUBTASK: horas refinadas (null = sem estimativa)
        private String role;            // DEV | QA | NONE
        private String templateId;      // CREATE_SUBTASK: template de origem (validação do tipo)
        private String comment;         // ADD_COMMENT: corpo
        // Só na fila (saída):
        private String status;
        private String resultKey;
        private String errorMessage;
        private Integer attempts;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Preview {
        private String roomId;
        private String issueId;
        private String squadId;
        private String projectKey;
        private String parentKey;
        private String parentIssueType;
        private String currentSummary;
        private boolean hoursDeck;
        private Double devHours;
        private Double qaHours;
        private List<Step> steps;
        private List<String> warnings;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubmitRequest {
        private String issueId;
        private List<Step> steps;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Job {
        private String id;
        private String squadId;
        private String roomId;
        private String issueId;
        private String parentKey;
        private String parentSummary;
        private String requestedBy;
        private String status;
        private String errorMessage;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private LocalDateTime finishedAt;
        private List<Step> steps;
        // Só na tela de fila da squad (nulos no resto):
        private String requestedByName;
        private String roomTitle;
        /** Envio "em andamento" sem sinal de vida há mais de 10 minutos (servidor reiniciou no meio, por exemplo). */
        private boolean stale;
    }

    /** Fila da squad: os envios de todas as salas e o domínio do Jira para montar os links de cada issue. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SquadQueue {
        private String squadId;
        private String jiraDomain;
        private List<Job> jobs;
    }

    /** Uma linha do log de um envio. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Event {
        private String id;
        private Integer stepPosition;
        private String kind;
        private String action;   // SUBMITTED | RETRY | STEP_DONE | STEP_ERROR | JOB_DONE | VERIFIED
        private Boolean ok;
        private String message;
        private String resultKey;
        private String userId;
        private String userName;
        private LocalDateTime createdAt;
    }

    /** Resultado da conferência (somente leitura) de um passo contra o que existe hoje no Jira. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VerifyCheck {
        private Integer index;
        private String kind;
        private String status;   // OK | DIVERGENT | NOT_SENT
        private String detail;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VerifyResult {
        private String jobId;
        private LocalDateTime checkedAt;
        private boolean allOk;
        private int confirmed;
        private int total;
        private List<VerifyCheck> checks;
    }
}
