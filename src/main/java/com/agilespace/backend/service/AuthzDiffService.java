package com.agilespace.backend.service;

import com.agilespace.backend.domain.ProjectConfig;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.AuthzDiffDto;
import com.agilespace.backend.dto.MembershipDto;
import com.agilespace.backend.dto.UserProjectAccessDto;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.SquadMemberRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.LeadPolicy;
import com.agilespace.backend.security.SquadLeadership;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Compara a autorização por squad LEGADA com a NOVA (Etapa 2 do vínculo) para todos os usuários ativos x squads, sem
 * escrever nada (transação readOnly; o Postgres recusaria) e sem usar o cache de vínculos. Alimenta
 * {@code GET /api/admin/membership/authz-diff} e a categoria {@code WOULD_LOSE_ACCESS} do relatório.
 *
 * <p>Não chama {@link SquadAccessService} par a par (seriam milhares de consultas): levanta, por usuário, os fatos
 * (default_project_id, roster, cargo, resolvedor antigo, {@link MembershipService}) uma vez e por squad só a liderança
 * cadastrada, e avalia as regras puras de {@link AuthzRules}, as mesmas que explicam as divergências do modo sombra.
 * Um teste cruza essas regras com as decisões reais do {@link SquadAccessService}.
 *
 * <p>Universo: todas as squads que existem em {@code projects} mais as que aparecem para o usuário em default_project_id,
 * roster ou vínculos (squads só do roster também perderiam acesso). ADMIN (e LEAD, só com lead-level=legacy) são iguais nos
 * dois modos e ficam de fora; com lead-level=off o LEAD é um MEMBER e entra na comparação.
 */
@Service
@Slf4j
public class AuthzDiffService {

    static final int SAMPLE_LIMIT = 20;
    private static final List<String> CHECKS = List.of(AuthzRules.CHECK_MATCHES, AuthzRules.CHECK_MANAGE, AuthzRules.CHECK_LEADERSHIP);

    /** Uma divergência (com e-mail, só para o relatório de exceções; o authz-diff expõe sem e-mail). */
    public record Row(String userId, String email, String squad, String check, String direction, String reason) { }

    record Computation(List<Row> rows, int usersChecked, int usersSkippedPrivileged, int usersFailed, long pairsChecked,
                       int usersWithDivergence) { }

    private final UserRepository userRepository;
    private final ProjectConfigRepository projectConfigRepository;
    private final ProjectMemberRoleRepository projectMemberRoleRepository;
    private final SquadMemberRepository squadMemberRepository;
    private final UserProjectResolverService legacyResolver;
    private final MembershipService membershipService;
    private final AuthzShadow authz;

    /** Etapa 4 (app.membership.lead-level). Montagem manual: legacy (LEAD fora da comparação, como antes). */
    private LeadPolicy leadPolicy = LeadPolicy.LEGACY;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLeadPolicy(LeadPolicy leadPolicy) {
        if (leadPolicy != null) this.leadPolicy = leadPolicy;
    }

    public AuthzDiffService(UserRepository userRepository, ProjectConfigRepository projectConfigRepository,
                            ProjectMemberRoleRepository projectMemberRoleRepository, SquadMemberRepository squadMemberRepository,
                            UserProjectResolverService legacyResolver, MembershipService membershipService, AuthzShadow authz) {
        this.userRepository = userRepository;
        this.projectConfigRepository = projectConfigRepository;
        this.projectMemberRoleRepository = projectMemberRoleRepository;
        this.squadMemberRepository = squadMemberRepository;
        this.legacyResolver = legacyResolver;
        this.membershipService = membershipService;
        this.authz = authz;
    }

    // ---------- API ----------

    @Transactional(readOnly = true)
    public AuthzDiffDto report() {
        Computation c = compute();
        Map<String, Long> totals = new LinkedHashMap<>();
        totals.put(AuthzShadow.DIRECTION_LOSS, 0L);
        totals.put(AuthzShadow.DIRECTION_GAIN, 0L);
        Map<String, Map<String, Long>> byCheck = new LinkedHashMap<>();
        for (String check : CHECKS) {
            Map<String, Long> m = new LinkedHashMap<>();
            m.put(AuthzShadow.DIRECTION_LOSS, 0L);
            m.put(AuthzShadow.DIRECTION_GAIN, 0L);
            byCheck.put(check, m);
        }
        Map<String, Long> byReason = new TreeMap<>();
        List<AuthzDiffDto.Sample> sample = new ArrayList<>();
        for (Row r : c.rows()) {
            totals.merge(r.direction(), 1L, Long::sum);
            byCheck.get(r.check()).merge(r.direction(), 1L, Long::sum);
            byReason.merge(r.direction() + ":" + r.reason(), 1L, Long::sum);
            if (sample.size() < SAMPLE_LIMIT) {
                sample.add(new AuthzDiffDto.Sample(r.userId(), r.squad(), r.check(), r.direction(), r.reason()));
            }
        }
        return new AuthzDiffDto(Instant.now(), authz.mode().name().toLowerCase(Locale.ROOT), SAMPLE_LIMIT, c.usersChecked(),
                c.usersSkippedPrivileged(), c.usersFailed(), c.pairsChecked(), c.usersWithDivergence(), totals, byCheck, byReason,
                sample, c.rows().size() > sample.size());
    }

    /** Quem o legado deixa acessar e a regra nova NÃO deixaria (sentido LOSS), com o motivo; base de WOULD_LOSE_ACCESS. */
    @Transactional(readOnly = true)
    public List<Row> wouldLoseAccess() {
        return compute().rows().stream().filter(r -> AuthzShadow.DIRECTION_LOSS.equals(r.direction())).toList();
    }

    // ---------- cálculo ----------

    Computation compute() {
        Map<String, String> projectSquads = new LinkedHashMap<>();
        for (ProjectConfig p : projectConfigRepository.findAll()) {
            projectSquads.putIfAbsent(key(p.getId()), p.getId());
        }
        Set<String> leadershipSquads = new HashSet<>();
        for (ProjectMemberRole r : projectMemberRoleRepository.findAll()) {
            if (r.isLeadership() && r.getProjectId() != null) leadershipSquads.add(r.getProjectId().trim());
        }

        List<User> users = userRepository.findAll().stream().filter(User::isActive)
                .sorted((a, b) -> a.getId().compareTo(b.getId())).toList();

        List<Row> rows = new ArrayList<>();
        int checked = 0, skipped = 0, failed = 0, withDivergence = 0;
        long pairs = 0;
        for (User user : users) {
            if (isPrivileged(user.getRole())) {
                skipped++;
                continue;
            }
            checked++;
            try {
                List<Row> userRows = new ArrayList<>();
                pairs += evaluateUser(user, projectSquads, leadershipSquads, userRows);
                if (!userRows.isEmpty()) withDivergence++;
                rows.addAll(userRows);
            } catch (RuntimeException e) {
                failed++;
                log.warn("authz_diff_error user={} erro={}", AuthzShadow.sanitize(user.getId()), e.getClass().getSimpleName());
            }
        }
        return new Computation(rows, checked, skipped, failed, pairs, withDivergence);
    }

    private long evaluateUser(User user, Map<String, String> projectSquads, Set<String> leadershipSquads, List<Row> out) {
        // Fatos do usuário (uma vez).
        UserProjectAccessDto legacy = legacyResolver.resolveUserAccess(user);
        Map<String, Boolean> legacyLinks = new LinkedHashMap<>();
        if (legacy != null && legacy.getProjects() != null) {
            for (UserProjectAccessDto.ProjectAccessItem p : legacy.getProjects()) {
                legacyLinks.merge(key(p.getProjectId()), p.isLeadership(), Boolean::logicalOr);
            }
        }
        Map<String, Boolean> newLinks = new LinkedHashMap<>();
        List<MembershipDto> memberships = membershipService.computeFresh(user).memberships();
        for (MembershipDto m : memberships) {
            newLinks.merge(key(m.projectId()), m.leadership(), Boolean::logicalOr);
        }
        List<SquadMember> rosterRows = rosterRows(user);
        Set<String> rosterSquads = new HashSet<>();
        for (SquadMember m : rosterRows) {
            if (m.getSquadId() != null) rosterSquads.add(m.getSquadId());
        }
        boolean jobTitle = SquadLeadership.isLeadershipJobTitle(user.getJobTitle());

        // Universo de squads deste usuário.
        Map<String, String> candidates = new LinkedHashMap<>(projectSquads);
        addCandidate(candidates, user.getDefaultProjectId());
        rosterSquads.forEach(s -> addCandidate(candidates, s));
        if (legacy != null && legacy.getProjects() != null) {
            legacy.getProjects().forEach(p -> addCandidate(candidates, p.getProjectId()));
        }
        memberships.forEach(m -> addCandidate(candidates, m.projectId()));

        List<String> ordered = new ArrayList<>(candidates.values());
        ordered.sort(String.CASE_INSENSITIVE_ORDER);
        for (String squad : ordered) {
            String k = key(squad);
            boolean roster = MembershipService.isMissiAlias(squad)
                    ? rosterSquads.contains("DDWMISSI") || rosterSquads.contains("MISSI")
                    : rosterSquads.contains(squad);
            Boolean legacyLeader = legacyLinks.get(k);
            Boolean newLeader = newLinks.get(k);
            boolean hasLeadership = leadershipSquads.contains(squad.trim())
                    || (MembershipService.isMissiAlias(squad)
                        && leadershipSquads.contains("DDWMISSI".equalsIgnoreCase(squad.trim()) ? "MISSI" : "DDWMISSI"));
            AuthzRules.Facts f = new AuthzRules.Facts(
                    SquadAccessService.sessionSquadMatches(user, squad), roster, jobTitle,
                    legacyLeader != null, legacyLeader != null && legacyLeader,
                    newLeader != null, newLeader != null && newLeader,
                    hasLeadership);
            for (String check : CHECKS) {
                boolean before = AuthzRules.decide(check, f, false);
                boolean after = AuthzRules.decide(check, f, true);
                if (before != after) {
                    out.add(new Row(user.getId(), user.getEmail(), squad, check,
                            before ? AuthzShadow.DIRECTION_LOSS : AuthzShadow.DIRECTION_GAIN,
                            AuthzRules.reason(check, f, before)));
                }
            }
        }
        return ordered.size();
    }

    /** Vagas do roster que dão acesso à conta pelo critério legado: vaga reivindicada, e-mail ou conta do Jira (nome nunca). */
    private List<SquadMember> rosterRows(User user) {
        Set<SquadMember> rows = new LinkedHashSet<>();
        if (user.getId() != null && !user.getId().isBlank()) {
            List<SquadMember> claimed = squadMemberRepository.findByClaimedByUid(user.getId());
            if (claimed != null) rows.addAll(claimed);
        }
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            String email = user.getEmail().trim();
            for (SquadMember m : squadMemberRepository.findByUserIdentifier(email)) {
                if (m.getEmail() != null && email.equalsIgnoreCase(m.getEmail().trim())) rows.add(m);
            }
        }
        if (user.getJiraAccountId() != null && !user.getJiraAccountId().isBlank()) {
            String jira = user.getJiraAccountId().trim();
            for (SquadMember m : squadMemberRepository.findByUserIdentifier(jira)) {
                if (m.getJiraAccountId() != null && jira.equalsIgnoreCase(m.getJiraAccountId().trim())) rows.add(m);
            }
        }
        return new ArrayList<>(rows);
    }

    private static void addCandidate(Map<String, String> candidates, String squad) {
        if (squad == null || squad.isBlank()) return;
        candidates.putIfAbsent(key(squad), squad.trim());
    }

    /** Chave de comparação: sem caixa/espaço e com DDWMISSI e MISSI como a mesma squad. */
    static String key(String squad) {
        if (squad == null) return "";
        String k = squad.trim().toUpperCase(Locale.ROOT);
        return "MISSI".equals(k) ? "DDWMISSI" : k;
    }

    /** ADMIN (e LEAD só em lead-level=legacy, Etapa 4: com off o LEAD é tratado como MEMBER e entra na comparação). */
    private boolean isPrivileged(String role) {
        return leadPolicy.isPrivileged(role);
    }
}
