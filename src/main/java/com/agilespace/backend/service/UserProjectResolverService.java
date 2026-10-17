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
     * Qual linha representa a pessoa numa squad quando ela tem várias. Ordem: (1) liderança vence contribuidor;
     * (2) entre lideranças, o papel de maior alcance (tribo/coach/people lead, depois PO/AM/SM, depois o resto);
     * (3) desempate estável por nome do papel e id, para a resposta não depender da ordem do banco.
     */
    static final Comparator<ProjectMemberRole> PRIMARY_ROLE_ORDER = Comparator
            .comparing((ProjectMemberRole r) -> !r.isLeadership())
            .thenComparingInt(r -> leadershipRank(r.getRoleKey()))
            .thenComparing(r -> r.getRoleName() == null ? "" : r.getRoleName(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(r -> r.getId() == null ? "" : r.getId());

    private static final List<String> LEADERSHIP_RANK = List.of(
            "TRIBE_LEAD", "AGILE_COACH", "PEOPLE_LEAD", "PRODUCT_OWNER", "AGILE_MASTER", "SCRUM_MASTER");

    private static int leadershipRank(String roleKey) {
        if (roleKey == null) return LEADERSHIP_RANK.size();
        int i = LEADERSHIP_RANK.indexOf(roleKey.toUpperCase(Locale.ROOT));
        return i < 0 ? LEADERSHIP_RANK.size() : i;
    }

    /**
     * Linha que expande o acesso para a tribo/segmento inteiros: liderança de verdade (isLeadership=true) com papel
     * TRIBE_LEAD, AGILE_COACH ou PEOPLE_LEAD.
     */
    static boolean isTransversalLeadershipRow(ProjectMemberRole role) {
        return role.isLeadership() && ("TRIBE_LEAD".equalsIgnoreCase(role.getRoleKey())
                || "AGILE_COACH".equalsIgnoreCase(role.getRoleKey())
                || "PEOPLE_LEAD".equalsIgnoreCase(role.getRoleKey()));
    }

    static int rankOfKey(String roleKey) {
        return leadershipRank(roleKey);
    }

    /** True se o projeto já tem alguma pessoa marcada como liderança no cadastro (project_member_roles). */
    @Transactional(readOnly = true)
    public boolean hasRegisteredLeadership(String projectId) {
        if (projectId == null || projectId.isBlank()) return false;
        return projectMemberRoleRepository.findByProjectId(projectId.trim()).stream().anyMatch(ProjectMemberRole::isLeadership);
    }

    /**
     * Linhas de project_member_roles que pertencem à conta: por e-mail, por conta do Jira e por user_id (o vínculo
     * explícito do "sou eu"), sem repetir a mesma linha. Usada pelo resolvedor legado e pelo {@link MembershipService}.
     */
    @Transactional(readOnly = true)
    public List<ProjectMemberRole> loadDirectRows(User user) {
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
        // Vínculo explícito conta -> linha do roster (OnboardingService.claim, "sou eu").
        // Sem isso, quem tem e-mail do Jira diferente do login continuaria sem acesso
        // mesmo depois de se reconhecer na lista — que é justamente o caso que o claim
        // existe pra resolver.
        if (user.getId() != null && !user.getId().isBlank()) {
            List<ProjectMemberRole> byUserId = projectMemberRoleRepository.findByUserId(user.getId());
            for (ProjectMemberRole r : byUserId) {
                if (directRoles.stream().noneMatch(existing -> existing.getId().equals(r.getId()))) {
                    directRoles.add(r);
                }
            }
        }
        return directRoles;
    }

    /**
     * Resolve todos os projetos, tribos e cargos associados ao usuário (cálculo LEGADO; o modelo novo é o
     * {@link MembershipService}, que reaproveita {@link #loadDirectRows} e {@link #PRIMARY_ROLE_ORDER}).
     */
    @Transactional(readOnly = true)
    public UserProjectAccessDto resolveUserAccess(User user) {
        List<ProjectMemberRole> directRoles = loadDirectRows(user);

        Map<String, UserProjectAccessDto.ProjectAccessItem> projectMap = new LinkedHashMap<>();
        Set<String> leaderTribes = new HashSet<>();
        Set<String> leaderSegments = new HashSet<>();
        boolean isTransversalLeader = false;

        // 1. Projetos com atribuição direta
        // A mesma pessoa pode ter mais de uma linha na mesma squad (PO e Developer; linha do Jira + linha cadastrada à mão).
        // O accessibleProjects tem UM item por squad, então escolhemos qual linha o representa: ver PRECEDÊNCIA abaixo.
        // A ordem dos projetos continua a de primeira aparição (afeta o projeto primário quando não há default); só a
        // linha que representa cada squad é escolhida por PRIMARY_ROLE_ORDER (determinístico, não depende do banco).
        Map<String, ProjectMemberRole> representative = new HashMap<>();
        for (ProjectMemberRole role : directRoles) {
            representative.merge(role.getProjectId(), role, (a, b) -> PRIMARY_ROLE_ORDER.compare(b, a) < 0 ? b : a);
        }
        for (ProjectMemberRole role : directRoles) {
            Optional<ProjectConfig> optProject = projectConfigRepository.findById(role.getProjectId());
            String projectName = optProject.map(ProjectConfig::getName).orElse(role.getProjectId());
            String segmentName = optProject.map(ProjectConfig::getSegmentName).orElse("");
            String tribeName = optProject.map(ProjectConfig::getTribeName).orElse("");

            // PRECEDÊNCIA: liderança vence contribuidor na mesma squad (antes: a última linha ganhava, e quem era PO e
            // Developer podia perder isLeadership). A detecção de liderança transversal abaixo olha TODAS as linhas,
            // independentemente de qual representa a squad.
            if (representative.get(role.getProjectId()) == role) {
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
            }

            // Identifica se é liderança de tribo / segmento (ex: Tribe Lead, Agile Coach, People Lead)
            // Só vale para linha de liderança de verdade (isLeadership=true: sync do Jira ou gestão de time). A roleKey
            // sozinha vem do NOME do papel, que um convite/backfill grava com isLeadership=false: sem esta exigência,
            // convidar alguém como "Tribe Lead" dava acesso a todos os projetos da tribo.
            if (isTransversalLeadershipRow(role)) {
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
