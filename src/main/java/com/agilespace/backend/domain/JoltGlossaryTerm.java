package com.agilespace.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Par de nomes de campo aprendido pelo Gerador de spec: {@code termFrom} é o campo da saída e {@code termTo} o campo da
 * entrada que a equipe ligou a ele (normalizados: sem acento, minúsculas, só letras e dígitos).
 */
@Entity
@Table(name = "jolt_glossary_terms")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JoltGlossaryTerm {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "term_from", nullable = false, length = 120)
    private String termFrom;

    @Column(name = "term_to", nullable = false, length = 120)
    private String termTo;

    @Column(nullable = false)
    @Builder.Default
    private int uses = 1;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
