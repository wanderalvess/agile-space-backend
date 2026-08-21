package com.agilespace.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UnifiedSessionDto {
    private String id;
    private String type;
    private String title;
    private String creatorId;
    private String createdAt;
    private Integer participantCount;
}
