package com.agilespace.backend.service;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.MembershipDto;
import com.agilespace.backend.dto.MembershipShadowDiffDto;
import com.agilespace.backend.dto.UserProjectAccessDto;
import com.agilespace.backend.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Modo sombra do vínculo: compara o cálculo legado ({@link UserProjectResolverService}) com o novo
 * ({@link MembershipService}) sem mudar o que a sessão devolve.
 *
 * <ul>
 *   <li>Em tempo real ({@link #record}): chamado a cada resposta de sessão com {@code app.membership.mode=shadow};
 *       cada divergência vira uma linha de log estruturada ({@code membership_divergence ...}, só ids) e soma no
 *       contador Micrometer {@code membership.divergence} (tag {@code type}).</li>
 *   <li>Sob demanda ({@link #report}): recalcula legado x novo para TODOS os usuários ativos, sem escrever nada
 *       (transação readOnly) e sem usar o cache.</li>
 * </ul>
 */
@Service
@Slf4j
public class MembershipShadowService {

    /** Tipos de divergência (chaves estáveis em log, métrica e relatório). */
    public enum Type {
        /** Squad que o legado devolve e o novo não. */
        ONLY_LEGACY,
        /** Squad que o novo devolve e o legado não. */
        ONLY_NEW,
        /** Mesma squad, papel primário/liderança/forma de acesso diferentes. */
        ROLE_DIFFERENT,
        /** A liderança transversal (acesso à tribo/segmento) não bate. */
        TRANSVERSAL_DIFFERENT,
        /** A squad ativa da sessão não é a mesma. */
        ACTIVE_PROJECT_DIFFERENT
    }

    public record Divergence(Type type, String squad, String detail) { }

    static final int SAMPLE_LIMIT = 20;
    static final String METRIC = "membership.divergence";

    private final UserProjectResolverService legacy;
    private final MembershipService membership;
    private final UserRepository userRepository;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    public MembershipShadowService(UserProjectResolverService legacy, MembershipService membership,
                                   UserRepository userRepository, ObjectProvider<MeterRegistry> meterRegistry) {
        this.legacy = legacy;
        this.membership = membership;
        this.userRepository = userRepository;
        this.meterRegistry = meterRegistry;
    }

    // ---------- comparação pura ----------

    /**
     * @param legacyActive squad ativa que a sessão legada devolveria (users.default_project_id sem validar, senão a primária)
     */
    static List<Divergence> compare(UserProjectAccessDto legacyAccess, String legacyActive, User user, MembershipService.View neu) {
        List<Divergence> out = new ArrayList<>();

        Map<String, UserProjectAccessDto.ProjectAccessItem> old = new TreeMap<>();
        if (legacyAccess.getProjects() != null) {
            for (UserProjectAccessDto.ProjectAccessItem p : legacyAccess.getProjects()) {
                old.put(p.getProjectId().toUpperCase(Locale.ROOT), p);
            }
        }
        Map<String, MembershipDto> fresh = new TreeMap<>();
        for (MembershipDto m : neu.memberships()) {
            fresh.put(m.projectId().toUpperCase(Locale.ROOT), m);
        }

        for (String key : new TreeSet<>(old.keySet())) {
            UserProjectAccessDto.ProjectAccessItem o = old.get(key);
            MembershipDto n = fresh.get(key);
            if (n == null) {
                out.add(new Divergence(Type.ONLY_LEGACY, o.getProjectId(), o.isDirectAssignment() ? "direto" : "transversal"));
                continue;
            }
            List<String> what = new ArrayList<>();
            if (!eq(o.getRoleKey(), n.roleKey())) what.add("roleKey");
            if (o.isLeadership() != n.leadership()) what.add("leadership");
            if (o.isDirectAssignment() != n.direct()) what.add("direct");
            if (!what.isEmpty()) {
                out.add(new Divergence(Type.ROLE_DIFFERENT, o.getProjectId(), String.join("+", what)));
            }
        }
        for (String key : new TreeSet<>(fresh.keySet())) {
            if (!old.containsKey(key)) {
                MembershipDto n = fresh.get(key);
                out.add(new Divergence(Type.ONLY_NEW, n.projectId(), n.direct() ? "direto" : "transversal"));
            }
        }

        if (legacyAccess.isTransversalLeader() != neu.transversalLeader()) {
            out.add(new Divergence(Type.TRANSVERSAL_DIFFERENT, null, neu.transversalLeader() ? "so_no_novo" : "so_no_legado"));
        }

        if (!eq(legacyActive, neu.activeProjectId())) {
            String pref = user.getDefaultProjectId();
            String detail;
            if (neu.activeProjectId() == null) {
                detail = "sem_vinculos";
            } else if (pref != null && !pref.isBlank() && fresh.keySet().stream().noneMatch(k -> k.equalsIgnoreCase(pref.trim()))) {
                detail = "preferencia_fora_dos_vinculos";
            } else if (pref == null || pref.isBlank()) {
                detail = "sem_preferencia";
            } else {
                detail = "outro";
            }
            out.add(new Divergence(Type.ACTIVE_PROJECT_DIFFERENT, neu.activeProjectId() != null ? neu.activeProjectId() : legacyActive, detail));
        }
        return out;
    }

    private static boolean eq(String a, String b) {
        if (a == null || a.isBlank()) return b == null || b.isBlank();
        return b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    // ---------- tempo real (modo shadow) ----------

    /**
     * Compara e registra. Nunca lança: o modo sombra não pode derrubar login nem /me.
     *
     * @return as divergências encontradas (vazia = idênticos)
     */
    public List<Divergence> record(User user, UserProjectAccessDto legacyAccess, String legacyActive) {
        try {
            MembershipService.View neu = membership.forUser(user);
            List<Divergence> diffs = compare(legacyAccess, legacyActive, user, neu);
            for (Divergence d : diffs) {
                emit(user.getId(), d);
            }
            return diffs;
        } catch (RuntimeException e) {
            log.warn("membership_shadow_error user={} erro={}", user.getId(), e.toString());
            return List.of();
        }
    }

    private void emit(String userId, Divergence d) {
        String line = "membership_divergence user={} type={} squad={} detail={}";
        // Divergência de acesso (quem vê o quê) é WARN; diferença só de squad ativa/transversal é INFO.
        if (d.type() == Type.ACTIVE_PROJECT_DIFFERENT) {
            log.info(line, userId, d.type(), d.squad(), d.detail());
        } else {
            log.warn(line, userId, d.type(), d.squad(), d.detail());
        }
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            registry.counter(METRIC, "type", d.type().name()).increment();
        }
    }

    // ---------- relatório sob demanda ----------

    /** Recalcula legado x novo para todos os usuários ativos. Somente leitura; não usa nem popula o cache. */
    @Transactional(readOnly = true)
    public MembershipShadowDiffDto report() {
        Map<Type, Integer> totals = new EnumMap<>(Type.class);
        for (Type t : Type.values()) totals.put(t, 0);
        List<MembershipShadowDiffDto.Sample> sample = new ArrayList<>();
        int checked = 0, withDiff = 0, failed = 0;
        boolean truncated = false;

        List<User> users = userRepository.findAll().stream().filter(User::isActive)
                .sorted((a, b) -> a.getId().compareTo(b.getId())).toList();
        for (User user : users) {
            checked++;
            try {
                UserProjectAccessDto old = legacy.resolveUserAccess(user);
                String legacyActive = user.getDefaultProjectId() != null ? user.getDefaultProjectId() : old.getPrimaryProjectId();
                List<Divergence> diffs = compare(old, legacyActive, user, membership.computeFresh(user));
                if (!diffs.isEmpty()) withDiff++;
                for (Divergence d : diffs) {
                    totals.merge(d.type(), 1, Integer::sum);
                    if (sample.size() < SAMPLE_LIMIT) {
                        sample.add(new MembershipShadowDiffDto.Sample(user.getId(), d.squad(), d.type().name(), d.detail()));
                    } else {
                        truncated = true;
                    }
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("membership_shadow_error user={} erro={}", user.getId(), e.toString());
            }
        }

        Map<String, Integer> totalsByName = new LinkedHashMap<>();
        totals.forEach((k, v) -> totalsByName.put(k.name(), v));
        return new MembershipShadowDiffDto(Instant.now(), membership.mode().name().toLowerCase(Locale.ROOT), SAMPLE_LIMIT,
                checked, withDiff, failed, totalsByName, sample, truncated);
    }
}
