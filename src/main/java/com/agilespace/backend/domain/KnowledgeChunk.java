package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/** Trecho de um documento da KB (uma seção ou parte dela), unidade da busca híbrida. */
@Entity
@Table(name = "knowledge_chunk")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(nullable = false)
    private Integer ordinal;

    @Column(name = "heading_path", length = 1000)
    private String headingPath;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(name = "text_hash", nullable = false, length = 64)
    private String textHash;

    @Convert(converter = EmbeddingConverter.class)
    @Column(columnDefinition = "TEXT")
    private float[] embedding;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
        }
    }
}
