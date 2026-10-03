package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_snippets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSnippet {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    private String language;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
