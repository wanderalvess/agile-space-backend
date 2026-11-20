package com.agilespace.backend.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "prompt_comments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromptComment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prompt_id", nullable = false)
    @JsonIgnore
    private Prompt prompt;

    @NotBlank(message = "O comentário não pode ser vazio")
    @Size(max = 4000, message = "O comentário aceita até 4.000 caracteres")
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(nullable = false)
    private String authorId;
    
    private String authorName;
    private String authorRole;
    private String authorSquad;
    private String authorAvatar;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
