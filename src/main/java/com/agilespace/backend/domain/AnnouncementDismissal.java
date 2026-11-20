package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;

/** Usuário dispensou um aviso: some de /api/announcements/active, continua no histórico. */
@Entity
@Table(name = "announcement_dismissals")
@IdClass(AnnouncementDismissal.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnnouncementDismissal {

    @Id
    @Column(name = "announcement_id", nullable = false)
    private String announcementId;

    @Id
    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "dismissed_at", nullable = false)
    private LocalDateTime dismissedAt;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private String announcementId;
        private String userId;
    }
}
