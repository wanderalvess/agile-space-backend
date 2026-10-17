package com.agilespace.backend.service;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.LeadDemotionDto;
import com.agilespace.backend.dto.LeadRestoreDto;
import com.agilespace.backend.repository.ApiKeyRepository;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.LeadPolicy;
import com.agilespace.backend.security.UserSessionGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Ferramenta de admin da Etapa 4 do vínculo: rebaixa contas {@code LEAD} para {@code MEMBER} de forma controlada e
 * reversível (docs/membership-api.md). Nada aqui roda sozinho: a migração V77 só cria o backup, e o rebaixamento é um
 * POST de ADMIN (dry-run por padrão).
 *
 * <ul>
 *   <li>{@code HAS_LEADERSHIP_LINK}: a conta tem pelo menos uma linha de liderança em project_member_roles (por conta,
 *       e-mail ou conta do Jira, como o relatório e o resolvedor): pode virar MEMBER sem perder o poder de liderança
 *       onde tem vínculo.</li>
 *   <li>{@code NO_LEADERSHIP_LINK}: ficaria sem poder em squad nenhuma. NÃO é rebaixada (só reportada), a não ser que o
 *       admin peça uma conta específica com {@code forceDemote=true}.</li>
 * </ul>
 *
 * Nunca cria vínculo, nunca infere liderança e não mexe nas chaves de API (só avisa quantas estão ativas).
 */
@Service
@Slf4j
public class LeadDemotionService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 200;

    static final String LEAD = "LEAD";
    static final String MEMBER = "MEMBER";

    private final UserRepository userRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final AuditLogRepository auditLogRepository;
    private final JdbcTemplate jdbc;
    private final LeadPolicy leadPolicy;
    /** Opcional: sem ele (montagem manual) não há cache de sessão a derrubar. */
    private UserSessionGuard sessionGuard;

    @Autowired
    public LeadDemotionService(UserRepository userRepository, ApiKeyRepository apiKeyRepository,
                               AuditLogRepository auditLogRepository, JdbcTemplate jdbc, LeadPolicy leadPolicy) {
        this.userRepository = userRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.auditLogRepository = auditLogRepository;
        this.jdbc = jdbc;
        this.leadPolicy = leadPolicy;
    }

    @Autowired(required = false)
    public void setSessionGuard(UserSessionGuard sessionGuard) {
        this.sessionGuard = sessionGuard;
    }

    // =====================================================================================================
    // Rebaixamento
    // =====================================================================================================

    /**
     * @param apply       false = dry-run (nada é gravado)
     * @param limit       tamanho máximo do lote (1..{@value #MAX_LIMIT}); ignorado quando há {@code userId}
     * @param userId      restringe a uma conta (404 se não existe, 409 se não é LEAD)
     * @param forceDemote permite rebaixar uma conta NO_LEADERSHIP_LINK; exige {@code userId}
     * @param actorId     id do admin (auditoria)
     */
    @Transactional
    public LeadDemotionDto demote(boolean apply, int limit, String userId, boolean forceDemote, String actorId) {
        boolean single = userId != null && !userId.isBlank();
        if (forceDemote && !single) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "forceDemote exige userId: rebaixar sem liderança é uma decisão por conta.");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit deve estar entre 1 e " + MAX_LIMIT + ".");
        }

        List<User> candidates = single ? List.of(singleLead(userId.trim())) : allLeads();
        Map<String, TreeSet<String>> leadershipSquads = leadershipSquadsOf(candidates);

        List<LeadDemotionDto.Item> items = new ArrayList<>();
        int hasLink = 0, noLink = 0, would = 0, demoted = 0, kept = 0, over = 0;
        int eligibleSeen = 0;
        String actor = actorLabel(actorId);
        LocalDateTime now = LocalDateTime.now();

        for (User user : candidates) {
            TreeSet<String> squads = leadershipSquads.getOrDefault(user.getId(), new TreeSet<>());
            boolean linked = !squads.isEmpty();
            if (linked) hasLink++; else noLink++;
            String classification = linked ? LeadDemotionDto.HAS_LEADERSHIP_LINK : LeadDemotionDto.NO_LEADERSHIP_LINK;
            boolean forced = !linked && forceDemote;
            boolean eligible = linked || forced;

            String action;
            if (!eligible) {
                action = LeadDemotionDto.KEPT_NO_LEADERSHIP_LINK;
                kept++;
            } else if (!single && ++eligibleSeen > limit) {
                action = LeadDemotionDto.OVER_LIMIT;
                over++;
            } else if (!apply) {
                action = forced ? LeadDemotionDto.WOULD_DEMOTE_FORCED : LeadDemotionDto.WOULD_DEMOTE;
                would++;
            } else {
                applyDemotion(user, squads, forced, actor, now);
                action = forced ? LeadDemotionDto.DEMOTED_FORCED : LeadDemotionDto.DEMOTED;
                demoted++;
            }

            long keys = apiKeyRepository.countActiveByOwner(user.getId(), now);
            items.add(new LeadDemotionDto.Item(user.getId(), user.getEmail(), user.getName(), user.isActive(), classification,
                    List.copyOf(squads), action, keys, keys > 0 ? apiKeysNote(keys) : null));
        }

        List<String> warnings = new ArrayList<>();
        if (leadPolicy.isLegacy()) {
            warnings.add("app.membership.lead-level=legacy: rebaixar agora REMOVE o acesso global destas contas "
                    + "(o LEAD ainda concede acesso a todas as squads neste modo).");
        }
        if (noLink > 0 && !forceDemote) {
            warnings.add(noLink + " conta(s) LEAD sem nenhum vínculo de liderança não foram rebaixadas: crie o vínculo "
                    + "ou use userId + forceDemote=true conscientemente.");
        }
        if (apply) {
            log.info("lead_demotion actor={} demoted={} kept={} overLimit={} forced={}", AuthzShadow.sanitize(actorId), demoted, kept, over, forceDemote);
        }

        LeadDemotionDto.Summary summary = new LeadDemotionDto.Summary(candidates.size(), hasLink, noLink, would, demoted, kept, over);
        return new LeadDemotionDto(apply ? LeadDemotionDto.MODE_APPLIED : LeadDemotionDto.MODE_DRY_RUN,
                leadPolicy.level().name().toLowerCase(Locale.ROOT), limit, summary, items, warnings);
    }

    private void applyDemotion(User user, TreeSet<String> squads, boolean forced, String actor, LocalDateTime now) {
        // Backup ANTES de trocar o nível (a restauração lê daqui). Upsert: V77 já copiou os LEAD existentes; quem virou LEAD depois entra aqui.
        jdbc.update("INSERT INTO users_role_lead_backup (id, role, migrated_at) VALUES (?, ?, ?) "
                + "ON CONFLICT (id) DO UPDATE SET role = EXCLUDED.role, migrated_at = EXCLUDED.migrated_at",
                user.getId(), user.getRole(), java.sql.Timestamp.valueOf(now));
        user.setRole(MEMBER);
        user.setUpdatedAt(now);
        userRepository.save(user);
        audit(forced ? "LEAD_DEMOTED_FORCED" : "LEAD_DEMOTED", actor, user,
                "papel: LEAD -> MEMBER; squads de liderança: " + (squads.isEmpty() ? "nenhuma" : String.join(",", squads)));
        evictSession(user.getId());
    }

    private User singleLead(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado."));
        if (!LEAD.equalsIgnoreCase(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A conta não é LEAD (nível atual: " + user.getRole() + ").");
        }
        return user;
    }

    private List<User> allLeads() {
        List<User> leads = new ArrayList<>(userRepository.findByRoleIgnoreCase(LEAD));
        leads.sort(Comparator.comparing((User u) -> u.getEmail() == null ? "" : u.getEmail().toLowerCase(Locale.ROOT))
                .thenComparing(User::getId));
        return leads;
    }

    /**
     * Squads com linha de liderança de cada conta. Mesma ligação do relatório e do resolvedor (PMR_U): por user_id, por
     * e-mail (sem caixa/espaços) ou por conta do Jira.
     */
    private Map<String, TreeSet<String>> leadershipSquadsOf(List<User> users) {
        Map<String, TreeSet<String>> out = new LinkedHashMap<>();
        if (users.isEmpty()) return out;
        jdbc.query(MembershipReportService.PMR_U
                        + "SELECT user_id, project_up FROM pmr_u WHERE is_leadership",
                rs -> {
                    out.computeIfAbsent(rs.getString("user_id"), k -> new TreeSet<>()).add(rs.getString("project_up"));
                });
        // só interessa quem é candidato (a consulta cobre todas as contas)
        out.keySet().retainAll(users.stream().map(User::getId).toList());
        return out;
    }

    private static String apiKeysNote(long keys) {
        return keys + " chave(s) de API ativa(s) continuam válidas com os escopos atuais até serem renovadas ou rotacionadas; "
                + "a renovação corta os escopos de liderança. Nenhuma chave foi alterada.";
    }

    // =====================================================================================================
    // Restauração
    // =====================================================================================================

    /**
     * Restaura {@code users.role} a partir de {@code users_role_lead_backup}. Só mexe em quem hoje é MEMBER (nunca
     * sobrescreve um ADMIN promovido depois). Idempotente.
     */
    @Transactional
    public LeadRestoreDto restore(String userId, String actorId) {
        boolean single = userId != null && !userId.isBlank();
        List<String> backedUp = single
                ? jdbc.queryForList("SELECT id FROM users_role_lead_backup WHERE id = ?", String.class, userId.trim())
                : jdbc.queryForList("SELECT id FROM users_role_lead_backup ORDER BY id", String.class);

        List<LeadRestoreDto.Item> items = new ArrayList<>();
        int restored = 0, skipped = 0;
        if (single && backedUp.isEmpty()) {
            User u = userRepository.findById(userId.trim()).orElse(null);
            items.add(new LeadRestoreDto.Item(userId.trim(), u == null ? null : u.getEmail(), LeadRestoreDto.SKIPPED,
                    u == null ? LeadRestoreDto.USER_NOT_FOUND : LeadRestoreDto.NOT_IN_BACKUP));
            return new LeadRestoreDto(0, 1, items);
        }

        String actor = actorLabel(actorId);
        LocalDateTime now = LocalDateTime.now();
        for (String id : backedUp) {
            User user = userRepository.findById(id).orElse(null);
            if (user == null) {
                items.add(new LeadRestoreDto.Item(id, null, LeadRestoreDto.SKIPPED, LeadRestoreDto.USER_NOT_FOUND));
                skipped++;
            } else if (LEAD.equalsIgnoreCase(user.getRole())) {
                items.add(new LeadRestoreDto.Item(id, user.getEmail(), LeadRestoreDto.SKIPPED, LeadRestoreDto.ALREADY_LEAD));
                skipped++;
            } else if (!MEMBER.equalsIgnoreCase(user.getRole())) {
                items.add(new LeadRestoreDto.Item(id, user.getEmail(), LeadRestoreDto.SKIPPED, LeadRestoreDto.CURRENT_ROLE_NOT_MEMBER));
                skipped++;
            } else {
                user.setRole(LEAD);
                user.setUpdatedAt(now);
                userRepository.save(user);
                audit("LEAD_RESTORED", actor, user, "papel: MEMBER -> LEAD (restaurado do backup)");
                evictSession(user.getId());
                items.add(new LeadRestoreDto.Item(id, user.getEmail(), LeadRestoreDto.RESTORED, null));
                restored++;
            }
        }
        log.info("lead_restore actor={} restored={} skipped={}", AuthzShadow.sanitize(actorId), restored, skipped);
        return new LeadRestoreDto(restored, skipped, items);
    }

    // =====================================================================================================
    // Auditoria e sessão
    // =====================================================================================================

    private String actorLabel(String actorId) {
        if (actorId == null) return "ADMIN";
        return userRepository.findById(actorId).map(User::getEmail).orElse(actorId);
    }

    private void audit(String action, String actor, User target, String detail) {
        auditLogRepository.save(AuditLog.builder()
                .id(UUID.randomUUID().toString())
                .action(action)
                .performedBy(actor)
                .details("Conta " + target.getEmail() + " (" + detail + ")")
                .createdAt(LocalDateTime.now())
                .build());
    }

    /** O token vivo passa a valer com o papel novo: derruba o cache do filtro JWT depois do commit. */
    private void evictSession(String userId) {
        if (sessionGuard != null) {
            AfterCommit.run(() -> sessionGuard.evict(userId));
        }
    }
}
