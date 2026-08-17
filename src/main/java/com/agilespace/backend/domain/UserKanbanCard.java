package com.agilespace.backend.domain;

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
    private LocalDateTime dueDate;

    private String tag;

    @Column(name = "origin_link")
    private String originLink;

    @Column(name = "exported_at")
    private LocalDateTime exportedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
