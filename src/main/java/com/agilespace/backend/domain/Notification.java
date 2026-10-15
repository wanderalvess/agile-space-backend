package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Notificação de um usuário (V71). Datas em UTC (LocalDateTime da JVM, ver JacksonUtcConfig). */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    public static final String INVITE = "INVITE";
    public static final String REMINDER = "REMINDER";
    public static final String ACTION_ASSIGNED = "ACTION_ASSIGNED";
    public static final String MESSAGE = "MESSAGE";
    public static final String ANNOUNCEMENT = "ANNOUNCEMENT";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false, length = 30)
    private String kind;

    @Column(length = 200)
    private String title;

    @Column(nullable = false, length = 500)
    private String text;

    @Column(length = 500)
    private String link;

    @Column(name = "actor_id")
    private String actorId;

    @Column(name = "squad_id", length = 100)
    private String squadId;

    @Column(name = "dedupe_key", length = 200)
    private String dedupeKey;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;
}
