package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "user_worklogs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserWorklog {

    @Id
    private String id; // Chave estruturada no formato: {userId}_{timestamp}

    @Column(nullable = false)
    private String userId;

    private String taskId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String title;

    @Builder.Default
    private Integer durationMinutes = 0;

    @Column(nullable = false)
    private String timestamp;

    @Builder.Default
    private Boolean isJira = false;

    @Column(name = "log_date", length = 10, nullable = false)
    private String date; // Formato YYYY-MM-DD - Mapeado para log_date no Postgres

    @Column(length = 50)
    private String jiraStatus; // pendente, lançado
}
