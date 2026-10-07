package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "knowledge_token_usage")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeTokenUsage {

    @Id
    @Column(name = "user_id")
    private String userId;

    @Column(name = "user_name")
    private String userName; // Desnormalizado para a view de top 10 no admin, evitando join

    @Builder.Default
    @Column(name = "total_tokens", nullable = false)
    private long totalTokens = 0L;

    private LocalDateTime updatedAt;
}
