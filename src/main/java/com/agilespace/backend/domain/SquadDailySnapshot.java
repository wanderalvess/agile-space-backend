package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "squad_daily_snapshots")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SquadDailySnapshot {

    @Id
    @Column(name = "db_id")
    private String dbId; // PK = {squadId}_{date}

    @Column(name = "squad_id", nullable = false)
    private String squadId;

    @Column(name = "snapshot_date", nullable = false) // Renomeado de 'date' para evitar colisão com funções SQL
    private String snapshotDate;

    @Column(name = "done_issues")
    private Integer doneIssues;

    @Column(name = "in_progress_issues")
    private Integer inProgressIssues;

    @Column(name = "total_issues")
    private Integer totalIssues;

    @Column(name = "bug_issues")
    private Integer bugIssues;

    @Column(name = "stale_issues")
    private Integer staleIssues;

    @Column(name = "logged_sec")
    private Long loggedSec;

    @Column(name = "synced_at")
    private String syncedAt;
}
