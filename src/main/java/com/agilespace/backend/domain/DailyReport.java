package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "daily_reports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyReport {

    @Id
    private String id; // Chave estruturada no formato: {userId}_{report_date}

    @Column(nullable = false)
    private String userId;

    @Column(columnDefinition = "TEXT")
    private String yesterday;

    @Column(columnDefinition = "TEXT")
    private String today;

    @Column(columnDefinition = "TEXT")
    private String blockers;

    @Column(name = "report_date", length = 10, nullable = false)
    private String date; // Formato YYYY-MM-DD - Mapeado para report_date no Postgres

    @Column(nullable = false)
    private String timestamp;
}
