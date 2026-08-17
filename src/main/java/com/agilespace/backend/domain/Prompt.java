package com.agilespace.backend.domain;

import jakarta.persistence.*;
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

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(length = 50)
    private String type; // prompt, skill, agent

    @Column(length = 50)
    private String visibility; // public, private

    @Column(length = 50)
    private String status;

    @Column(length = 50)
    private String impact;

    private String businessGoal;
    private String targetAudience;
    private String gemLink;
    private String architectureLink;

    // Denormalized Author info
    @Column(nullable = false)
    private String authorId;
    private String authorName;
    private String authorRole;
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
    private Set<String> tags = new HashSet<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
