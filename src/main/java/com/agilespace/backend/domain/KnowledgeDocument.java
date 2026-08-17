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
@Table(name = "knowledge_kb")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true)
    private String tdnId; // ID vindo da importação externa do TDN/Confluence

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    private String category;
    private String fullPath;
    private String moduleId;
    private String moduleName;
    private String folderId;
    private String folderName;

    @Column(length = 50)
    private String status; // indexed, published, deleted

    @Column(nullable = false)
    private String authorId;

    @Builder.Default
    private Long byteSize = 0L;

    @Builder.Default
    private Integer views = 0;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "knowledge_tags", joinColumns = @JoinColumn(name = "document_id"))
    @Column(name = "tag")
    @Builder.Default
    private Set<String> tags = new HashSet<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    private String updatedBy;
    private LocalDateTime deletedAt;
    private String deletedBy;
}
