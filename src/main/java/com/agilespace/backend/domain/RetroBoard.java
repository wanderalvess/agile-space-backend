package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

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

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @Column(length = 50)
    private String templateKey;

    @Builder.Default
    private Boolean syncStageEnabled = false;

    private String activeColumnKey;

    @Builder.Default
    private Boolean autoRevealOnTimerEnd = false;

    @Builder.Default
    private Boolean autoSortOnVoteEnd = false;

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
