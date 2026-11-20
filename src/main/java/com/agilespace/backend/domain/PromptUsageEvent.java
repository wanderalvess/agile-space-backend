package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** Um uso ("use") ou clone ("clone") de um item; alimenta o gráfico de uso por semana. */
@Entity
@Table(name = "prompt_usage_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromptUsageEvent {

    public static final String USE = "use";
    public static final String CLONE = "clone";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "prompt_id", nullable = false)
    private UUID promptId;

    @Column(name = "user_id")
    private String userId;

    @Column(nullable = false, length = 10)
    private String kind;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
