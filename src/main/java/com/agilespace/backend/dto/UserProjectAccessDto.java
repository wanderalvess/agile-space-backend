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
public class UserProjectAccessDto {
    private String userId;
    private String email;
    private String name;
    private String primaryProjectId;
    private boolean isTransversalLeader;
    private List<ProjectAccessItem> projects;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProjectAccessItem {
        private String projectId;
        private String projectName;
        private String segmentName;
        private String tribeName;
        private String roleName;
        private String roleKey;
        private boolean isDirectAssignment;
        private boolean isLeadership;
    }
}
