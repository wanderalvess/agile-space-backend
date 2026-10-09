package com.agilespace.backend.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "prompt_hub")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Prompt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotBlank(message = "O título é obrigatório")
    @Size(max = 255, message = "O título aceita até 255 caracteres")
    @Column(nullable = false)
    private String title;

    @Size(max = 10000, message = "A descrição aceita até 10.000 caracteres")
    @Column(columnDefinition = "TEXT")
    private String description;

    @Size(max = 200000, message = "O conteúdo aceita até 200.000 caracteres")
    @Column(columnDefinition = "TEXT")
    private String content;

    @Size(max = 50)
    @Column(length = 50)
    private String type; // prompt, skill, agent

    @Size(max = 50)
    @Column(length = 50)
    private String visibility; // public, private

    @Size(max = 50)
    @Column(length = 50)
    private String status;

    @Size(max = 50)
    @Column(length = 50)
    private String impact;

    @Size(max = 255, message = "O objetivo de negócio aceita até 255 caracteres")
    private String businessGoal;
    @Size(max = 255, message = "O público-alvo aceita até 255 caracteres")
    private String targetAudience;
    @Size(max = 255, message = "O link da ferramenta aceita até 255 caracteres")
    private String gemLink;
    @Size(max = 255, message = "O link de documentação aceita até 255 caracteres")
    private String architectureLink;

    // Denormalized Author info
    @Column(nullable = false)
    private String authorId;
    @Size(max = 255)
    private String authorName;
    @Size(max = 255)
    private String authorRole;
    @Size(max = 255)
    private String authorSquad;
    private String authorAvatar;

    @Builder.Default
    private Integer useCount = 0;
    
    @Builder.Default
    private Integer forkCount = 0;

    @ElementCollection
    @CollectionTable(name = "prompt_tags", joinColumns = @JoinColumn(name = "prompt_id"))
    @Column(name = "tag")
    @Builder.Default
    @Size(max = 30, message = "Use no máximo 30 tags")
    private Set<@Size(max = 100, message = "Cada tag aceita até 100 caracteres") String> tags = new HashSet<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
