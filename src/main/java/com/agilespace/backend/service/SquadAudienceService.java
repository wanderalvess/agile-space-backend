package com.agilespace.backend.service;

import com.agilespace.backend.domain.ProjectConfig;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.UserProjectAccessDto;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.SquadMemberRepository;
import com.agilespace.backend.repository.SquadRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.SquadLeadership;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * "Quem é de qual squad", nas duas direções, a partir das MESMAS fontes que o {@link SquadAccessService} usa para
 * autorizar (squad da sessão em users, project_member_roles via {@link UserProjectResolverService} e o roster
 * squad_members por vaga reivindicada / e-mail / conta do Jira — nome nunca conta). Não é um modelo de acesso novo:
 * serve para listar (squads acessíveis da home, público de avisos, destinatários de notificação, contatos de DM).
 * <p>
 * Ids de squad são comparados sem caixa (as colunas "team"/"squadId" das cerimônias já são comparadas assim).
 */
@Service
@RequiredArgsConstructor
public class SquadAudienceService {

    private final UserRepository userRepository;
    private final ProjectMemberRoleRepository projectMemberRoleRepository;
    private final SquadMemberRepository squadMemberRepository;
    private final UserProjectResolverService userProjectResolverService;
    private final SquadRepository squadRepository;
    private final ProjectConfigRepository projectConfigRepository;

    /**
     * Visão do usuário autenticado.
     *
     * @param currentSquadId squad da sessão (defaultProjectId → squadId → projeto primário), pode ser null
     * @param squadIds       squads acessíveis, a atual primeiro (sem duplicatas, sem caixa)
     * @param roleKeys       UserRole + roleKeys de project_member_roles, em MAIÚSCULAS
     * @param leadership     ADMIN/LEAD, cargo de liderança, papel de liderança em algum projeto ou liderança transversal
     */
    public record UserContext(User user, String currentSquadId, List<String> squadIds, Set<String> roleKeys, boolean leadership) {

        public boolean hasSquad(String squadId) {
            return squadId != null && squadIds.stream().anyMatch(s -> s.equalsIgnoreCase(squadId));
        }

        /** Agile Master / liderança com mais de uma squad acessível vê a visão consolidada da home. */
        public boolean multiSquad() {
            return leadership && squadIds.size() > 1;
        }
    }

    @Transactional(readOnly = true)
    public Optional<UserContext> contextFor(String userId, String tokenRole) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return Optional.empty();
        }
        UserProjectAccessDto access = userProjectResolverService.resolveUserAccess(user);
        List<UserProjectAccessDto.ProjectAccessItem> projects =
                access != null && access.getProjects() != null ? access.getProjects() : List.of();

        String current = firstNonBlank(user.getDefaultProjectId(), user.getSquadId(),
                access != null ? access.getPrimaryProjectId() : null);

        Map<String, String> squads = new LinkedHashMap<>();
        addSquad(squads, current);
        addSquad(squads, user.getSquadId());
        addSquad(squads, user.getDefaultProjectId());
        for (UserProjectAccessDto.ProjectAccessItem p : projects) {
            addSquad(squads, p.getProjectId());
        }
        for (SquadMember m : squadMemberRepository.findByClaimedByUid(user.getId())) {
            addSquad(squads, m.getSquadId());
        }
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            String email = user.getEmail().trim();
            for (SquadMember m : squadMemberRepository.findByUserIdentifier(email)) {
                if (m.getEmail() != null && m.getEmail().trim().equalsIgnoreCase(email)) {
                    addSquad(squads, m.getSquadId());
                }
            }
        }

        Set<String> roleKeys = new LinkedHashSet<>();
        String role = isPrivileged(tokenRole) ? tokenRole : user.getRole();
        if (role != null) roleKeys.add(role.toUpperCase(Locale.ROOT));
        if (user.getRole() != null) roleKeys.add(user.getRole().toUpperCase(Locale.ROOT));
        for (UserProjectAccessDto.ProjectAccessItem p : projects) {
            if (p.getRoleKey() != null && !p.getRoleKey().isBlank()) roleKeys.add(p.getRoleKey().toUpperCase(Locale.ROOT));
        }

        boolean leadership = isPrivileged(tokenRole) || isPrivileged(user.getRole())
                || SquadLeadership.isLeadershipJobTitle(user.getJobTitle())
                || (access != null && access.isTransversalLeader())
                || projects.stream().anyMatch(UserProjectAccessDto.ProjectAccessItem::isLeadership);

        return Optional.of(new UserContext(user, current, List.copyOf(squads.values()), roleKeys, leadership));
    }

    /**
     * Usuários ativos de cada squad (chave = id da squad em MAIÚSCULAS). Número fixo de consultas, qualquer que seja
     * a quantidade de squads.
     */
    @Transactional(readOnly = true)
    public Map<String, Set<String>> memberUserIds(Collection<String> squadIds) {
        Set<String> ids = upper(squadIds);
        Map<String, Set<String>> result = new LinkedHashMap<>();
        ids.forEach(id -> result.put(id, new LinkedHashSet<>()));
        if (ids.isEmpty()) {
            return result;
        }
        for (User u : userRepository.findActiveBySquadIds(ids)) {
            addIfIn(result, u.getSquadId(), u.getId());
            addIfIn(result, u.getDefaultProjectId(), u.getId());
        }

        // Linhas de papel (project_member_roles) e roster (squad_members) apontam para a conta por id, e-mail ou Jira.
        List<Link> links = new ArrayList<>();
        for (ProjectMemberRole r : projectMemberRoleRepository.findByProjectIdsUpper(ids)) {
            links.add(new Link(r.getProjectId(), r.getUserId(), r.getEmail(), r.getJiraAccountId()));
        }
        for (SquadMember m : squadMemberRepository.findBySquadIdsUpper(ids)) {
            links.add(new Link(m.getSquadId(), m.getClaimedByUid(), m.getEmail(), m.getJiraAccountId()));
        }
        Resolved resolved = resolve(links);
        for (Link l : links) {
            for (String userId : resolved.userIdsFor(l)) {
                addIfIn(result, l.squadId(), userId);
            }
        }
        return result;
    }

    /** União de {@link #memberUserIds} (útil para destinatários). */
    @Transactional(readOnly = true)
    public Set<String> memberUserIdsOf(Collection<String> squadIds) {
        Set<String> all = new LinkedHashSet<>();
        memberUserIds(squadIds).values().forEach(all::addAll);
        return all;
    }

    /** Usuários ativos com algum dos papéis: UserRole (ADMIN/LEAD/MEMBER) ou roleKey de project_member_roles. */
    @Transactional(readOnly = true)
    public Set<String> userIdsWithRoles(Collection<String> roles) {
        Set<String> keys = upper(roles);
        Set<String> result = new LinkedHashSet<>();
        if (keys.isEmpty()) {
            return result;
        }
        result.addAll(userRepository.findActiveIdsByRoles(keys));
        List<Link> links = new ArrayList<>();
        for (ProjectMemberRole r : projectMemberRoleRepository.findByRoleKeysUpper(keys)) {
            links.add(new Link(r.getProjectId(), r.getUserId(), r.getEmail(), r.getJiraAccountId()));
        }
        Resolved resolved = resolve(links);
        for (Link l : links) {
            result.addAll(resolved.userIdsFor(l));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Set<String> allActiveUserIds() {
        return new LinkedHashSet<>(userRepository.findActiveIds());
    }

    /** Nome de exibição de cada squad (squads.name, senão projects.name); chave = id como informado. */
    @Transactional(readOnly = true)
    public Map<String, String> squadNames(Collection<String> squadIds) {
        Map<String, String> names = new HashMap<>();
        if (squadIds == null || squadIds.isEmpty()) {
            return names;
        }
        Set<String> wanted = new LinkedHashSet<>(squadIds);
        wanted.removeIf(Objects::isNull);
        for (ProjectConfig p : projectConfigRepository.findAllById(wanted)) {
            if (p.getName() != null && !p.getName().isBlank()) names.put(p.getId(), p.getName());
        }
        for (Squad s : squadRepository.findAllById(wanted)) {
            if (s.getName() != null && !s.getName().isBlank()) names.put(s.getId(), s.getName());
        }
        return names;
    }

    // --- internos ---

    private record Link(String squadId, String userId, String email, String jiraAccountId) { }

    private record Resolved(Set<String> activeIds, Map<String, String> byEmail, Map<String, String> byJira) {
        List<String> userIdsFor(Link l) {
            List<String> out = new ArrayList<>(2);
            if (l.userId() != null && activeIds.contains(l.userId())) out.add(l.userId());
            if (l.email() != null) {
                String id = byEmail.get(l.email().trim().toLowerCase(Locale.ROOT));
                if (id != null) out.add(id);
            }
            if (l.jiraAccountId() != null) {
                String id = byJira.get(l.jiraAccountId().trim());
                if (id != null) out.add(id);
            }
            return out;
        }
    }

    private Resolved resolve(List<Link> links) {
        Set<String> userIds = new HashSet<>();
        Set<String> emails = new HashSet<>();
        Set<String> jiraIds = new HashSet<>();
        for (Link l : links) {
            if (l.userId() != null && !l.userId().isBlank()) userIds.add(l.userId());
            if (l.email() != null && !l.email().isBlank()) emails.add(l.email().trim().toLowerCase(Locale.ROOT));
            if (l.jiraAccountId() != null && !l.jiraAccountId().isBlank()) jiraIds.add(l.jiraAccountId().trim());
        }
        Set<String> active = new HashSet<>();
        if (!userIds.isEmpty()) {
            userRepository.findActiveByIds(userIds).forEach(u -> active.add(u.getId()));
        }
        Map<String, String> byEmail = new HashMap<>();
        if (!emails.isEmpty()) {
            userRepository.findActiveByEmails(emails).forEach(u -> byEmail.put(u.getEmail().trim().toLowerCase(Locale.ROOT), u.getId()));
        }
        Map<String, String> byJira = new HashMap<>();
        if (!jiraIds.isEmpty()) {
            userRepository.findActiveByJiraAccountIds(jiraIds).forEach(u -> byJira.put(u.getJiraAccountId().trim(), u.getId()));
        }
        return new Resolved(active, byEmail, byJira);
    }

    private static void addIfIn(Map<String, Set<String>> result, String squadId, String userId) {
        if (squadId == null || userId == null) return;
        Set<String> set = result.get(squadId.trim().toUpperCase(Locale.ROOT));
        if (set != null) set.add(userId);
    }

    private static void addSquad(Map<String, String> squads, String squadId) {
        if (squadId == null || squadId.isBlank()) return;
        squads.putIfAbsent(squadId.trim().toUpperCase(Locale.ROOT), squadId.trim());
    }

    static Set<String> upper(Collection<String> values) {
        Set<String> out = new LinkedHashSet<>();
        if (values == null) return out;
        for (String v : values) {
            if (v != null && !v.isBlank()) out.add(v.trim().toUpperCase(Locale.ROOT));
        }
        return out;
    }

    private static boolean isPrivileged(String role) {
        return "ADMIN".equalsIgnoreCase(role) || "LEAD".equalsIgnoreCase(role);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }
}
