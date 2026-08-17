package com.agilespace.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppReleaseDTO {
    private String id;
    private String tag;
    private String title;
    private String description;
    private List<String> changes;
    private String type;
    private String iconName;
    private String iconClass;
    private String displayDate;
    private Boolean isPublished;
    private String createdBy;
}
