package com.agilespace.backend.service;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.SquadMemberExclusion;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.SquadMemberExclusionRepository;
import com.agilespace.backend.repository.SquadMemberRepository;
import com.agilespace.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pessoas do time de uma squad: adicionar, trocar o papel e remover, feito por quem lidera o time (Agile Master ou
 * People Lead da própria squad) ou por admin. Tudo é validado aqui, no servidor.
 * <p>
 * O papel mexido é o papel DENTRO da equipe (roster + cadastro do projeto). Nunca toca em User.role (autorização global),
 * e remover alguém não apaga a conta.
 */
@Service
@RequiredArgsConstructor
public class SquadTeamService {

    /** Papéis que gerenciam o time. */
    private static final Set<String> MANAGER_KEYS = Set.of("AGILE_MASTER", "SCRUM_MASTER", "PEOPLE_LEAD");

    /** nome (maiúsculo) -> {nome canônico, chave, liderança?, gerente?}. Tribe Lead e Agile Coach são só do Jira (dão acesso à tribo). */
    private record RoleDef(String name, String key, boolean leadership, boolean manager) {}

    private static final Map<String, RoleDef> CATALOG = Map.ofEntries(
            entry(new RoleDef("Developer", "DEVELOPER", false, false)),
            entry(new RoleDef("QA", "QA", false, false)),
            entry(new RoleDef("Designer", "DESIGNER", false, false)),
            entry(new RoleDef("UX/Designer", "UX_DESIGNER", false, false)),
            entry(new RoleDef("UX", "UX", false, false)),
            entry(new RoleDef("SME", "SME", false, false)),
            entry(new RoleDef("Stakeholder / Observador", "STAKEHOLDER", false, false)),
            entry(new RoleDef("Product Owner", "PRODUCT_OWNER", true, false)),
            entry(new RoleDef("Tech Lead", "TECH_LEAD", true, false)),
            entry(new RoleDef("Agile Master", "AGILE_MASTER", true, true)),
            entry(new RoleDef("Scrum Master", "SCRUM_MASTER", true, true)),
            entry(new RoleDef("People Lead", "PEOPLE_LEAD", true, true)));

    private static Map.Entry<String, RoleDef> entry(RoleDef d) {
        return Map.entry(d.name().toUpperCase(Locale.ROOT), d);
    }

    private final SquadMemberRepository memberRepository;
    private final SquadMemberExclusionRepository exclusionRepository;
    private final ProjectMemberRoleRepository projectMemberRoleRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;

    public record AddRequest(String userId, String email, String displayName, String roleName) {}

    public record Candidate(String userId, String name, String email) {}

    // ---------- permissão ----------

    /** Admin, ou Agile Master / Scrum Master / People Lead DESTA squad (linha do cadastro do projeto). */
    public boolean canManageTeam(String squadId, User caller) {
        if (caller == null) return false;
        if ("ADMIN".equalsIgnoreCase(caller.getRole())) return true;
        return projectRows(squadId).stream().anyMatch(r -> isManager(r) && JiraProfieldsService.rowMatchesUser(r, caller));
    }

    public void requireCanManageTeam(String squadId, User caller) {
        if (!canManageTeam(squadId, caller)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Só o Agile Master ou o People Lead desta equipe (ou um administrador) pode adicionar, remover ou mudar o papel das pessoas.");
        }
    }

    private static boolean isAdmin(User u) {
        return u != null && "ADMIN".equalsIgnoreCase(u.getRole());
    }

    // ---------- busca ----------

    public List<Candidate> searchCandidates(String squadId, User caller, String query) {
        requireCanManageTeam(squadId, caller);
        String q = query == null ? "" : query.trim();
        if (q.length() < 3) return List.of();
        List<Candidate> out = new ArrayList<>();
        for (User u : userRepository.findByEmailStartingWithIgnoreCase(q)) {
            if (out.size() >= 10) break;
            out.add(new Candidate(u.getId(), u.getName(), u.getEmail()));
        }
        return out;
    }

    // ---------- adicionar ----------

    @Transactional
    public SquadMember addMember(String squadId, User caller, AddRequest req) {
        requireCanManageTeam(squadId, caller);
        RoleDef role = roleFor(req.roleName(), caller);

        User user = null;
        String reqEmail = PersonIdentity.normEmail(req.email());
        if (notBlank(req.userId())) {
            user = userRepository.findById(req.userId().trim()).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Pessoa não encontrada. Busque pelo e-mail ou cadastre pelo nome."));
        } else if (reqEmail != null) {
            user = userRepository.findByEmail(reqEmail).orElse(null);
            if (user == null && !reqEmail.equals(req.email().trim())) user = userRepository.findByEmail(req.email().trim()).orElse(null);
        }
        String email = user != null ? PersonIdentity.normEmail(user.getEmail()) : reqEmail;
        String name = user != null && notBlank(user.getName()) ? PersonIdentity.cleanName(user.getName()) : PersonIdentity.cleanName(req.displayName());
        if (name == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o nome da pessoa.");
        }
        if (name.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome muito longo.");
        }

        // Chave estável (nunca por relógio): a mesma pessoa cai sempre na mesma chave, então um duplo clique ou dois
        // pedidos ao mesmo tempo esbarram na unicidade (squad + conta) em vez de criar duas linhas idênticas.
        // Mesma regra de id da planilha (e-mail em minúsculas) para os dois caminhos se encontrarem.
        String jiraAccountId = user != null && notBlank(user.getJiraAccountId()) ? user.getJiraAccountId().trim()
                : user != null ? user.getId()
                : email != null ? email
                : "manual-" + squadId + "-" + slug(name);

        List<SquadMember> roster = memberRepository.findBySquadIdOrderByDisplayNameAsc(squadId);
        SquadMember dup = PersonIdentity.resolve(roster, jiraAccountId, email, name);
        // Só o NOME em comum com um pré-cadastro sem e-mail, e agora há e-mail: não dá para saber se é a mesma pessoa
        // (decisão da liderança), então não bloqueia; o cadastro com e-mail diferente segue como outra pessoa.
        boolean nameOnly = dup != null && email != null
                && !dup.getJiraAccountId().trim().equalsIgnoreCase(jiraAccountId)
                && !email.equals(PersonIdentity.normEmail(dup.getEmail()));
        if (dup != null && !nameOnly) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, name + " já está no time desta squad."
                    + (email == null ? " Para cadastrar outra pessoa com o mesmo nome, informe o e-mail." : ""));
        }

        // Quem já consta no cadastro do projeto (fundador da equipe, importação do Jira) com este papel não ganha uma
        // segunda linha igual: o vínculo existente é reaproveitado e só o roster (horas) é criado.
        ProjectMemberRole existingRow = null;
        for (ProjectMemberRole r : projectRows(squadId)) {
            boolean same = (user != null && user.getId() != null && user.getId().equals(r.getUserId()))
                    || (email != null && email.equals(PersonIdentity.normEmail(r.getEmail())))
                    || (notBlank(r.getJiraAccountId()) && r.getJiraAccountId().trim().equalsIgnoreCase(jiraAccountId));
            if (!same) continue;
            if (r.getRoleKey() != null && r.getRoleKey().equalsIgnoreCase(role.key())) { existingRow = r; break; }
            throw new ResponseStatusException(HttpStatus.CONFLICT, name + " já está nesta squad como " + r.getRoleName()
                    + ". Para mudar o papel, use a troca de papel da pessoa.");
        }

        SquadMember member = SquadMember.builder()
                .dbId(squadId + "_" + jiraAccountId).squadId(squadId).jiraAccountId(jiraAccountId)
                .displayName(name).email(email).role(role.name())
                .claimedByUid(user != null ? user.getId() : null)
                .updatedAt(Instant.now().toString())
                .build();
        try {
            member = memberRepository.save(member);
            memberRepository.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, name + " já está no time desta squad.", ex);
        }

        if (existingRow == null) {
            projectMemberRoleRepository.save(ProjectMemberRole.builder()
                    .projectId(squadId).roleName(role.name()).roleKey(role.key())
                    .jiraAccountId(user != null ? user.getJiraAccountId() : null)
                    .displayName(name).email(email).userId(user != null ? user.getId() : null)
                    .isLeadership(role.leadership()).source(com.agilespace.backend.domain.MembershipSource.MANUAL).build());
        }

        exclusionRepository.deleteById(squadId + "_" + jiraAccountId);
        audit(caller, "SQUAD_TEAM_MEMBER_ADDED", squadId, name + " (" + (email != null ? email : jiraAccountId) + ") como " + role.name());
        return member;
    }

    // ---------- trocar o papel ----------

    @Transactional
    public SquadMember changeRole(String squadId, User caller, String jiraAccountId, String roleName) {
        requireCanManageTeam(squadId, caller);
        RoleDef role = roleFor(roleName, caller);
        SquadMember member = memberRepository.findBySquadIdAndJiraAccountId(squadId, jiraAccountId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Pessoa não encontrada no time desta squad."));
        List<ProjectMemberRole> rows = rowsOf(squadId, member);
        requireMayTouch(caller, rows);
        if (rows.stream().anyMatch(ProjectMemberRole::isLeadership) && !role.leadership()) {
            requireAnotherLeader(squadId, rows, caller);
        }
        if (rows.stream().anyMatch(r -> isManager(r)) && !role.manager()) {
            requireAnotherManager(squadId, rows, caller);
        }

        String previous = member.getRole();
        member.setRole(role.name());
        member.setUpdatedAt(Instant.now().toString());
        member = memberRepository.save(member);

        if (rows.isEmpty()) {
            User linked = linkedUser(member);
            projectMemberRoleRepository.save(ProjectMemberRole.builder()
                    .projectId(squadId).roleName(role.name()).roleKey(role.key())
                    .jiraAccountId(linked != null ? linked.getJiraAccountId() : null)
                    .displayName(member.getDisplayName()).email(member.getEmail()).userId(linked != null ? linked.getId() : null)
                    .isLeadership(role.leadership()).source(com.agilespace.backend.domain.MembershipSource.MANUAL).build());
        } else {
            for (ProjectMemberRole r : rows) {
                r.setRoleName(role.name());
                r.setRoleKey(role.key());
                r.setLeadership(role.leadership());
                // Papel trocado à mão deixa de seguir o Jira: a reimportação não o desfaz.
                r.setSource(com.agilespace.backend.domain.MembershipSource.MANUAL);
            }
            projectMemberRoleRepository.saveAll(rows);
        }
        audit(caller, "SQUAD_TEAM_ROLE_CHANGED", squadId, member.getDisplayName() + ": " + previous + " -> " + role.name());
        return member;
    }

    // ---------- remover ----------

    @Transactional
    public void removeMember(String squadId, User caller, String jiraAccountId) {
        requireCanManageTeam(squadId, caller);
        SquadMember member = memberRepository.findBySquadIdAndJiraAccountId(squadId, jiraAccountId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Pessoa não encontrada no time desta squad."));
        List<ProjectMemberRole> rows = rowsOf(squadId, member);
        requireMayTouch(caller, rows);
        if (rows.stream().anyMatch(ProjectMemberRole::isLeadership)) {
            requireAnotherLeader(squadId, rows, caller);
        }

        memberRepository.delete(member);
        if (!rows.isEmpty()) projectMemberRoleRepository.deleteAll(rows);

        // Solta a conta da squad (sem apagar a conta) para o acesso cair de verdade.
        User linked = linkedUser(member);
        if (linked != null) {
            boolean changed = false;
            if (linked.getDefaultProjectId() != null && squadId.equalsIgnoreCase(linked.getDefaultProjectId())) { linked.setDefaultProjectId(null); changed = true; }
            if (changed) { linked.setUpdatedAt(LocalDateTime.now()); userRepository.save(linked); }
        }

        // O sync do Jira não recoloca quem foi removido à mão.
        exclusionRepository.save(SquadMemberExclusion.builder()
                .dbId(squadId + "_" + member.getJiraAccountId()).squadId(squadId).jiraAccountId(member.getJiraAccountId())
                .email(member.getEmail()).removedBy(caller.getId()).removedAt(Instant.now().toString()).build());
        audit(caller, "SQUAD_TEAM_MEMBER_REMOVED", squadId, member.getDisplayName() + " (" + (member.getEmail() != null ? member.getEmail() : member.getJiraAccountId()) + ")");
    }

    // ---------- regras ----------

    private RoleDef roleFor(String roleName, User caller) {
        RoleDef def = roleName == null ? null : CATALOG.get(roleName.trim().toUpperCase(Locale.ROOT));
        if (def == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Papel inválido para o time. Escolha um da lista.");
        }
        // Agile Master / People Lead dão o poder de gerir equipes: só admin atribui (senão um PL promoveria quem quisesse).
        if (def.manager() && !isAdmin(caller)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Agile Master, Scrum Master e People Lead são atribuídos por um administrador (ou vêm do Jira).");
        }
        return def;
    }

    /** Quem já é Agile Master/People Lead só é alterado/removido pelo admin ou por ele mesmo. */
    private void requireMayTouch(User caller, List<ProjectMemberRole> targetRows) {
        if (isAdmin(caller)) return;
        boolean targetIsManager = targetRows.stream().anyMatch(SquadTeamService::isManager);
        boolean self = targetRows.stream().anyMatch(r -> JiraProfieldsService.rowMatchesUser(r, caller));
        if (targetIsManager && !self) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Agile Master e People Lead só são alterados ou removidos por um administrador.");
        }
    }

    private void requireAnotherLeader(String squadId, List<ProjectMemberRole> targetRows, User caller) {
        if (isAdmin(caller)) return;
        boolean other = projectRows(squadId).stream()
                .filter(ProjectMemberRole::isLeadership)
                .anyMatch(r -> targetRows.stream().noneMatch(t -> t.getId().equals(r.getId())));
        if (!other) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Esta é a única liderança da equipe. Cadastre outra liderança antes (ou peça a um administrador).");
        }
    }

    private void requireAnotherManager(String squadId, List<ProjectMemberRole> targetRows, User caller) {
        if (isAdmin(caller)) return;
        boolean other = projectRows(squadId).stream()
                .filter(SquadTeamService::isManager)
                .anyMatch(r -> targetRows.stream().noneMatch(t -> t.getId().equals(r.getId())));
        if (!other) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Este é o único Agile Master/People Lead da equipe. Peça a um administrador para trocar.");
        }
    }

    // ---------- apoio ----------

    private static boolean isManager(ProjectMemberRole r) {
        return r.getRoleKey() != null && MANAGER_KEYS.contains(r.getRoleKey().toUpperCase(Locale.ROOT));
    }

    private List<ProjectMemberRole> projectRows(String squadId) {
        List<ProjectMemberRole> rows = new ArrayList<>(projectMemberRoleRepository.findByProjectId(squadId));
        String alias = "DDWMISSI".equalsIgnoreCase(squadId) ? "MISSI" : "MISSI".equalsIgnoreCase(squadId) ? "DDWMISSI" : null;
        if (alias != null) rows.addAll(projectMemberRoleRepository.findByProjectId(alias));
        return rows;
    }

    /** Linhas do cadastro do projeto que são desta pessoa do roster (conta do Jira, e-mail ou conta vinculada). */
    private List<ProjectMemberRole> rowsOf(String squadId, SquadMember m) {
        List<ProjectMemberRole> out = new ArrayList<>();
        User linked = linkedUser(m);
        for (ProjectMemberRole r : projectRows(squadId)) {
            boolean match = (notBlank(r.getJiraAccountId()) && r.getJiraAccountId().equals(m.getJiraAccountId()))
                    || (notBlank(r.getEmail()) && notBlank(m.getEmail()) && r.getEmail().trim().equalsIgnoreCase(m.getEmail().trim()))
                    || (notBlank(r.getUserId()) && linked != null && r.getUserId().equals(linked.getId()));
            if (match) out.add(r);
        }
        return out;
    }

    /**
     * Conta da pessoa do roster: pela conta do Jira ou, na falta, pelo e-mail. squad_members.claimed_by_uid deixou de
     * ser fonte de vínculo (Etapa 5 do vínculo): a capacidade liga por jira_account_id e o acesso por project_member_roles.
     */
    private User linkedUser(SquadMember m) {
        if (notBlank(m.getJiraAccountId())) {
            User u = userRepository.findByJiraAccountId(m.getJiraAccountId().trim()).orElse(null);
            if (u != null) return u;
        }
        if (notBlank(m.getEmail())) {
            return userRepository.findByEmail(m.getEmail().trim()).orElse(null);
        }
        return null;
    }

    private void audit(User actor, String action, String squadId, String detail) {
        if (auditLogRepository == null) return;
        auditLogRepository.save(AuditLog.builder()
                .id(UUID.randomUUID().toString())
                .action(action)
                .performedBy(actor.getEmail() != null ? actor.getEmail() : actor.getId())
                .details("Squad " + squadId + ": " + detail)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return s.isEmpty() ? "pessoa" : s;
    }
}
