package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "retro_boards")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetroBoard {

    @Id
    private String id; // UUID ou informado pelo criador

    @Column(nullable = false)
    private String creatorId;

    @Builder.Default
    private Boolean isCardsRevealed = false;

    @Builder.Default
    private Boolean isAuthorsRevealed = false;

    @Column(length = 50)
    @Builder.Default
    private String votingStatus = "disabled";

    @Column(nullable = false)
    private String title;

    @Builder.Default
    private String team = "Squad Geral";

    // Id real da squad (Squad.id) — team acima continua só o nome de exibição.
    // Sem FK, mesmo padrão de sprintId: liga pelo id sem depender de join/tabela nova.
    @Column(length = 100)
    private String squadId;

    // Id da sprint do Jira (mesmo espaço de Squad.activeSprintId / WorkItem.sprintId).
    // String livre, sem FK — permite ligar o board à sprint sem tabela nova.
    @Column(length = 100)
    private String sprintId;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    // Carimbado a cada escrita real no board (voto, card, revelação...) — usado como
    // proxy de "tempo de uso" (updatedAt - createdAt) na aba executiva do /admin.
    // Nenhum job/sync toca essa tabela em background, então é sinal de atividade real.
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Column(length = 50)
    private String templateKey;

    @Builder.Default
    private Boolean syncStageEnabled = false;

    private String activeColumnKey;

    @Builder.Default
    private Boolean autoRevealOnTimerEnd = false;

    @Builder.Default
    private Boolean autoSortOnVoteEnd = false;

    // Quantos cards cada participante pode votar no total do board (dot-voting).
    // Null/0 = sem limite. Default 5 pra boards novos.
    @Builder.Default
    private Integer maxVotesPerParticipant = 5;

    // --- Check-in inicial (health-check configurável pelo facilitador) ---
    @Builder.Default
    private Boolean healthCheckEnabled = false;

    @Column(columnDefinition = "TEXT")
    private String healthCheckQuestion;

    // --- Timer Details ---
    @Builder.Default
    private String timerStatus = "stopped";

    @Builder.Default
    private Integer timerInitialDuration = 300;

    @Builder.Default
    private Integer timerRemainingOnPause = 300;

    private String timerEndTime;

    // --- Nested Columns ---
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "retro_board_columns", joinColumns = @JoinColumn(name = "board_id"))
    @Builder.Default
    private List<RetroColumnDef> columns = new ArrayList<>();
}
