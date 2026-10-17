package com.agilespace.backend.service;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.MembershipDto;
import com.agilespace.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Quem pode o quê na Review (docs/ceremony-review-api.md), tudo por VÍNCULO ({@link MembershipService}), nunca pelo cargo
 * autodeclarado (jobTitle):
 * <ul>
 *   <li><b>decidir</b> (veredito e feedback de um card): ADMIN, liderança da squad da Review, ou vínculo na squad com o papel
 *       Product Owner ou SME. Review sem squad: ADMIN e o criador;</li>
 *   <li><b>conduzir</b> (apresentador, apagar consulta JQL da squad): ADMIN ou liderança da squad.</li>
 * </ul>
 * {@code app.showcase.decision-lock=off} desliga SÓ a trava de decisão (volta ao comportamento anterior); o padrão é {@code enforce}.
 */
@Component
public class ShowcaseAccessPolicy {

    static final Set<String> DECIDER_ROLE_KEYS = Set.of("PRODUCT_OWNER", "SME");
    /** Mesmos rótulos que o cliente aceita em {@code canDecide} (review-room-logic.ts), sem caixa. */
    static final Set<String> DECIDER_ROLE_NAMES = Set.of("product owner", "product owner (po)", "sme");

    private final UserRepository userRepository;
    private final MembershipService membershipService;
    private final boolean enforceDecisionLock;

    @Autowired
    public ShowcaseAccessPolicy(UserRepository userRepository, MembershipService membershipService,
                                @Value("${app.showcase.decision-lock:enforce}") String decisionLock) {
        this.userRepository = userRepository;
        this.membershipService = membershipService;
        this.enforceDecisionLock = !"off".equalsIgnoreCase(decisionLock == null ? "" : decisionLock.trim());
    }

    public boolean decisionLockEnforced() {
        return enforceDecisionLock;
    }

    /** Pode registrar veredito/feedback nos cards de uma Review desta squad? */
    public boolean canDecide(String squadId, String reviewCreatorId, String userId) {
        if (!enforceDecisionLock) {
            return true;
        }
        if (userId == null || userId.isBlank()) {
            return false;
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return false;
        }
        if ("ADMIN".equalsIgnoreCase(user.getRole())) {
            return true;
        }
        if (squadId == null || squadId.isBlank()) {
            return userId.equals(reviewCreatorId);
        }
        MembershipDto m = membershipService.membershipIn(user, squadId);
        return m != null && (anyLeadership(m) || hasDeciderRole(m));
    }

    /** ADMIN ou liderança (por vínculo) da squad. */
    public boolean isAdminOrSquadLeader(String squadId, String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return false;
        }
        if ("ADMIN".equalsIgnoreCase(user.getRole())) {
            return true;
        }
        if (squadId == null || squadId.isBlank()) {
            return false;
        }
        MembershipDto m = membershipService.membershipIn(user, squadId);
        return m != null && anyLeadership(m);
    }

    private static boolean anyLeadership(MembershipDto m) {
        if (m.leadership()) {
            return true;
        }
        return m.roles() != null && m.roles().stream().anyMatch(MembershipDto.RoleDto::leadership);
    }

    private static boolean hasDeciderRole(MembershipDto m) {
        if (isDecider(m.roleKey(), m.roleName())) {
            return true;
        }
        return m.roles() != null && m.roles().stream().anyMatch(r -> isDecider(r.roleKey(), r.roleName()));
    }

    private static boolean isDecider(String roleKey, String roleName) {
        if (roleKey != null && DECIDER_ROLE_KEYS.contains(roleKey.trim().toUpperCase(Locale.ROOT))) {
            return true;
        }
        return roleName != null && DECIDER_ROLE_NAMES.contains(roleName.trim().toLowerCase(Locale.ROOT));
    }
}
