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
public class AuthResponseDto {

    private String token;
    private String tokenType; // "Bearer"
    private Long expiresIn;

    // Dados do Usuário
    private String id;
    private String email;
    private String name;
    private String role;
    private String authProvider;
    private String jiraAccountId;
    private String avatarSeed;

    // Governança & Projetos Resolvidos
    private String activeProjectId;
    private String activeProjectName;
    private String activeProjectRole;
    private String segmentName;
    private String tribeName;
    private boolean isTransversalLeader;

    private List<UserProjectAccessDto.ProjectAccessItem> accessibleProjects;
}
