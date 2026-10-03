package com.agilespace.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectMemberRoleDto {
    private String id;
    private String projectId;
    private String roleName; // Ex: "Agile Master", "Product Owner"
    private String roleKey;  // Ex: "AGILE_MASTER", "PRODUCT_OWNER"
    private String jiraAccountId;
    private String displayName;
    private String email;
    private String avatarUrl;
    private String userId;
    private boolean leadership;
}
