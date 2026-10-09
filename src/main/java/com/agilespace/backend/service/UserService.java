package com.agilespace.backend.service;

import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.domain.UserJiraConfig;
import com.agilespace.backend.domain.UserRole;
import com.agilespace.backend.domain.UserTdnConfig;
import com.agilespace.backend.dto.UserProjectAccessDto;
import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.repository.SquadMemberRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.repository.UserJiraConfigRepository;
import com.agilespace.backend.repository.UserTdnConfigRepository;
import com.agilespace.backend.security.JiraAccountIdGuard;
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

    @Autowired
    private UserProjectResolverService userProjectResolverService;

    @Autowired(required = false)
    private JiraAccountIdGuard jiraAccountIdGuard;

    @Autowired(required = false)
    private UserSessionGuard sessionGuard;

    @Autowired(required = false)
    private AuditLogRepository auditLogRepository;

    @Transactional(readOnly = true)
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    @Transactional(readOnly = true)
    public User getUser(String id) {
        return userRepository.findById(id).orElse(null);
    }

    /**
     * Atualiza o perfil de um usuário já existente. Aceita apenas os campos de perfil
     * (nunca role/active/passwordHash/authProvider/email/ssoId a partir do corpo da requisição)
     * para evitar mass assignment; role/active/defaultProjectId (autorização/acesso a projeto)
     * só são aplicados quando isAdmin=true. squadId e jobTitle ENTRAM em checagem de autorização
     * (SquadAccessService usa squadId; InviteController e SquadLeadership usam jobTitle de
     * liderança), então: jobTitle só por ADMIN, e squadId só muda pra um projeto ao qual o usuário
     * já tem vínculo — a mesma regra do /auth/switch-project. Sem isso, um MEMBER se colocava em
     * qualquer squad (e com cargo de liderança) só editando o próprio perfil.
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
        if (incoming.getSquadId() != null && !incoming.getSquadId().equalsIgnoreCase(existing.getSquadId())) {
            if (!isAdmin && !incoming.getSquadId().isBlank()) {
                requireProjectAccess(existing, incoming.getSquadId());
            }
            existing.setSquadId(incoming.getSquadId());
        }

        if (isAdmin) {
            if (incoming.getJobTitle() != null && !incoming.getJobTitle().equals(existing.getJobTitle())) {
                audit(actorId, "USER_JOBTITLE_CHANGED", existing, "cargo: " + existing.getJobTitle() + " -> " + incoming.getJobTitle());
                existing.setJobTitle(incoming.getJobTitle());
            }
            if (incoming.isRoleExplicit() && incoming.getRole() != null) {
                if (!UserRole.isValid(incoming.getRole())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "role inválida: use ADMIN, LEAD ou MEMBER");
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
        return saved;
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

    private void requireProjectAccess(User user, String projectId) {
        UserProjectAccessDto access = userProjectResolverService.resolveUserAccess(user);
        boolean hasAccess = access != null && access.getProjects() != null
                && access.getProjects().stream().anyMatch(p -> projectId.equalsIgnoreCase(p.getProjectId()));
        if (!hasAccess) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Você não tem vínculo com este time. Entre nele pelo onboarding ou peça um convite.");
        }
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

    @Transactional(readOnly = true)
    public List<SquadMember> getSquadsForUser(String uid) {
        return squadMemberRepository.findByClaimedByUid(uid);
    }
}


