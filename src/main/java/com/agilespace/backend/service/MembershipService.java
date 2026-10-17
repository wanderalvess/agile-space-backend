package com.agilespace.backend.service;

import com.agilespace.backend.domain.MembershipSource;
import com.agilespace.backend.domain.ProjectConfig;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.MembershipDto;
import com.agilespace.backend.dto.MembershipDto.RoleDto;
import com.agilespace.backend.dto.MembershipsResponseDto;
import com.agilespace.backend.dto.UserProjectAccessDto;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.SquadMemberRepository;
import com.agilespace.backend.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Vínculo Pessoa x Squad x Papel lido de {@code project_member_roles} (a fonte da verdade). Substitui, no modelo novo, a
 * parte de leitura do {@link UserProjectResolverService}: devolve TODOS os papéis da pessoa em cada squad (o resolvedor
 * antigo escolhia uma linha e descartava as outras), com um papel primário por precedência, o escopo de cada vínculo
 * (SQUAD, TRIBE ou SEGMENT) e a squad ativa validada contra os vínculos.
 *
 * <p>Etapa 1 do plano (docs/vinculo-auditoria-e-plano.md): aditivo. Quem decide o que a sessão devolve é
 * {@code app.membership.mode} (ver {@link MembershipMode}); a autorização por squad continua no
 * {@code SquadAccessService}/resolvedor antigo.
 */
@Service
@Slf4j
public class MembershipService {

    static final String TRANSVERSAL_ROLE_NAME = "Liderança Transversal da Tribo";
    static final String TRANSVERSAL_ROLE_KEY = "TRIBE_LEADER_ACCESS";

    /** Resultado completo para uma pessoa. */
    public record View(List<MembershipDto> memberships, String activeProjectId, boolean legacyOnly, boolean transversalLeader) { }

    private final UserProjectResolverService resolver;
    private final ProjectConfigRepository projectConfigRepository;
    private final SquadMemberRepository squadMemberRepository;
    private final UserRepository userRepository;
    private final MembershipCache cache;
    private final MembershipMode mode;

    public MembershipService(UserProjectResolverService resolver,
                             ProjectConfigRepository projectConfigRepository,
                             SquadMemberRepository squadMemberRepository,
                             UserRepository userRepository,
                             MembershipCache cache,
                             @Value("${app.membership.mode:shadow}") String modeRaw) {
        this.resolver = resolver;
        this.projectConfigRepository = projectConfigRepository;
        this.squadMemberRepository = squadMemberRepository;
        this.userRepository = userRepository;
        this.cache = cache;
        this.mode = MembershipMode.parse(modeRaw);
        if (this.mode == MembershipMode.LEGACY && modeRaw != null && !modeRaw.isBlank()
                && !"legacy".equalsIgnoreCase(modeRaw.trim())) {
            log.warn("app.membership.mode inválido ({}): usando legacy (comportamento anterior)", modeRaw);
        }
        log.info("Vínculo: app.membership.mode={}", this.mode.name().toLowerCase(Locale.ROOT));
    }

    public MembershipMode mode() {
        return mode;
    }

    // ---------- leitura ----------

    /** Vínculos do usuário autenticado (GET /api/me/memberships). 401 se a conta não existe mais. */
    @Transactional(readOnly = true)
    public MembershipsResponseDto responseFor(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida ou expirada"));
        View view = forUser(user);
        return new MembershipsResponseDto(MembershipsResponseDto.CONTRACT_VERSION, view.activeProjectId(),
                view.legacyOnly(), view.transversalLeader(), view.memberships());
    }

    /** Vínculos do usuário, com cache de TTL curto. */
    @Transactional(readOnly = true)
    public View forUser(User user) {
        String fingerprint = fingerprint(user);
        MembershipCache.Snapshot snapshot = cache.get(user.getId(), fingerprint).orElse(null);
        if (snapshot == null) {
            snapshot = load(user);
            cache.put(user.getId(), fingerprint, snapshot);
        }
        return view(user, snapshot);
    }

    /** Igual a {@link #forUser}, mas sempre recalcula do banco e não toca no cache (relatório shadow-diff). */
    @Transactional(readOnly = true)
    public View computeFresh(User user) {
        return view(user, load(user));
    }

    private View view(User user, MembershipCache.Snapshot snapshot) {
        List<MembershipDto> list = snapshot.memberships();
        String active = resolveActive(user.getDefaultProjectId(), list);
        boolean legacyOnly = list.isEmpty() && hasLegacyTraces(user);
        return new View(list, active, legacyOnly, snapshot.transversalLeader());
    }

    public void evict(String userId) {
        cache.evict(userId);
    }

    /**
     * A preferência (users.default_project_id) só vale se está nos vínculos (comparação sem caixa; devolve o id
     * canônico do vínculo). Fora deles, a primeira squad por precedência; sem vínculo nenhum, null.
     */
    static String resolveActive(String preference, List<MembershipDto> memberships) {
        if (memberships.isEmpty()) return null;
        if (preference != null && !preference.isBlank()) {
            String wanted = preference.trim();
            for (MembershipDto m : memberships) {
                if (m.projectId().equalsIgnoreCase(wanted)) return m.projectId();
            }
        }
        return memberships.get(0).projectId();
    }

    static String fingerprint(User user) {
        return nullToEmpty(user.getId()) + "|" + nullToEmpty(user.getEmail()).trim().toLowerCase(Locale.ROOT)
                + "|" + nullToEmpty(user.getJiraAccountId()).trim();
    }

    // ---------- cálculo ----------

    private MembershipCache.Snapshot load(User user) {
        List<ProjectMemberRole> rows = resolver.loadDirectRows(user);

        Map<String, List<ProjectMemberRole>> byProject = new LinkedHashMap<>();
        for (ProjectMemberRole r : rows) {
            byProject.computeIfAbsent(r.getProjectId(), k -> new ArrayList<>()).add(r);
        }

        boolean transversal = rows.stream().anyMatch(UserProjectResolverService::isTransversalLeadershipRow);
        Map<String, ProjectConfig> projects = new HashMap<>();
        List<ProjectConfig> all = null;
        if (transversal) {
            all = projectConfigRepository.findAll();
            all.forEach(p -> projects.put(p.getId(), p));
        } else if (!byProject.isEmpty()) {
            projectConfigRepository.findAllById(byProject.keySet()).forEach(p -> projects.put(p.getId(), p));
        }

        List<MembershipDto> direct = new ArrayList<>();
        Set<String> leaderTribes = new HashSet<>();
        Set<String> leaderSegments = new HashSet<>();
        for (Map.Entry<String, List<ProjectMemberRole>> e : byProject.entrySet()) {
            ProjectConfig project = projects.get(e.getKey());
            String projectName = project != null && project.getName() != null ? project.getName() : e.getKey();
            String segmentName = project != null && project.getSegmentName() != null ? project.getSegmentName() : "";
            String tribeName = project != null && project.getTribeName() != null ? project.getTribeName() : "";

            List<ProjectMemberRole> ordered = new ArrayList<>(e.getValue());
            ordered.sort(UserProjectResolverService.PRIMARY_ROLE_ORDER);
            ProjectMemberRole primary = ordered.get(0);
            List<RoleDto> roles = new ArrayList<>();
            for (int i = 0; i < ordered.size(); i++) {
                ProjectMemberRole r = ordered.get(i);
                roles.add(new RoleDto(r.getRoleName(), r.getRoleKey(), r.isLeadership(), MembershipSource.normalize(r.getSource()), i == 0));
            }
            direct.add(new MembershipDto(e.getKey(), projectName, segmentName, tribeName, primary.getRoleName(),
                    primary.getRoleKey(), primary.isLeadership(), true, MembershipDto.SCOPE_SQUAD,
                    MembershipSource.normalize(primary.getSource()), List.copyOf(roles)));

            // Liderança transversal olha TODAS as linhas da squad, não só a primária.
            for (ProjectMemberRole r : ordered) {
                if (UserProjectResolverService.isTransversalLeadershipRow(r)) {
                    if (!tribeName.isBlank()) leaderTribes.add(tribeName.toLowerCase(Locale.ROOT));
                    if (!segmentName.isBlank()) leaderSegments.add(segmentName.toLowerCase(Locale.ROOT));
                }
            }
        }
        direct.sort(DIRECT_ORDER);

        List<MembershipDto> expanded = new ArrayList<>();
        if (transversal && all != null) {
            Set<String> directIds = direct.stream().map(MembershipDto::projectId).collect(Collectors.toSet());
            for (ProjectConfig p : all) {
                boolean matchTribe = p.getTribeName() != null && leaderTribes.contains(p.getTribeName().toLowerCase(Locale.ROOT));
                boolean matchSeg = p.getSegmentName() != null && leaderSegments.contains(p.getSegmentName().toLowerCase(Locale.ROOT));
                if ((matchTribe || matchSeg) && !directIds.contains(p.getId())) {
                    expanded.add(new MembershipDto(p.getId(), p.getName(), p.getSegmentName(), p.getTribeName(),
                            TRANSVERSAL_ROLE_NAME, TRANSVERSAL_ROLE_KEY, true, false,
                            matchTribe ? MembershipDto.SCOPE_TRIBE : MembershipDto.SCOPE_SEGMENT, null,
                            List.of(new RoleDto(TRANSVERSAL_ROLE_NAME, TRANSVERSAL_ROLE_KEY, true, null, true))));
                }
            }
            expanded.sort(EXPANDED_ORDER);
        }

        List<MembershipDto> result = new ArrayList<>(direct.size() + expanded.size());
        result.addAll(direct);
        result.addAll(expanded);
        return new MembershipCache.Snapshot(List.copyOf(result), transversal);
    }

    /** Vínculos próprios: liderança primeiro, maior alcance, depois nome da squad e id (determinístico). */
    private static final Comparator<MembershipDto> DIRECT_ORDER = Comparator
            .comparing((MembershipDto m) -> !m.leadership())
            .thenComparingInt(m -> UserProjectResolverService.rankOfKey(m.roleKey()))
            .thenComparing(m -> nullToEmpty(m.projectName()), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(MembershipDto::projectId);

    /** Alcançados por liderança transversal: tribo antes de segmento, depois nome e id. */
    private static final Comparator<MembershipDto> EXPANDED_ORDER = Comparator
            .comparing((MembershipDto m) -> MembershipDto.SCOPE_TRIBE.equals(m.scope()) ? 0 : 1)
            .thenComparing(m -> nullToEmpty(m.projectName()), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(MembershipDto::projectId);

    /**
     * True quando a conta não tem nenhuma linha pmr resolvível mas ainda tem rastros do modelo antigo: users.squad_id
     * ou default_project_id preenchido (fora do sentinela "Sem Time") ou presença no roster (squad_members). Quem não
     * tem nada disso é conta nova: vai para o onboarding, não é "só legado".
     */
    boolean hasLegacyTraces(User user) {
        if (meaningfulSquad(user.getSquadId()) || meaningfulSquad(user.getDefaultProjectId())) {
            return true;
        }
        if (user.getId() != null && !user.getId().isBlank()) {
            List<SquadMember> claimed = squadMemberRepository.findByClaimedByUid(user.getId());
            if (claimed != null && !claimed.isEmpty()) return true;
        }
        for (String identifier : new String[] { user.getEmail(), user.getJiraAccountId() }) {
            if (identifier != null && !identifier.isBlank()) {
                List<SquadMember> byIdentifier = squadMemberRepository.findByUserIdentifier(identifier.trim());
                if (byIdentifier != null && !byIdentifier.isEmpty()) return true;
            }
        }
        return false;
    }

    private static boolean meaningfulSquad(String squad) {
        if (squad == null || squad.isBlank()) return false;
        String s = squad.trim();
        return !"Sem Time".equalsIgnoreCase(s) && !"Sem Squad".equalsIgnoreCase(s);
    }

    // ---------- ponte para o contrato antigo ----------

    /** Vínculos no formato de accessibleProjects (AuthResponse), sem perder nenhum campo existente. */
    public static UserProjectAccessDto toAccess(User user, View view) {
        List<UserProjectAccessDto.ProjectAccessItem> items = view.memberships().stream()
                .map(m -> UserProjectAccessDto.ProjectAccessItem.builder()
                        .projectId(m.projectId())
                        .projectName(m.projectName())
                        .segmentName(m.segmentName())
                        .tribeName(m.tribeName())
                        .roleName(m.roleName())
                        .roleKey(m.roleKey())
                        .isDirectAssignment(m.direct())
                        .isLeadership(m.leadership())
                        .build())
                .collect(Collectors.toCollection(ArrayList::new));
        return UserProjectAccessDto.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .primaryProjectId(view.activeProjectId())
                .isTransversalLeader(view.transversalLeader())
                .projects(items)
                .build();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
