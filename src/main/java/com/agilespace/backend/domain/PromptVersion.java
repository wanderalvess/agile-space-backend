package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** Foto do item a cada criação/edição que mude conteúdo, título, descrição, tipo, visibilidade, status ou tags. */
@Entity
@Table(name = "prompt_versions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromptVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "prompt_id", nullable = false)
    private UUID promptId;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(length = 50)
    private String type;

    @Column(length = 50)
    private String visibility;

    @Column(length = 50)
    private String status;

    /** Array JSON de strings (as tags ficam numa tabela à parte no item; aqui é só a foto). */
    @Column(columnDefinition = "TEXT")
    private String tags;

    @Column(name = "changed_by")
    private String changedBy;

    @Column(name = "changed_by_name")
    private String changedByName;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;
}
