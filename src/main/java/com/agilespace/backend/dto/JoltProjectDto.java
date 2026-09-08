package com.agilespace.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JoltProjectDto {
    private UUID id;
    private String name;
    private String description;
    private String category;
    private String entityName;
    private String mappingMode;
    private Boolean isPublic;

    private String authorId;
    private String authorName;
    private String authorEmail;
    private String squadId;

    private String inputJson;
    private String targetJson;
    private String specJson;
    private String flowNodes;
    private String flowEdges;

    private int versionCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
