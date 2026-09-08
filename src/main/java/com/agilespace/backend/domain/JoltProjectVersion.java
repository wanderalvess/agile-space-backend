package com.agilespace.backend.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "jolt_project_versions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JoltProjectVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    @JsonIgnore
    private JoltProject project;

    @Column(nullable = false)
    private Integer versionNumber;

    @Column(columnDefinition = "TEXT")
    private String commitMessage;

    @Column(columnDefinition = "TEXT")
    private String specJson;

    @Column(columnDefinition = "TEXT")
    private String flowNodes;

    @Column(columnDefinition = "TEXT")
    private String flowEdges;

    @Column(columnDefinition = "TEXT")
    private String inputJson;

    @Column(columnDefinition = "TEXT")
    private String targetJson;

    private String createdBy;
    private String authorName;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
