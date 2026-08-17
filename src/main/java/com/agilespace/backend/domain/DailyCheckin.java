package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "daily_checkins")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyCheckin {

    @Id
    private String id; // Chave estruturada no formato: {userId}_{checkin_date}

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String userName;

    private String userRole;
    private String userAvatar;

    @Column(nullable = false)
    private String squadId;

    @Column(name = "checkin_date", length = 10, nullable = false)
    private String date; // Formato YYYY-MM-DD - Mapeado para checkin_date no Postgres

    @Column(columnDefinition = "TEXT")
    private String yesterday;

    @Column(columnDefinition = "TEXT")
    private String today;

    @Column(columnDefinition = "TEXT")
    private String blockers;

    private String blockerDuration;

    @Builder.Default
    private Boolean hasBlocker = false;

    @Builder.Default
    private Boolean isPrivate = false;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
