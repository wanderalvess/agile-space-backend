package com.agilespace.backend.domain;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_kanban_cards")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserKanbanCard {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "card_status", nullable = false)
    private String status; // todo, doing, done

    @Column(name = "card_priority", nullable = false)
    private String priority; // baixa, media, alta, critica

    @Column(name = "due_date")
    // Horário digitado por pessoa (sem fuso): fica FORA da conversão para UTC com Z (ver JacksonUtcConfig).
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime dueDate;

    private String tag;

    @Column(name = "origin_link", length = 2048)
    private String originLink;

    @Column(name = "exported_at")
    private LocalDateTime exportedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
