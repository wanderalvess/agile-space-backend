package com.agilespace.backend.service;

import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.MembershipDto;
import com.agilespace.backend.dto.UserProjectAccessDto;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.security.LeadPolicy;
import com.agilespace.backend.security.SquadLeadership;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Controle de acesso "esse usuário pertence a esse squad?" — extraído de SquadController
 * (única fonte de checagem antes) pra ser reaproveitado por qualquer endpoint que lista
 * dado por squad (poker, showcase, retro, health-check, brainstorming), que antes não
 * tinham checagem nenhuma além de autenticação genérica.
 *
 * <p><b>Etapa 2 do vínculo</b> (docs/vinculo-auditoria-e-plano.md): atrás de {@code app.membership.authz}
 * ({@link AuthzMode}, padrão {@code legacy}). Em {@code legacy} as decisões são as de sempre (código abaixo, intacto);
 * em {@code shadow} o legado decide e a regra nova roda em paralelo registrando divergências ({@link AuthzShadow});
 * em {@code new} decide a regra nova, que lê SÓ dos vínculos (project_member_roles, via {@link MembershipService}):
 * ADMIN; LEAD só com app.membership.lead-level=legacy (Etapa 4: em off não concede nada); vínculo na squad, inclusive a expansão por tribo/segmento. Deixam de dar
 * acesso: users.squad_id/default_project_id, o roster squad_members e o cargo (jobTitle) como autoridade.
 */
@Service
@Slf4j
public class SquadAccessService {

    private static final int MAX_WARNED_SQUADS = 2_000;

    private final UserRepository userRepository;
    private final SquadService squadService;
    private final UserProjectResolverService userProjectResolverService;
    /** Nulos nos testes que montam o serviço à mão: sem eles só o legado existe. */
    private final MembershipService membershipService;
    private final AuthzShadow authz;

    private final Set<String> warnedWithoutLeadership = ConcurrentHashMap.newKeySet();

    /** Etapa 4 (app.membership.lead-level). Montagem manual: legacy (LEAD concede, como antes). */
    private LeadPolicy leadPolicy = LeadPolicy.LEGACY;

    @Autowired(required = false)
    public void setLeadPolicy(LeadPolicy leadPolicy) {
        if (leadPolicy != null) this.leadPolicy = leadPolicy;
    }

    /** Só o legado (testes e montagens manuais). */
    public SquadAccessService(UserRepository userRepository, SquadService squadService,
                              UserProjectResolverService userProjectResolverService) {
        this(userRepository, squadService, userProjectResolverService, null, null);
    }

    @Autowired
    public SquadAccessService(UserRepository userRepository, SquadService squadService,
                              UserProjectResolverService userProjectResolverService,
                              MembershipService membershipService, AuthzShadow authz) {
        this.userRepository = userRepository;
        this.squadService = squadService;
        this.userProjectResolverService = userProjectResolverService;
        this.membershipService = membershipService;
        this.authz = authz;
    }

    /** Modo em vigor; sem a chave montada (testes antigos) é {@link AuthzMode#LEGACY}. */
    public AuthzMode authzMode() {
        return authz == null || membershipService == null ? AuthzMode.LEGACY : authz.mode();
    }

    private boolean legacyOnly() {
        return authzMode() == AuthzMode.LEGACY;
    }

    // =====================================================================================================
    // matchesSquad
    // =====================================================================================================

    /**
     * Núcleo comum de leitura E escrita: ADMIN (LEAD só em lead-level=legacy), ou um caller já vinculado a esta squad
     * (User.squadId/defaultProjectId, papel em projeto resolvido, ou membro no roster
     * squad_members). Não tem efeito colateral nenhum — nunca grava nada.
     * <p>Em {@code app.membership.authz=new}: ADMIN (LEAD só em lead-level=legacy), ou vínculo (project_member_roles) nesta squad.
     */
    public boolean matchesSquad(String squadId, HttpServletRequest request) {
        return matchesSquad(squadId,
                (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID),
                (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE));
    }

    /** Mesma checagem sem HttpServletRequest (ex.: handshake de WebSocket, onde só há userId/role do JWT). */
    public boolean matchesSquad(String squadId, String userId, String role) {
        if (legacyOnly()) {
            return legacyMatchesSquad(squadId, userId, role);
        }
        return authz.decide(AuthzRules.CHECK_MATCHES, userId, squadId,
                () -> legacyMatchesSquad(squadId, userId, role),
                () -> newMatchesSquad(squadId, userId, role),
                legacyAllowed -> explain(AuthzRules.CHECK_MATCHES, userId, squadId, legacyAllowed));
    }

    private boolean legacyMatchesSquad(String squadId, String userId, String role) {
        if (leadPolicy.isPrivileged(role)) {
            return true;
        }
        User caller = userId != null ? userRepository.findById(userId).orElse(null) : null;
        if (caller == null) {
            return false;
        }

        // 0. Papel administrativo no registro do banco (caso o token JWT não esteja atualizado)
        if (leadPolicy.isPrivileged(caller.getRole())) {
            return true;
        }

        // 1+2. Checagem direta por squadId ou defaultProjectId (com o alias DDWMISSI <-> MISSI)
        boolean matches = sessionSquadMatches(caller, squadId);

        // 0.1 Cargos de liderança/governança de squad — só vale se o caller já pertence a este squad
        // (senão qualquer usuário autodeclarando jobTitle de liderança ganharia acesso a squads alheios)
        if (matches && SquadLeadership.isLeadershipJobTitle(caller.getJobTitle())) {
            return true;
        }
        if (matches) {
            return true;
        }

        // 3. Checagem através dos projetos resolvidos pelo UserProjectResolverService
        if (userProjectResolverService != null) {
            UserProjectAccessDto access = userProjectResolverService.resolveUserAccess(caller);
            if (access != null) {
                // Liderança transversal (PL, Tribe Lead, Agile Coach) já vem com a tribo/segmento inteiro
                // expandido em access.getProjects(). Antes, ser transversal em QUALQUER projeto liberava
                // TODAS as squads do banco, inclusive de outras tribos.
                if (access.getProjects() != null) {
                    matches = access.getProjects().stream().anyMatch(p ->
                            p.getProjectId().equalsIgnoreCase(squadId)
                            || (("DDWMISSI".equalsIgnoreCase(squadId) || "MISSI".equalsIgnoreCase(squadId))
                                && ("DDWMISSI".equalsIgnoreCase(p.getProjectId()) || "MISSI".equalsIgnoreCase(p.getProjectId())))
                    );
                    if (matches) return true;
                }
            }
        }

        // 4. Checagem se o usuário é membro registrado desta squad na tabela squad_members
        return rosterMatches(caller, squadId);
    }

    /**
     * default_project_id (a squad ativa preferida) aponta para a squad (comparação sem caixa, com o alias DDWMISSI/MISSI).
     * Etapa 5 do vínculo: users.squad_id deixou de ser lido (a coluna foi aposentada); no legado ele era gravado junto
     * com default_project_id, então esta é a mesma "squad da sessão" de antes.
     */
    static boolean sessionSquadMatches(User caller, String squadId) {
        boolean matches = caller.getDefaultProjectId() != null && caller.getDefaultProjectId().equalsIgnoreCase(squadId);
        if (!matches && ("DDWMISSI".equalsIgnoreCase(squadId) || "MISSI".equalsIgnoreCase(squadId))) {
            matches = "DDWMISSI".equalsIgnoreCase(caller.getDefaultProjectId()) || "MISSI".equalsIgnoreCase(caller.getDefaultProjectId());
        }
        return matches;
    }

    /** Membro do roster squad_members (vaga reivindicada, e-mail ou conta do Jira; nome nunca conta), com o alias. */
    private boolean rosterMatches(User caller, String squadId) {
        List<SquadMember> members = squadService.getMembers(squadId);
        if ("DDWMISSI".equalsIgnoreCase(squadId) || "MISSI".equalsIgnoreCase(squadId)) {
            String aliasSquad = "DDWMISSI".equalsIgnoreCase(squadId) ? "MISSI" : "DDWMISSI";
            List<SquadMember> aliasMembers = squadService.getMembers(aliasSquad);
            if (aliasMembers != null && !aliasMembers.isEmpty()) {
                List<SquadMember> combined = new ArrayList<>(members != null ? members : List.of());
                combined.addAll(aliasMembers);
                members = combined;
            }
        }
        if (members != null && !members.isEmpty()) {
            // Nome NÃO conta: User.name é editável pela própria pessoa, e um homônimo de alguém do roster
            // ganharia acesso à squad. Vale a vaga reivindicada ("Sou eu"), o e-mail ou a conta do Jira.
            return members.stream().anyMatch(m ->
                    (m.getClaimedByUid() != null && m.getClaimedByUid().equals(caller.getId()))
                    || (caller.getEmail() != null && m.getEmail() != null && caller.getEmail().trim().equalsIgnoreCase(m.getEmail().trim()))
                    || (caller.getJiraAccountId() != null && m.getJiraAccountId() != null && caller.getJiraAccountId().trim().equalsIgnoreCase(m.getJiraAccountId().trim()))
            );
        }
        return false;
    }

    private boolean newMatchesSquad(String squadId, String userId, String role) {
        if (isPrivileged(role)) {
            return true;
        }
        User caller = userId != null ? userRepository.findById(userId).orElse(null) : null;
        if (caller == null) {
            return false;
        }
        if (isPrivileged(caller.getRole())) {
            return true;
        }
        return membershipService.membershipIn(caller, squadId) != null;
    }

    // =====================================================================================================
    // canManageSquad
    // =====================================================================================================

    /**
     * Gestão da squad (config, roster, capacidade, dado nominal por pessoa): admin, ou quem pertence à
     * squad E tem papel de liderança nela (cargo de liderança, ou papel de liderança no cadastro do projeto,
     * inclusive a liderança transversal da tribo).
     * <p>
     * Squad que ainda não tem NENHUMA liderança cadastrada (projeto importado sem Profields, ambiente novo)
     * mantém a regra antiga: qualquer membro gerencia. Sem isso ninguém conseguiria configurar a squad.
     * <p>
     * Em {@code app.membership.authz=new}: ADMIN (LEAD só em lead-level=legacy), ou vínculo com {@code is_leadership} na squad (inclusive a
     * liderança transversal); o cargo (jobTitle) deixa de valer. O fallback "sem liderança cadastrada" passa a exigir
     * VÍNCULO na squad (não mais qualquer membro do roster/users.squad_id) e é o bootstrap de ambiente novo: cada
     * squad nessa situação gera um aviso {@code authz_squad_without_leadership} (uma vez por squad e processo) e
     * aparece no relatório como SQUADS_WITHOUT_LEADERSHIP.
     */
    public boolean canManageSquad(String squadId, HttpServletRequest request) {
        return canManageSquad(squadId,
                (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID),
                (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE));
    }

    public boolean canManageSquad(String squadId, String userId, String role) {
        if (legacyOnly()) {
            return legacyCanManageSquad(squadId, userId, role);
        }
        return authz.decide(AuthzRules.CHECK_MANAGE, userId, squadId,
                () -> legacyCanManageSquad(squadId, userId, role),
                () -> newCanManageSquad(squadId, userId, role),
                legacyAllowed -> explain(AuthzRules.CHECK_MANAGE, userId, squadId, legacyAllowed));
    }

    private boolean legacyCanManageSquad(String squadId, String userId, String role) {
        if (leadPolicy.isPrivileged(role)) {
            return true;
        }
        User caller = userId != null ? userRepository.findById(userId).orElse(null) : null;
        if (caller == null) {
            return false;
        }
        if (leadPolicy.isPrivileged(caller.getRole())) {
            return true;
        }
        if (!legacyMatchesSquad(squadId, userId, role)) {
            return false;
        }
        if (SquadLeadership.isLeadershipJobTitle(caller.getJobTitle())) {
            return true;
        }
        if (userProjectResolverService == null) {
            return false;
        }
        UserProjectAccessDto access = userProjectResolverService.resolveUserAccess(caller);
        if (access != null && access.getProjects() != null
                && access.getProjects().stream().anyMatch(p -> p.isLeadership() && sameSquad(p.getProjectId(), squadId))) {
            return true;
        }
        return !hasRegisteredLeadership(squadId);
    }

    private boolean newCanManageSquad(String squadId, String userId, String role) {
        if (isPrivileged(role)) {
            return true;
        }
        User caller = userId != null ? userRepository.findById(userId).orElse(null) : null;
        if (caller == null) {
            return false;
        }
        if (isPrivileged(caller.getRole())) {
            return true;
        }
        MembershipDto membership = membershipService.membershipIn(caller, squadId);
        if (membership == null) {
            return false;
        }
        if (membership.leadership()) {
            return true;
        }
        if (hasRegisteredLeadership(squadId)) {
            return false;
        }
        // Bootstrap de ambiente novo: a squad não tem liderança cadastrada, então quem TEM VÍNCULO nela configura.
        warnSquadWithoutLeadership(squadId);
        return true;
    }

    private void warnSquadWithoutLeadership(String squadId) {
        String key = squadId.trim().toUpperCase(Locale.ROOT);
        if (warnedWithoutLeadership.size() < MAX_WARNED_SQUADS && warnedWithoutLeadership.add(key)) {
            log.warn("authz_squad_without_leadership squad={} detail=gestao_liberada_a_quem_tem_vinculo "
                    + "(bootstrap: cadastre a liderança; ver SQUADS_WITHOUT_LEADERSHIP em /api/admin/membership/report)",
                    AuthzShadow.sanitize(squadId));
        }
    }

    // =====================================================================================================
    // isSquadLeadership
    // =====================================================================================================

    /**
     * Liderança ESTRITA da squad (sem a regra "squad sem liderança cadastrada: qualquer membro"):
     * ADMIN, ou membro da squad com cargo de liderança, ou linha de liderança no roster. Usada onde a
     * regra pede liderança de verdade (ex.: editar documentos da Base de Conhecimento).
     * <p>Em {@code app.membership.authz=new}: ADMIN, ou vínculo com {@code is_leadership} na squad (o cargo não vale).
     */
    public boolean isSquadLeadership(String squadId, String userId, String role) {
        if (squadId == null || squadId.isBlank() || userId == null) {
            return false;
        }
        if (legacyOnly()) {
            return legacyIsSquadLeadership(squadId, userId, role);
        }
        return authz.decide(AuthzRules.CHECK_LEADERSHIP, userId, squadId,
                () -> legacyIsSquadLeadership(squadId, userId, role),
                () -> newIsSquadLeadership(squadId, userId, role),
                legacyAllowed -> explain(AuthzRules.CHECK_LEADERSHIP, userId, squadId, legacyAllowed));
    }

    private boolean legacyIsSquadLeadership(String squadId, String userId, String role) {
        if ("ADMIN".equalsIgnoreCase(role)) {
            return true;
        }
        User caller = userRepository.findById(userId).orElse(null);
        if (caller == null) {
            return false;
        }
        if ("ADMIN".equalsIgnoreCase(caller.getRole())) {
            return true;
        }
        if (!legacyMatchesSquad(squadId, userId, role)) {
            return false;
        }
        if (SquadLeadership.isLeadershipJobTitle(caller.getJobTitle())) {
            return true;
        }
        if (userProjectResolverService == null) {
            return false;
        }
        UserProjectAccessDto access = userProjectResolverService.resolveUserAccess(caller);
        return access != null && access.getProjects() != null
                && access.getProjects().stream().anyMatch(p -> p.isLeadership() && sameSquad(p.getProjectId(), squadId));
    }

    private boolean newIsSquadLeadership(String squadId, String userId, String role) {
        if ("ADMIN".equalsIgnoreCase(role)) {
            return true;
        }
        User caller = userRepository.findById(userId).orElse(null);
        if (caller == null) {
            return false;
        }
        if ("ADMIN".equalsIgnoreCase(caller.getRole())) {
            return true;
        }
        MembershipDto membership = membershipService.membershipIn(caller, squadId);
        return membership != null && membership.leadership();
    }

    /** True se o cadastro do projeto (ou o alias DDWMISSI/MISSI) já tem alguma pessoa de liderança. */
    private boolean hasRegisteredLeadership(String squadId) {
        if (userProjectResolverService.hasRegisteredLeadership(squadId)) {
            return true;
        }
        if ("DDWMISSI".equalsIgnoreCase(squadId) || "MISSI".equalsIgnoreCase(squadId)) {
            return userProjectResolverService.hasRegisteredLeadership("DDWMISSI".equalsIgnoreCase(squadId) ? "MISSI" : "DDWMISSI");
        }
        return false;
    }

    private static boolean sameSquad(String projectId, String squadId) {
        if (projectId == null || squadId == null) return false;
        if (projectId.equalsIgnoreCase(squadId)) return true;
        return ("DDWMISSI".equalsIgnoreCase(squadId) || "MISSI".equalsIgnoreCase(squadId))
                && ("DDWMISSI".equalsIgnoreCase(projectId) || "MISSI".equalsIgnoreCase(projectId));
    }

    /**
     * Leitura: qualquer membro real da squad (ou admin/liderança) — nunca auto-vincula.
     */
    public void requireSquadReadAccess(String squadId, HttpServletRequest request) {
        if (!matchesSquad(squadId, request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a membros desta squad ou administradores.");
        }
    }

    // =====================================================================================================
    // Peças reaproveitadas pelos outros pontos migrados (convite, público, Jolt, chave de API, Conhecimento...)
    // =====================================================================================================

    /** Regra NOVA: a pessoa tem vínculo (project_member_roles, inclusive tribo/segmento) nesta squad. Sem ADMIN/LEAD. */
    public boolean isLinkedToSquad(User caller, String squadId) {
        return membershipService != null && membershipService.membershipIn(caller, squadId) != null;
    }

    /** Regra NOVA: liderança em linha PRÓPRIA da squad (escopo SQUAD; a expansão por tribo não conta). Sem ADMIN/LEAD. */
    public boolean hasDirectLeadership(User caller, String squadId) {
        if (membershipService == null) return false;
        MembershipDto m = membershipService.membershipIn(caller, squadId);
        return m != null && m.leadership() && MembershipDto.SCOPE_SQUAD.equals(m.scope());
    }

    /**
     * Etapa 4: liderança por VÍNCULO na squad (project_member_roles com {@code is_leadership}, direta ou por expansão de
     * tribo/segmento), sem considerar ADMIN/LEAD, cargo (jobTitle), roster nem users.squad_id. Usada onde LEAD deixou de
     * conceder poder (ex.: facilitador do Poker) e quem lidera a squad assume o papel.
     */
    public boolean hasLeadershipLink(String userId, String squadId) {
        if (membershipService == null || userId == null || squadId == null || squadId.isBlank()) {
            return false;
        }
        User caller = userRepository.findById(userId).orElse(null);
        if (caller == null) {
            return false;
        }
        MembershipDto m = membershipService.membershipIn(caller, squadId);
        return m != null && m.leadership();
    }

    /**
     * Squad "do usuário" para quem precisa de UMA (autoria de documento, chave de API, atribuição do Prompt Hub).
     * Legado: {@code users.default_project_id} (a coluna squad_id foi aposentada). Novo: a squad ativa validada contra
     * os vínculos (null sem vínculo nenhum).
     * Em {@code shadow} devolve o legado e registra a diferença com {@code check} no log.
     */
    public String activeSquadFor(String check, User user) {
        if (user == null) {
            return null;
        }
        if (legacyOnly()) {
            return user.getDefaultProjectId();
        }
        return authz.choose(check, user.getId(),
                user::getDefaultProjectId,
                () -> membershipService.activeProjectOf(user),
                (legacy, neu) -> {
                    boolean hadLegacy = legacy != null && !legacy.isBlank();
                    if (hadLegacy && !MembershipService.sameSquad(legacy, neu)) {
                        authz.record(check, user.getId(), legacy, true, false, neu == null ? "NO_MEMBERSHIP" : "ACTIVE_SQUAD_DIFFERENT");
                    } else if (!hadLegacy && neu != null) {
                        authz.record(check, user.getId(), neu, false, true, AuthzRules.NEW_ONLY_MEMBERSHIP);
                    }
                });
    }

    /**
     * Squads com as quais a pessoa pode compartilhar/ler algo "da squad" (Jolt). Retorna {@code null} quando quem decide
     * ainda é o legado (a squad única de users.default_project_id, que o chamador já tem); só em {@code new} devolve a lista
     * (ids canônicos dos vínculos). Em {@code shadow} registra o que cada lado deixaria de fora.
     */
    public Set<String> squadsForSharing(String check, User user) {
        if (user == null || legacyOnly()) {
            return null;
        }
        Set<String> result = authz.choose(check, user.getId(),
                () -> legacyShareSet(user),
                () -> newShareSet(user),
                (legacy, neu) -> compareSquadSets(check, user, legacy, neu));
        return authz.mode() == AuthzMode.NEW ? result : null;
    }

    private static Set<String> legacyShareSet(User user) {
        Set<String> out = new LinkedHashSet<>();
        if (user.getDefaultProjectId() != null && !user.getDefaultProjectId().isBlank()) out.add(user.getDefaultProjectId());
        return out;
    }

    private Set<String> newShareSet(User user) {
        Set<String> out = new LinkedHashSet<>();
        for (MembershipDto m : membershipService.membershipsOf(user)) out.add(m.projectId());
        return out;
    }

    /** Registra cada squad que só um dos dois lados tem (sem caixa, com o alias). */
    public void compareSquadSets(String check, User user, Set<String> legacy, Set<String> neu) {
        for (String s : legacy) {
            if (neu.stream().noneMatch(n -> MembershipService.sameSquad(n, s))) {
                authz.recordLazy(check, user.getId(), s, true, false, () -> lossReasonFor(user, s));
            }
        }
        for (String s : neu) {
            if (legacy.stream().noneMatch(l -> MembershipService.sameSquad(l, s))) {
                authz.record(check, user.getId(), s, false, true, AuthzRules.NEW_ONLY_MEMBERSHIP);
            }
        }
    }

    /** Motivo categórico de a regra nova não reconhecer o acesso que o legado dava (users.squad_id, roster ou os dois). */
    public String lossReasonFor(User user, String squadId) {
        try {
            return AuthzRules.matchesLossReason(factsFor(user, squadId));
        } catch (RuntimeException e) {
            return "UNKNOWN";
        }
    }

    // =====================================================================================================
    // Motivo da divergência (só roda quando o legado e o novo discordam)
    // =====================================================================================================

    private String explain(String check, String userId, String squadId, boolean legacyAllowed) {
        User caller = userId != null ? userRepository.findById(userId).orElse(null) : null;
        if (caller == null) {
            return "UNKNOWN";
        }
        return AuthzRules.reason(check, factsFor(caller, squadId), legacyAllowed);
    }

    /** Levanta os fatos do par (usuário, squad) com as mesmas fontes das decisões; usado para explicar e para testar. */
    AuthzRules.Facts factsFor(User caller, String squadId) {
        boolean session = sessionSquadMatches(caller, squadId);
        boolean roster = squadService != null && rosterMatches(caller, squadId);
        boolean jobTitle = SquadLeadership.isLeadershipJobTitle(caller.getJobTitle());
        boolean legacyLinked = false;
        boolean legacyLeader = false;
        if (userProjectResolverService != null) {
            UserProjectAccessDto access = userProjectResolverService.resolveUserAccess(caller);
            if (access != null && access.getProjects() != null) {
                for (UserProjectAccessDto.ProjectAccessItem p : access.getProjects()) {
                    if (sameSquad(p.getProjectId(), squadId)) {
                        legacyLinked = true;
                        if (p.isLeadership()) legacyLeader = true;
                    }
                }
            }
        }
        MembershipDto m = membershipService == null ? null : membershipService.membershipIn(caller, squadId);
        boolean hasLeadership = userProjectResolverService != null && hasRegisteredLeadership(squadId);
        return new AuthzRules.Facts(session, roster, jobTitle, legacyLinked, legacyLeader,
                m != null, m != null && m.leadership(), hasLeadership);
    }

    /** ADMIN, e LEAD só com {@code app.membership.lead-level=legacy} (Etapa 4). */
    private boolean isPrivileged(String role) {
        return leadPolicy.isPrivileged(role);
    }

    /** Política do nível LEAD em vigor (legacy nas montagens manuais). */
    public LeadPolicy leadPolicy() {
        return leadPolicy;
    }
}
