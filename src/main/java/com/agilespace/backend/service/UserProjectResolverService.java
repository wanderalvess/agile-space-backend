package com.agilespace.backend.service;

import com.agilespace.backend.domain.ProjectConfig;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.UserProjectAccessDto;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserProjectResolverService {

    private final ProjectMemberRoleRepository projectMemberRoleRepository;
    private final ProjectConfigRepository projectConfigRepository;

    /**
     * Resolve todos os projetos, tribos e cargos associados ao usuário.
     */
    @Transactional(readOnly = true)
    public UserProjectAccessDto resolveUserAccess(User user) {
        String email = user.getEmail();
        String jiraAccountId = user.getJiraAccountId();

        List<ProjectMemberRole> directRoles = new ArrayList<>();
        if (email != null && !email.isBlank()) {
            directRoles.addAll(projectMemberRoleRepository.findByEmailIgnoreCase(email.trim()));
        }
        if (jiraAccountId != null && !jiraAccountId.isBlank()) {
            List<ProjectMemberRole> byJira = projectMemberRoleRepository.findByJiraAccountId(jiraAccountId.trim());
            for (ProjectMemberRole r : byJira) {
                if (directRoles.stream().noneMatch(existing -> existing.getId().equals(r.getId()))) {
                    directRoles.add(r);
                }
            }
        }

        Map<String, UserProjectAccessDto.ProjectAccessItem> projectMap = new LinkedHashMap<>();
        Set<String> leaderTribes = new HashSet<>();
        Set<String> leaderSegments = new HashSet<>();
        boolean isTransversalLeader = false;

        // 1. Projetos com atribuição direta
        for (ProjectMemberRole role : directRoles) {
            Optional<ProjectConfig> optProject = projectConfigRepository.findById(role.getProjectId());
            String projectName = optProject.map(ProjectConfig::getName).orElse(role.getProjectId());
            String segmentName = optProject.map(ProjectConfig::getSegmentName).orElse("");
            String tribeName = optProject.map(ProjectConfig::getTribeName).orElse("");

            projectMap.put(role.getProjectId(), UserProjectAccessDto.ProjectAccessItem.builder()
                    .projectId(role.getProjectId())
                    .projectName(projectName)
                    .segmentName(segmentName)
                    .tribeName(tribeName)
                    .roleName(role.getRoleName())
                    .roleKey(role.getRoleKey())
                    .isDirectAssignment(true)
                    .isLeadership(role.isLeadership())
                    .build());

            // Identifica se é liderança de tribo / segmento (ex: Tribe Lead, Agile Coach, People Lead)
            if ("TRIBE_LEAD".equalsIgnoreCase(role.getRoleKey()) || "AGILE_COACH".equalsIgnoreCase(role.getRoleKey()) || "PEOPLE_LEAD".equalsIgnoreCase(role.getRoleKey())) {
                isTransversalLeader = true;
                if (!tribeName.isBlank()) leaderTribes.add(tribeName.toLowerCase());
                if (!segmentName.isBlank()) leaderSegments.add(segmentName.toLowerCase());
            }
        }

        // 2. Se for liderança transversal, expande o acesso para todos os projetos da sua Tribo/Segmento
        if (isTransversalLeader) {
            List<ProjectConfig> allProjects = projectConfigRepository.findAll();
            for (ProjectConfig p : allProjects) {
                boolean matchTribe = p.getTribeName() != null && leaderTribes.contains(p.getTribeName().toLowerCase());
                boolean matchSeg = p.getSegmentName() != null && leaderSegments.contains(p.getSegmentName().toLowerCase());

                if ((matchTribe || matchSeg) && !projectMap.containsKey(p.getId())) {
                    projectMap.put(p.getId(), UserProjectAccessDto.ProjectAccessItem.builder()
                            .projectId(p.getId())
                            .projectName(p.getName())
                            .segmentName(p.getSegmentName())
                            .tribeName(p.getTribeName())
                            .roleName("Liderança Transversal da Tribo")
                            .roleKey("TRIBE_LEADER_ACCESS")
                            .isDirectAssignment(false)
                            .isLeadership(true)
                            .build());
                }
            }
        }

        // 3. Define projeto primário/ativo
        String primaryProjectId = null;
        if (user.getDefaultProjectId() != null && projectMap.containsKey(user.getDefaultProjectId())) {
            primaryProjectId = user.getDefaultProjectId();
        } else if (!projectMap.isEmpty()) {
            primaryProjectId = projectMap.keySet().iterator().next();
        }

        List<UserProjectAccessDto.ProjectAccessItem> projectList = new ArrayList<>(projectMap.values());

        return UserProjectAccessDto.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .primaryProjectId(primaryProjectId)
                .isTransversalLeader(isTransversalLeader)
                .projects(projectList)
                .build();
    }
}
