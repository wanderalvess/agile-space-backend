package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_sticky_notes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserStickyNote {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(nullable = false)
    private String color;

    @Column(name = "is_pinned", nullable = false)
    private boolean isPinned;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
