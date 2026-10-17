package com.agilespace.backend.service;

import com.agilespace.backend.domain.ProjectConfig;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.MembershipDto;
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
    /** Etapa 2 do vínculo (app.membership.authz). Nulos em montagens manuais/testes antigos: só o legado. */
    private final MembershipService membershipService;
    private final AuthzShadow authz;
    private final SquadAccessService squadAccessService;

    private boolean legacyOnly() {
        return authz == null || membershipService == null || authz.isLegacy();
    }

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
        if (legacyOnly()) {
            return Optional.of(legacyContext(user, tokenRole));
        }
        // Etapa 2: new = só os vínculos (pmr); shadow = legado decide e a divergência vai para o log/métrica.
        return Optional.of(authz.choose("audience", userId,
                () -> legacyContext(user, tokenRole),
                () -> newContext(user, tokenRole),
                (legacy, neu) -> compareContexts(user, legacy, neu)));
    }

    /**
     * Regra NOVA: squads, papéis e liderança vêm só dos vínculos (project_member_roles via {@link MembershipService}):
     * deixam de contar default_project_id (e a antiga users.squad_id), o roster (squad_members) e o cargo (jobTitle). A squad
     * corrente é a ativa validada contra os vínculos; os papéis incluem TODOS os que a pessoa tem em cada squad.
     */
    private UserContext newContext(User user, String tokenRole) {
        List<MembershipDto> memberships = membershipService.membershipsOf(user);
        String current = MembershipService.resolveActive(user.getDefaultProjectId(), memberships);

        Map<String, String> squads = new LinkedHashMap<>();
        addSquad(squads, current);
        for (MembershipDto m : memberships) {
            addSquad(squads, m.projectId());
        }

        Set<String> roleKeys = new LinkedHashSet<>();
        String role = isPrivileged(tokenRole) ? tokenRole : user.getRole();
        if (role != null) roleKeys.add(role.toUpperCase(Locale.ROOT));
        if (user.getRole() != null) roleKeys.add(user.getRole().toUpperCase(Locale.ROOT));
        for (MembershipDto m : memberships) {
            for (MembershipDto.RoleDto r : m.roles()) {
                if (r.roleKey() != null && !r.roleKey().isBlank()) roleKeys.add(r.roleKey().toUpperCase(Locale.ROOT));
            }
        }

        boolean leadership = isPrivileged(tokenRole) || isPrivileged(user.getRole())
                || memberships.stream().anyMatch(MembershipDto::leadership);

        return new UserContext(user, current, List.copyOf(squads.values()), roleKeys, leadership);
    }

    private void compareContexts(User user, UserContext legacy, UserContext neu) {
        for (String s : legacy.squadIds()) {
            if (!neu.hasSquad(s)) {
                authz.recordLazy("audience", user.getId(), s, true, false, () ->
                        squadAccessService != null ? squadAccessService.lossReasonFor(user, s) : "UNKNOWN");
            }
        }
        for (String s : neu.squadIds()) {
            if (!legacy.hasSquad(s)) {
                authz.record("audience", user.getId(), s, false, true, AuthzRules.NEW_ONLY_MEMBERSHIP);
            }
        }
        if (legacy.leadership() != neu.leadership()) {
            authz.record("audienceLeadership", user.getId(), null, legacy.leadership(), neu.leadership(),
                    legacy.leadership()
                            ? (SquadLeadership.isLeadershipJobTitle(user.getJobTitle()) ? AuthzRules.ONLY_JOB_TITLE : AuthzRules.RESOLVER_ONLY)
                            : AuthzRules.NEW_ONLY_MEMBERSHIP);
        }
        if (!neu.roleKeys().containsAll(legacy.roleKeys())) {
            authz.record("audienceRoles", user.getId(), null, true, false, "ROLE_KEYS_LOST");
        }
        if (!legacy.roleKeys().containsAll(neu.roleKeys())) {
            authz.record("audienceRoles", user.getId(), null, false, true, "ROLE_KEYS_ADDED");
        }
    }

    private UserContext legacyContext(User user, String tokenRole) {
        UserProjectAccessDto access = userProjectResolverService.resolveUserAccess(user);
        List<UserProjectAccessDto.ProjectAccessItem> projects =
                access != null && access.getProjects() != null ? access.getProjects() : List.of();

        String current = firstNonBlank(user.getDefaultProjectId(),
                access != null ? access.getPrimaryProjectId() : null);

        Map<String, String> squads = new LinkedHashMap<>();
        addSquad(squads, current);
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

        return new UserContext(user, current, List.copyOf(squads.values()), roleKeys, leadership);
    }

    /**
     * Usuários ativos de cada squad (chave = id da squad em MAIÚSCULAS). Número fixo de consultas, qualquer que seja
     * a quantidade de squads.
     */
    @Transactional(readOnly = true)
    public Map<String, Set<String>> memberUserIds(Collection<String> squadIds) {
        Set<String> ids = upper(squadIds);
        if (ids.isEmpty()) {
            Map<String, Set<String>> empty = new LinkedHashMap<>();
            return empty;
        }
        if (legacyOnly()) {
            return legacyMemberUserIds(ids).result();
        }
        // Etapa 2: new = só quem tem linha em project_member_roles na squad; shadow compara e registra.
        return authz.choose("audienceMembers", null,
                () -> legacyMemberUserIds(ids),
                () -> new Members(newMemberUserIds(ids), Map.of(), Map.of()),
                (legacy, neu) -> compareMembers(legacy, neu.result()))
                .result();
    }

    /** Resultado do cálculo legado com a origem do acesso (só para explicar divergências no modo sombra). */
    private record Members(Map<String, Set<String>> result, Map<String, Set<String>> viaSessionSquad,
                           Map<String, Set<String>> viaRoster) { }

    private Members legacyMemberUserIds(Set<String> ids) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        ids.forEach(id -> result.put(id, new LinkedHashSet<>()));
        Map<String, Set<String>> viaSession = new LinkedHashMap<>();
        Map<String, Set<String>> viaRoster = new LinkedHashMap<>();
        ids.forEach(id -> {
            viaSession.put(id, new LinkedHashSet<>());
            viaRoster.put(id, new LinkedHashSet<>());
        });
        for (User u : userRepository.findActiveByDefaultProjectIds(ids)) {
            addIfIn(result, u.getDefaultProjectId(), u.getId());
            addIfIn(viaSession, u.getDefaultProjectId(), u.getId());
        }

        // Linhas de papel (project_member_roles) e roster (squad_members) apontam para a conta por id, e-mail ou Jira.
        List<Link> links = new ArrayList<>();
        for (ProjectMemberRole r : projectMemberRoleRepository.findByProjectIdsUpper(ids)) {
            links.add(new Link(r.getProjectId(), r.getUserId(), r.getEmail(), r.getJiraAccountId()));
        }
        int pmrLinks = links.size();
        for (SquadMember m : squadMemberRepository.findBySquadIdsUpper(ids)) {
            links.add(new Link(m.getSquadId(), m.getClaimedByUid(), m.getEmail(), m.getJiraAccountId()));
        }
        Resolved resolved = resolve(links);
        for (int i = 0; i < links.size(); i++) {
            Link l = links.get(i);
            for (String userId : resolved.userIdsFor(l)) {
                addIfIn(result, l.squadId(), userId);
                if (i >= pmrLinks) addIfIn(viaRoster, l.squadId(), userId);
            }
        }
        return new Members(result, viaSession, viaRoster);
    }

    /** Regra NOVA: só as linhas de project_member_roles da squad (conta por user_id, e-mail ou conta do Jira). */
    private Map<String, Set<String>> newMemberUserIds(Set<String> ids) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        ids.forEach(id -> result.put(id, new LinkedHashSet<>()));
        List<Link> links = new ArrayList<>();
        for (ProjectMemberRole r : projectMemberRoleRepository.findByProjectIdsUpper(ids)) {
            links.add(new Link(r.getProjectId(), r.getUserId(), r.getEmail(), r.getJiraAccountId()));
        }
        Resolved resolved = resolve(links);
        for (Link l : links) {
            for (String userId : resolved.userIdsFor(l)) {
                addIfIn(result, l.squadId(), userId);
            }
        }
        return result;
    }

    private static final int MAX_MEMBER_DIVERGENCES_PER_CALL = 200;

    private void compareMembers(Members legacy, Map<String, Set<String>> neu) {
        int recorded = 0;
        for (Map.Entry<String, Set<String>> e : legacy.result().entrySet()) {
            Set<String> fresh = neu.getOrDefault(e.getKey(), Set.of());
            for (String userId : e.getValue()) {
                if (!fresh.contains(userId) && recorded++ < MAX_MEMBER_DIVERGENCES_PER_CALL) {
                    boolean session = legacy.viaSessionSquad().getOrDefault(e.getKey(), Set.of()).contains(userId);
                    boolean roster = legacy.viaRoster().getOrDefault(e.getKey(), Set.of()).contains(userId);
                    authz.record("audienceMembers", userId, e.getKey(), true, false,
                            session && roster ? AuthzRules.ROSTER_AND_SQUAD_ID
                                    : session ? AuthzRules.ONLY_SQUAD_ID
                                    : roster ? AuthzRules.ONLY_ROSTER : AuthzRules.RESOLVER_ONLY);
                }
            }
            for (String userId : fresh) {
                if (!e.getValue().contains(userId) && recorded++ < MAX_MEMBER_DIVERGENCES_PER_CALL) {
                    authz.record("audienceMembers", userId, e.getKey(), false, true, AuthzRules.NEW_ONLY_MEMBERSHIP);
                }
            }
        }
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
