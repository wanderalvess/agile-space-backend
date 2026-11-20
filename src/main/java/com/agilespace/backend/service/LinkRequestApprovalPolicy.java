package com.agilespace.backend.service;

import com.agilespace.backend.domain.LinkRequest;
import com.agilespace.backend.domain.ProjectConfig;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.MembershipDto;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Quem pode decidir um pedido de vínculo (Etapa 3 do plano, docs/link-requests-squad-api.md). Lê SÓ dos vínculos
 * ({@code project_member_roles} via {@link MembershipService}, com o cache de TTL curto): poder de aprovar exige linha
 * com {@code is_leadership = true} e {@code role_key} na allowlist FIXA abaixo. Cargo (jobTitle), papel autodeclarado e
 * {@code users.role = LEAD} nunca dão esse poder; a lista não é configurável por dado.
 *
 * <ul>
 *   <li>Pedido de CONTRIBUIDOR: AM/SM/PO/People Lead da própria squad, People Lead/Tribe Lead (da squad ou de outra
 *       squad da mesma tribo) ou ADMIN.</li>
 *   <li>Pedido de LIDERANÇA: People Lead/Tribe Lead (da squad ou da tribo) ou ADMIN.</li>
 * </ul>
 * A regra "aprovador diferente do solicitante" é do {@link LinkRequestService} (precisa do pedido). Com
 * {@code app.link-requests.squad-approval=off} só o ADMIN decide e ninguém é considerado aprovador de squad.
 */
@Service
@Slf4j
public class LinkRequestApprovalPolicy {

    /** Papéis que aprovam pedido de contribuidor NA squad do pedido (allowlist fixa, nunca por dado). */
    static final Set<String> SQUAD_APPROVER_ROLE_KEYS = Set.of("AGILE_MASTER", "SCRUM_MASTER", "PRODUCT_OWNER", "PEOPLE_LEAD");
    /** Papéis que aprovam pedido de liderança, e também de contribuidor, na squad ou na tribo (allowlist fixa). */
    static final Set<String> LEADERSHIP_APPROVER_ROLE_KEYS = Set.of("PEOPLE_LEAD", "TRIBE_LEAD");

    /** Resultado de {@code GET /api/squads/{id}/link-requests/can-approve}. */
    public record Eligibility(boolean canApprove, boolean canApproveLeadership) { }

    private final MembershipService membershipService;
    private final ProjectConfigRepository projectConfigRepository;
    private final ProjectMemberRoleRepository projectMemberRoleRepository;
    private final UserRepository userRepository;
    private final boolean squadApprovalEnabled;

    public LinkRequestApprovalPolicy(MembershipService membershipService,
                                     ProjectConfigRepository projectConfigRepository,
                                     ProjectMemberRoleRepository projectMemberRoleRepository,
                                     UserRepository userRepository,
                                     @Value("${app.link-requests.squad-approval:on}") String squadApprovalRaw) {
        this.membershipService = membershipService;
        this.projectConfigRepository = projectConfigRepository;
        this.projectMemberRoleRepository = projectMemberRoleRepository;
        this.userRepository = userRepository;
        this.squadApprovalEnabled = parseFlag(squadApprovalRaw);
        log.info("Pedidos de vínculo: app.link-requests.squad-approval={}", squadApprovalEnabled ? "on" : "off");
    }

    /** "on" (ou vazio) liga; "off" desliga; qualquer outro valor desliga (comportamento anterior) com um aviso. */
    static boolean parseFlag(String raw) {
        if (raw == null || raw.isBlank()) return true;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.equals("on") || v.equals("true")) return true;
        if (!v.equals("off") && !v.equals("false")) {
            log.warn("app.link-requests.squad-approval inválido ({}): usando off (só ADMIN aprova)", raw);
        }
        return false;
    }

    public boolean squadApprovalEnabled() {
        return squadApprovalEnabled;
    }

    // ---------- decisão ----------

    public static boolean isAdmin(User user) {
        return user != null && "ADMIN".equalsIgnoreCase(user.getRole());
    }

    /** Pode decidir um pedido DESTE {@code kind} na squad? */
    @Transactional(readOnly = true)
    public boolean canDecide(User caller, String squadKey, String kind) {
        Eligibility e = eligibility(caller, squadKey);
        return LinkRequest.KIND_LEADERSHIP.equals(kind) ? e.canApproveLeadership() : e.canApprove();
    }

    /** Poderes do próprio chamador na squad (contribuidor e liderança). */
    @Transactional(readOnly = true)
    public Eligibility eligibility(User caller, String squadKey) {
        if (caller == null || squadKey == null || squadKey.isBlank()) return new Eligibility(false, false);
        if (isAdmin(caller)) return new Eligibility(true, true);
        if (!squadApprovalEnabled) return new Eligibility(false, false);

        List<MembershipDto> memberships = membershipService.membershipsOf(caller);
        boolean contributor = false;
        boolean leadership = false;

        // 1) papéis do próprio chamador NA squad do pedido
        MembershipDto inSquad = MembershipService.findIn(memberships, squadKey);
        if (inSquad != null && inSquad.direct() && inSquad.roles() != null) {
            for (MembershipDto.RoleDto r : inSquad.roles()) {
                if (!r.leadership()) continue;
                String key = upper(r.roleKey());
                if (SQUAD_APPROVER_ROLE_KEYS.contains(key)) contributor = true;
                if (LEADERSHIP_APPROVER_ROLE_KEYS.contains(key)) { contributor = true; leadership = true; }
            }
        }

        // 2) People Lead / Tribe Lead de OUTRA squad da mesma tribo (expansão de tribo; tribo vazia nunca casa)
        if (!(contributor && leadership)) {
            String tribe = tribeOf(squadKey);
            if (!tribe.isEmpty()) {
                for (MembershipDto m : memberships) {
                    if (!m.direct() || m.roles() == null || !tribe.equalsIgnoreCase(nullToEmpty(m.tribeName()).trim())) continue;
                    for (MembershipDto.RoleDto r : m.roles()) {
                        if (r.leadership() && LEADERSHIP_APPROVER_ROLE_KEYS.contains(upper(r.roleKey()))) {
                            contributor = true;
                            leadership = true;
                        }
                    }
                }
            }
        }
        return new Eligibility(contributor, leadership);
    }

    // ---------- destinatários das notificações ----------

    /**
     * Ids das contas ativas que podem decidir um pedido deste {@code kind} na squad (para notificar), SEM os ADMINs:
     * eles só entram como fallback quando este conjunto vem vazio. Vazio também com a flag desligada.
     */
    @Transactional(readOnly = true)
    public Set<String> approverUserIds(String squadKey, String kind) {
        if (!squadApprovalEnabled || squadKey == null || squadKey.isBlank()) return Set.of();
        boolean leadershipOnly = LinkRequest.KIND_LEADERSHIP.equals(kind);
        Set<String> squadKeys = LinkRequestService.squadKeyVariants(squadKey);
        Set<String> upperSquadKeys = new HashSet<>();
        squadKeys.forEach(k -> upperSquadKeys.add(k.toUpperCase(Locale.ROOT)));

        // squads da mesma tribo (para PL/TL transversais)
        Set<String> tribeProjects = new HashSet<>();
        String tribe = tribeOf(squadKey);
        if (!tribe.isEmpty()) {
            for (ProjectConfig p : projectConfigRepository.findByTribeNameIgnoreCase(tribe)) {
                tribeProjects.add(p.getId().toUpperCase(Locale.ROOT));
            }
        }
        Set<String> projectIds = new HashSet<>(upperSquadKeys);
        projectIds.addAll(tribeProjects);

        List<ProjectMemberRole> candidates = new ArrayList<>();
        for (ProjectMemberRole row : projectMemberRoleRepository.findByProjectIdsUpper(projectIds)) {
            if (!row.isLeadership()) continue;
            String key = upper(row.getRoleKey());
            boolean onSquad = upperSquadKeys.contains(upper(row.getProjectId()));
            boolean ok = LEADERSHIP_APPROVER_ROLE_KEYS.contains(key)
                    || (!leadershipOnly && onSquad && SQUAD_APPROVER_ROLE_KEYS.contains(key));
            if (ok) candidates.add(row);
        }
        if (candidates.isEmpty()) return Set.of();

        Set<String> userIds = new HashSet<>();
        Set<String> emails = new HashSet<>();
        Set<String> jiraIds = new HashSet<>();
        for (ProjectMemberRole row : candidates) {
            if (notBlank(row.getUserId())) userIds.add(row.getUserId());
            if (notBlank(row.getEmail())) emails.add(row.getEmail().trim().toLowerCase(Locale.ROOT));
            if (notBlank(row.getJiraAccountId())) jiraIds.add(row.getJiraAccountId().trim());
        }
        Set<String> out = new HashSet<>();
        if (!userIds.isEmpty()) userRepository.findActiveByIds(userIds).forEach(u -> out.add(u.getId()));
        if (!emails.isEmpty()) userRepository.findActiveByEmails(emails).forEach(u -> out.add(u.getId()));
        if (!jiraIds.isEmpty()) userRepository.findActiveByJiraAccountIds(jiraIds).forEach(u -> out.add(u.getId()));
        return out;
    }

    // ---------- internos ----------

    private String tribeOf(String squadKey) {
        for (String k : LinkRequestService.squadKeyVariants(squadKey)) {
            ProjectConfig p = projectConfigRepository.findById(k).orElse(null);
            if (p != null) return nullToEmpty(p.getTribeName()).trim();
        }
        return "";
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
