package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/** Foto de um documento da Base de Conhecimento num momento (título, conteúdo e tags). */
@Entity
@Table(name = "knowledge_document_versions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeDocumentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    // Lista de tags serializada em JSON.
    @Column(columnDefinition = "TEXT")
    private String tags;

    private String category;

    @Column(name = "full_path")
    private String fullPath;

    @Column(name = "changed_by")
    private String changedBy;

    // UTC (a JVM roda com TZ=UTC).
    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;
}
