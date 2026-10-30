package com.agilespace.backend.service;

import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.domain.UserJiraConfig;
import com.agilespace.backend.domain.UserRole;
import com.agilespace.backend.domain.UserTdnConfig;
import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.repository.SquadMemberRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.repository.UserJiraConfigRepository;
import com.agilespace.backend.repository.UserTdnConfigRepository;
import com.agilespace.backend.security.JiraAccountIdGuard;
import com.agilespace.backend.security.LeadPolicy;
import com.agilespace.backend.security.UserSessionGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserJiraConfigRepository jiraConfigRepository;

    @Autowired
    private UserTdnConfigRepository tdnConfigRepository;

    @Autowired
    private SquadMemberRepository squadMemberRepository;

    @Autowired(required = false)
    private JiraAccountIdGuard jiraAccountIdGuard;

    @Autowired(required = false)
    private UserSessionGuard sessionGuard;

    @Autowired(required = false)
    private AuditLogRepository auditLogRepository;

    /** Etapa 4 (app.membership.lead-level): com off o nível LEAD deixa de ser atribuível. Montagem manual: legacy. */
    private LeadPolicy leadPolicy = LeadPolicy.LEGACY;

    @Autowired(required = false)
    public void setLeadPolicy(LeadPolicy leadPolicy) {
        if (leadPolicy != null) this.leadPolicy = leadPolicy;
    }

    /** Fonte da squad ativa validada (e dos vínculos) exposta no JSON. Opcional: sem ele só vale default_project_id. */
    @Autowired(required = false)
    private MembershipService membershipService;

    /**
     * Lista (ADMIN). {@code squadId} do JSON = a preferência {@code default_project_id}, sem validar contra os vínculos
     * (evita ~3 consultas por pessoa; é só rótulo de exibição). A coluna users.squad_id foi aposentada (Etapa 5).
     */
    @Transactional(readOnly = true)
    public List<User> getAllUsers() {
        List<User> all = userRepository.findAll();
        for (User u : all) {
            u.setActiveSquadId(blankToNull(u.getDefaultProjectId()));
        }
        return all;
    }

    @Transactional(readOnly = true)
    public User getUser(String id) {
        User user = userRepository.findById(id).orElse(null);
        return withActiveSquad(user);
    }

    /**
     * Preenche o {@code squadId} somente-leitura do JSON com a squad ativa VALIDADA contra os vínculos
     * ({@link MembershipService#activeProjectOf}); sem o serviço (montagens manuais), a preferência default_project_id.
     * Nunca persiste: o campo é @Transient.
     */
    private User withActiveSquad(User user) {
        if (user == null) return null;
        String active = membershipService != null ? membershipService.activeProjectOf(user) : user.getDefaultProjectId();
        user.setActiveSquadId(blankToNull(active));
        return user;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * Atualiza o perfil de um usuário já existente. Aceita apenas os campos de perfil
     * (nunca role/active/passwordHash/authProvider/email/ssoId a partir do corpo da requisição)
     * para evitar mass assignment; role/active/defaultProjectId (autorização/acesso a projeto)
     * só são aplicados quando isAdmin=true. jobTitle ENTRA em checagem de autorização
     * (InviteController e SquadLeadership usam jobTitle de liderança), então só ADMIN o altera.
     * {@code squadId} recebido no corpo é IGNORADO (Etapa 5 do vínculo: users.squad_id foi aposentada e a squad ativa só
     * troca por /auth/switch-project, que valida o vínculo): cliente antigo que ainda o envia não recebe erro.
     * Campos omitidos no corpo da requisição (null) preservam o valor já existente.
     * Retorna null se o usuário não existir.
     */
    @Transactional
    public User saveUser(User incoming, boolean isAdmin) {
        return saveUser(incoming, isAdmin, null);
    }

    /**
     * Mesma regra, com o id de quem está salvando ({@code actorId}) para as guardas do painel de gestão:
     * ninguém tira o próprio acesso de admin nem desativa a si mesmo, e o último admin ativo não é rebaixado
     * nem desativado. Papel e ativo só mudam quando o corpo os trouxe de fato (ver User.roleExplicit).
     * Mudanças de papel, cargo e ativo por admin vão para a auditoria.
     */
    @Transactional
    public User saveUser(User incoming, boolean isAdmin, String actorId) {
        User existing = userRepository.findById(incoming.getId()).orElse(null);
        if (existing == null) {
            return null;
        }

        if (incoming.getName() != null) existing.setName(incoming.getName());
        if (incoming.getAvatarSeed() != null) existing.setAvatarSeed(incoming.getAvatarSeed());
        if (incoming.getDailyHours() != null) existing.setDailyHours(incoming.getDailyHours());
        if (incoming.getJiraAccountId() != null
                && !incoming.getJiraAccountId().trim().equals(existing.getJiraAccountId() == null ? "" : existing.getJiraAccountId().trim())) {
            // O id do Jira liga a conta ao roster (equipes e liderança): só o admin grava um id que
            // já pertence a outra pessoa; os demais usam "Sou eu" no onboarding.
            if (!isAdmin && jiraAccountIdGuard != null
                    && !jiraAccountIdGuard.isFreeFor(incoming.getJiraAccountId(), existing.getId(), existing.getEmail())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Este id do Jira já pertence a outra pessoa. Para vincular sua conta a uma linha da equipe, use \"Sou eu\" no onboarding.");
            }
            existing.setJiraAccountId(incoming.getJiraAccountId().trim());
        }
        if (incoming.getSegmentName() != null) existing.setSegmentName(incoming.getSegmentName());
        if (incoming.getTribeName() != null) existing.setTribeName(incoming.getTribeName());

        if (isAdmin) {
            if (incoming.getJobTitle() != null) {
                // Cargo canônico (Developer/Dev -> Desenvolvedor(a); QA -> Analista de QA); vazio limpa o cargo.
                String novoCargo = CargoNames.canonical(incoming.getJobTitle());
                if (!java.util.Objects.equals(novoCargo, existing.getJobTitle())) {
                    audit(actorId, "USER_JOBTITLE_CHANGED", existing, "cargo: " + existing.getJobTitle() + " -> " + novoCargo);
                    existing.setJobTitle(novoCargo);
                }
            }
            // LEAD reenviado sem mudança (o painel devolve o valor atual) não é erro nem altera nada, mesmo com lead-level=off.
            boolean leadUnchanged = UserRole.isLegacyLead(incoming.getRole()) && UserRole.isLegacyLead(existing.getRole());
            if (incoming.isRoleExplicit() && incoming.getRole() != null && !leadUnchanged) {
                boolean legacyLead = leadPolicy.isLegacy() && UserRole.isLegacyLead(incoming.getRole());
                if (!UserRole.isValid(incoming.getRole()) && !legacyLead) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            leadPolicy.isLegacy() ? "role inválida: use ADMIN, LEAD ou MEMBER"
                                    : "role inválida: use ADMIN ou MEMBER (liderança de squad é um vínculo, não um nível de conta)");
                }
                String newRole = incoming.getRole().toUpperCase();
                String oldRole = existing.getRole() == null ? "MEMBER" : existing.getRole().toUpperCase();
                if (!newRole.equals(oldRole)) {
                    if ("ADMIN".equals(oldRole)) {
                        requireCanLoseAdmin(existing, actorId, "rebaixar");
                    }
                    audit(actorId, "USER_ROLE_CHANGED", existing, "papel: " + oldRole + " -> " + newRole);
                    existing.setRole(newRole);
                }
            }
            if (incoming.isActiveExplicit() && incoming.isActive() != existing.isActive()) {
                if (!incoming.isActive()) {
                    if (existing.getId().equals(actorId)) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Você não pode desativar a sua própria conta.");
                    }
                    if ("ADMIN".equalsIgnoreCase(existing.getRole())) {
                        requireCanLoseAdmin(existing, actorId, "desativar");
                    }
                }
                audit(actorId, incoming.isActive() ? "USER_ACTIVATED" : "USER_DEACTIVATED", existing, null);
                existing.setActive(incoming.isActive());
            }
            if (incoming.getDefaultProjectId() != null) existing.setDefaultProjectId(incoming.getDefaultProjectId());
        }

        existing.setUpdatedAt(LocalDateTime.now());
        User saved = userRepository.save(existing);
        if (sessionGuard != null) {
            sessionGuard.evict(saved.getId());
        }
        return withActiveSquad(saved);
    }

    private void requireCanLoseAdmin(User target, String actorId, String verb) {
        if (target.getId().equals(actorId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Você não pode " + verb + " o seu próprio acesso de administrador. Peça a outro administrador.");
        }
        if (target.isActive() && userRepository.countByRoleIgnoreCaseAndActiveTrue("ADMIN") <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Não é possível " + verb + " o último administrador ativo. Promova outra pessoa antes.");
        }
    }

    private void audit(String actorId, String action, User target, String detail) {
        if (auditLogRepository == null) return;
        String actor = actorId;
        if (actorId != null) {
            actor = userRepository.findById(actorId).map(User::getEmail).orElse(actorId);
        }
        auditLogRepository.save(AuditLog.builder()
                .id(java.util.UUID.randomUUID().toString())
                .action(action)
                .performedBy(actor != null ? actor : "ADMIN")
                .details("Conta " + target.getEmail() + (detail != null ? " (" + detail + ")" : ""))
                .createdAt(LocalDateTime.now())
                .build());
    }

    @Transactional(readOnly = true)
    public UserJiraConfig getJiraConfig(String userId) {
        return jiraConfigRepository.findById(userId).orElse(null);
    }

    @Transactional
    public UserJiraConfig saveJiraConfig(UserJiraConfig config) {
        return jiraConfigRepository.save(config);
    }

    @Transactional
    public void deleteJiraConfig(String userId) {
        jiraConfigRepository.deleteById(userId);
    }

    @Transactional(readOnly = true)
    public UserTdnConfig getTdnConfig(String userId) {
        return tdnConfigRepository.findById(userId).orElse(null);
    }

    @Transactional
    public UserTdnConfig saveTdnConfig(UserTdnConfig config) {
        return tdnConfigRepository.save(config);
    }

    @Transactional
    public void deleteTdnConfig(String userId) {
        tdnConfigRepository.deleteById(userId);
    }

    /**
     * Vínculos de squad da conta (GET /api/users/{uid}/squads). Vêm de project_member_roles (MembershipService, só os
     * diretos: a expansão por tribo/segmento não é "vínculo da pessoa"), e não mais de squad_members.claimed_by_uid.
     * O formato segue o de antes (lista de SquadMember com squadId/role/displayName): a linha do roster da pessoa
     * naquela squad (por jira_account_id) quando existe; senão uma linha só de resposta (não persistida).
     */
    @Transactional(readOnly = true)
    public List<SquadMember> getSquadsForUser(String uid) {
        User user = uid == null ? null : userRepository.findById(uid).orElse(null);
        if (user == null || membershipService == null) {
            return List.of();
        }
        String jira = user.getJiraAccountId() == null ? "" : user.getJiraAccountId().trim();
        List<SquadMember> out = new java.util.ArrayList<>();
        for (com.agilespace.backend.dto.MembershipDto m : membershipService.membershipsOf(user)) {
            if (!m.direct()) continue;
            SquadMember row = jira.isEmpty() || squadMemberRepository == null ? null
                    : squadMemberRepository.findBySquadIdAndJiraAccountId(m.projectId(), jira).orElse(null);
            out.add(row != null ? row : SquadMember.builder()
                    .squadId(m.projectId())
                    .jiraAccountId(jira.isEmpty() ? null : jira)
                    .displayName(user.getName())
                    .email(user.getEmail())
                    .role(m.roleName())
                    .build());
        }
        return out;
    }
}


