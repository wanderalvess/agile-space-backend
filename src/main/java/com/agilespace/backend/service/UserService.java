package com.agilespace.backend.service;

import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.domain.UserJiraConfig;
import com.agilespace.backend.domain.UserRole;
import com.agilespace.backend.domain.UserTdnConfig;
import com.agilespace.backend.dto.UserProjectAccessDto;
import com.agilespace.backend.repository.SquadMemberRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.repository.UserJiraConfigRepository;
import com.agilespace.backend.repository.UserTdnConfigRepository;
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
        User existing = userRepository.findById(incoming.getId()).orElse(null);
        if (existing == null) {
            return null;
        }

        if (incoming.getName() != null) existing.setName(incoming.getName());
        if (incoming.getAvatarSeed() != null) existing.setAvatarSeed(incoming.getAvatarSeed());
        if (incoming.getDailyHours() != null) existing.setDailyHours(incoming.getDailyHours());
        if (incoming.getJiraAccountId() != null) existing.setJiraAccountId(incoming.getJiraAccountId());
        if (incoming.getSegmentName() != null) existing.setSegmentName(incoming.getSegmentName());
        if (incoming.getTribeName() != null) existing.setTribeName(incoming.getTribeName());
        if (incoming.getSquadId() != null && !incoming.getSquadId().equalsIgnoreCase(existing.getSquadId())) {
            if (!isAdmin && !incoming.getSquadId().isBlank()) {
                requireProjectAccess(existing, incoming.getSquadId());
            }
            existing.setSquadId(incoming.getSquadId());
        }

        if (isAdmin) {
            if (incoming.getJobTitle() != null) existing.setJobTitle(incoming.getJobTitle());
            if (incoming.getRole() != null) {
                if (!UserRole.isValid(incoming.getRole())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "role inválida: use ADMIN, LEAD ou MEMBER");
                }
                existing.setRole(incoming.getRole().toUpperCase());
            }
            existing.setActive(incoming.isActive());
            if (incoming.getDefaultProjectId() != null) existing.setDefaultProjectId(incoming.getDefaultProjectId());
        }

        existing.setUpdatedAt(LocalDateTime.now());
        return userRepository.save(existing);
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


