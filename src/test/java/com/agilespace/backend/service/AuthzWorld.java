package com.agilespace.backend.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agilespace.backend.domain.ProjectConfig;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.SquadMemberRepository;
import com.agilespace.backend.repository.SquadRepository;
import com.agilespace.backend.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mundo em memória para testar a autorização por squad nos três modos de {@code app.membership.authz}: usuários,
 * projetos, linhas de vínculo (project_member_roles) e roster (squad_members) ficam em listas e os repositórios
 * (mocks) respondem a partir delas. Os serviços de verdade (resolvedor antigo, {@link MembershipService},
 * {@link SquadAccessService}, {@link SquadAudienceService}, {@link AuthzDiffService}) são montados sobre eles.
 */
public final class AuthzWorld {

    public final List<User> users = new ArrayList<>();
    public final List<ProjectConfig> projects = new ArrayList<>();
    public final List<ProjectMemberRole> pmr = new ArrayList<>();
    public final List<SquadMember> roster = new ArrayList<>();

    public final UserRepository userRepository = mock(UserRepository.class);
    public final ProjectMemberRoleRepository pmrRepository = mock(ProjectMemberRoleRepository.class);
    public final ProjectConfigRepository projectRepository = mock(ProjectConfigRepository.class);
    public final SquadMemberRepository rosterRepository = mock(SquadMemberRepository.class);
    public final SquadService squadService = mock(SquadService.class);
    public final SquadRepository squadRepository = mock(SquadRepository.class);

    public final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    public final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final List<Logger> attached = new ArrayList<>();

    public final UserProjectResolverService resolver = new UserProjectResolverService(pmrRepository, projectRepository);
    public final MembershipCache cache = new MembershipCache(60);
    public final MembershipService membership = new MembershipService(resolver, projectRepository, rosterRepository,
            userRepository, cache, "legacy");

    @SuppressWarnings("unchecked")
    public AuthzWorld() {
        when(userRepository.findById(anyString())).thenAnswer(i -> users.stream().filter(u -> u.getId().equals(i.getArgument(0))).findFirst());
        when(userRepository.findAll()).thenAnswer(i -> List.copyOf(users));
        when(userRepository.findActiveByDefaultProjectIds(anyCollection())).thenAnswer(i -> {
            Set<String> ids = new HashSet<>((java.util.Collection<String>) i.getArgument(0));
            return users.stream().filter(User::isActive).filter(u -> inUpper(ids, u.getDefaultProjectId()) || inUpper(ids, u.getDefaultProjectId())).toList();
        });
        when(userRepository.findActiveByIds(anyCollection())).thenAnswer(i -> {
            Set<String> ids = new HashSet<>((java.util.Collection<String>) i.getArgument(0));
            return users.stream().filter(User::isActive).filter(u -> ids.contains(u.getId())).toList();
        });
        when(userRepository.findActiveByEmails(anyCollection())).thenAnswer(i -> {
            Set<String> emails = new HashSet<>((java.util.Collection<String>) i.getArgument(0));
            return users.stream().filter(User::isActive).filter(u -> u.getEmail() != null && emails.contains(u.getEmail().toLowerCase(Locale.ROOT))).toList();
        });
        when(userRepository.findActiveByJiraAccountIds(anyCollection())).thenAnswer(i -> {
            Set<String> ids = new HashSet<>((java.util.Collection<String>) i.getArgument(0));
            return users.stream().filter(User::isActive).filter(u -> u.getJiraAccountId() != null && ids.contains(u.getJiraAccountId())).toList();
        });

        when(pmrRepository.findByEmailIgnoreCase(anyString())).thenAnswer(i -> pmr.stream()
                .filter(r -> r.getEmail() != null && r.getEmail().equalsIgnoreCase(i.getArgument(0))).toList());
        when(pmrRepository.findByJiraAccountId(anyString())).thenAnswer(i -> pmr.stream()
                .filter(r -> i.getArgument(0).equals(r.getJiraAccountId())).toList());
        when(pmrRepository.findByUserId(anyString())).thenAnswer(i -> pmr.stream()
                .filter(r -> i.getArgument(0).equals(r.getUserId())).toList());
        when(pmrRepository.findByProjectId(anyString())).thenAnswer(i -> pmr.stream()
                .filter(r -> r.getProjectId().equals(i.getArgument(0))).toList());
        when(pmrRepository.findAll()).thenAnswer(i -> List.copyOf(pmr));
        when(pmrRepository.findByProjectIdsUpper(anyCollection())).thenAnswer(i -> {
            Set<String> ids = new HashSet<>((java.util.Collection<String>) i.getArgument(0));
            return pmr.stream().filter(r -> ids.contains(r.getProjectId().toUpperCase(Locale.ROOT))).toList();
        });

        when(projectRepository.findAll()).thenAnswer(i -> List.copyOf(projects));
        when(projectRepository.findById(anyString())).thenAnswer(i -> projects.stream().filter(p -> p.getId().equals(i.getArgument(0))).findFirst());
        when(projectRepository.findAllById(anyIterable())).thenAnswer(i -> {
            Set<String> ids = new HashSet<>();
            ((Iterable<String>) i.getArgument(0)).forEach(ids::add);
            return projects.stream().filter(p -> ids.contains(p.getId())).toList();
        });

        when(rosterRepository.findByClaimedByUid(anyString())).thenAnswer(i -> roster.stream()
                .filter(m -> i.getArgument(0).equals(m.getClaimedByUid())).toList());
        when(rosterRepository.findByUserIdentifier(anyString())).thenAnswer(i -> {
            String id = ((String) i.getArgument(0)).toLowerCase(Locale.ROOT);
            return roster.stream().filter(m -> eqi(m.getEmail(), id) || eqi(m.getJiraAccountId(), id) || eqi(m.getDisplayName(), id)).toList();
        });
        when(rosterRepository.findBySquadIdsUpper(anyCollection())).thenAnswer(i -> {
            Set<String> ids = new HashSet<>((java.util.Collection<String>) i.getArgument(0));
            return roster.stream().filter(m -> ids.contains(m.getSquadId().toUpperCase(Locale.ROOT))).toList();
        });
        when(squadService.getMembers(any())).thenAnswer(i -> roster.stream().filter(m -> m.getSquadId().equals(i.getArgument(0))).toList());

        logs.start();
        for (Class<?> c : List.of(AuthzShadow.class, SquadAccessService.class, AuthzDiffService.class)) {
            Logger l = (Logger) LoggerFactory.getLogger(c);
            l.addAppender(logs);
            l.setLevel(Level.INFO);
            attached.add(l);
        }
    }

    /** Solta os appenders do logback (chame no @AfterEach). */
    public void close() {
        attached.forEach(l -> l.detachAppender(logs));
    }

    private static boolean inUpper(Set<String> ids, String value) {
        return value != null && ids.contains(value.trim().toUpperCase(Locale.ROOT));
    }

    private static boolean eqi(String a, String lowerB) {
        return a != null && a.toLowerCase(Locale.ROOT).equals(lowerB);
    }

    // ---------- montagem do cenário ----------

    public AuthzWorld project(String id, String name, String segment, String tribe) {
        ProjectConfig p = new ProjectConfig();
        p.setId(id);
        p.setName(name);
        p.setSegmentName(segment);
        p.setTribeName(tribe);
        projects.add(p);
        return this;
    }

    /** Usuário ativo com e-mail {@code <id>@totvs.com.br}. */
    public User user(String id, String role, String squadId) {
        User u = User.builder().id(id).email(id + "@totvs.com.br").name("Pessoa " + id).role(role).active(true).defaultProjectId(squadId).build();
        users.add(u);
        return u;
    }

    /** Linha de vínculo ligada à conta pelo e-mail {@code <userId>@totvs.com.br}. */
    public AuthzWorld link(String rowId, String userId, String project, String roleKey, String roleName, boolean leadership) {
        ProjectMemberRole r = new ProjectMemberRole();
        r.setId(rowId);
        r.setProjectId(project);
        r.setEmail(userId + "@totvs.com.br");
        r.setRoleKey(roleKey);
        r.setRoleName(roleName);
        r.setDisplayName("Nome " + rowId);
        r.setLeadership(leadership);
        pmr.add(r);
        return this;
    }

    /** Linha de vínculo SEM conta (liderança cadastrada de alguém que ainda não entrou, por exemplo). */
    public AuthzWorld linkNobody(String rowId, String project, String roleKey, String roleName, boolean leadership) {
        ProjectMemberRole r = new ProjectMemberRole();
        r.setId(rowId);
        r.setProjectId(project);
        r.setEmail(rowId + "@sem-conta.com");
        r.setRoleKey(roleKey);
        r.setRoleName(roleName);
        r.setDisplayName("Nome " + rowId);
        r.setLeadership(leadership);
        pmr.add(r);
        return this;
    }

    /** Vaga do roster (squad_members) casando com a conta pelo e-mail {@code <userId>@totvs.com.br}. */
    public AuthzWorld rosterOf(String userId, String squad) {
        roster.add(SquadMember.builder().dbId(squad + "_" + userId).squadId(squad).jiraAccountId("jira-" + userId)
                .displayName("Pessoa " + userId + " (roster)").email(userId + "@totvs.com.br").role("Developer").build());
        return this;
    }

    // ---------- serviços ----------

    public AuthzShadow shadow(AuthzMode mode) {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(meters);
        return new AuthzShadow(mode.name().toLowerCase(Locale.ROOT), 0, provider);
    }

    public SquadAccessService access(AuthzMode mode) {
        return new SquadAccessService(userRepository, squadService, resolver, membership, shadow(mode));
    }

    public SquadAudienceService audience(AuthzMode mode) {
        AuthzShadow shadow = shadow(mode);
        SquadAccessService access = new SquadAccessService(userRepository, squadService, resolver, membership, shadow);
        return new SquadAudienceService(userRepository, pmrRepository, rosterRepository, resolver, squadRepository,
                projectRepository, membership, shadow, access);
    }

    public AuthzDiffService diff() {
        return new AuthzDiffService(userRepository, projectRepository, pmrRepository, rosterRepository, resolver, membership,
                shadow(AuthzMode.SHADOW));
    }

    // ---------- logs ----------

    public List<String> divergenceLines() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.startsWith("authz_divergence")).collect(Collectors.toList());
    }

    public long divergenceCount(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level && e.getFormattedMessage().startsWith("authz_divergence")).count();
    }

    public List<String> lines(String prefix) {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.startsWith(prefix)).collect(Collectors.toList());
    }

    public Optional<User> find(String id) {
        return users.stream().filter(u -> u.getId().equals(id)).findFirst();
    }
}
