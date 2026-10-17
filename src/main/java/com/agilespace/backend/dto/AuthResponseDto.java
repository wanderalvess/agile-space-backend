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
    private String jobTitle;
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

    /**
     * Aditivo e opcional: só vem (true) quando app.membership.mode=new e a conta não tem nenhuma linha em
     * project_member_roles, só rastros do modelo antigo (users.squad_id / roster). Ausente em qualquer outro caso.
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Boolean legacyOnly;
}
