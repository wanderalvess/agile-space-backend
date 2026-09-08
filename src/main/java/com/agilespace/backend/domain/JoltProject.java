package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "jolt_projects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JoltProject {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 50)
    @Builder.Default
    private String category = "Geral";

    @Column(length = 100)
    private String entityName;

    @Column(length = 20)
    @Builder.Default
    private String mappingMode = "smarthub";

    @Column(nullable = false)
    @Builder.Default
    private Boolean isPublic = false;

    // Autor e Squad
    @Column(nullable = false)
    private String authorId;
    private String authorName;
    private String authorEmail;
    private String squadId;

    // Payloads e nós do ReactFlow
    @Column(columnDefinition = "TEXT")
    private String inputJson;

    @Column(columnDefinition = "TEXT")
    private String targetJson;

    @Column(columnDefinition = "TEXT")
    private String specJson;

    @Column(columnDefinition = "TEXT")
    private String flowNodes;

    @Column(columnDefinition = "TEXT")
    private String flowEdges;

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("versionNumber DESC")
    @Builder.Default
    private List<JoltProjectVersion> versions = new ArrayList<>();

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
