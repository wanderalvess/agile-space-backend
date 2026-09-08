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
public class JoltProjectVersionDto {
    private UUID id;
    private UUID projectId;
    private Integer versionNumber;
    private String commitMessage;
    private String specJson;
    private String flowNodes;
    private String flowEdges;
    private String inputJson;
    private String targetJson;
    private String createdBy;
    private String authorName;
    private LocalDateTime createdAt;
}
