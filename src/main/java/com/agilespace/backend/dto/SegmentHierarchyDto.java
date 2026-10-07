package com.agilespace.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SegmentHierarchyDto {
    private String segmentName;
    private List<TribeGroupDto> tribes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TribeGroupDto {
        private String tribeName;
        private List<ProjectSummaryDto> projects;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProjectSummaryDto {
        private String id;
        private String name;
        private String status;
        private Integer devTeamSize;
        private String locality;
        private int totalLeaders;
    }
}
