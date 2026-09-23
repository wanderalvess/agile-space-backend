package com.agilespace.backend.service;

import com.agilespace.backend.domain.ProjectConfig;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.OnboardingRosterDto;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.SquadMemberRepository;
import com.agilespace.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Onboarding: reconhecer a pessoa antes de perguntar qualquer coisa a ela.
 *
 * O roster importado do Profields já sabe quem trabalha em cada projeto, mas o
 * vínculo automático só acontece quando o e-mail do Jira bate com o e-mail do
 * login (ver UserProjectResolverService). Quem cai no onboarding é justamente
 * quem NÃO bateu — normalmente porque usa e-mails diferentes nos dois lugares.
 *
 * Em vez de pedir pra essa pessoa digitar chave de projeto, squad e papel, aqui
 * ela se encontra na lista que já existe e reivindica a própria linha ("sou eu"),
 * o que liga a conta ao papel real vindo do Jira.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OnboardingService {

    private static final int MAX_SUGGESTIONS = 3;
    private static final int MAX_SEARCH_RESULTS = 8;
    private static final int PREVIEW_MEMBERS = 6;

    private final ProjectMemberRoleRepository projectMemberRoleRepository;
    private final ProjectConfigRepository projectConfigRepository;
    private final SquadMemberRepository squadMemberRepository;
    private final UserRepository userRepository;

    /** Minúsculo, sem acento e com espaços colapsados — base de toda comparação de nome. */
    static String normalize(String value) {
        if (value == null) return "";
        String stripped = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return stripped.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** "wanderson.kelvin@totvs.com.br" -> "w*************n@totvs.com.br". */
    static String maskEmail(String email) {
        if (email == null || email.isBlank() || !email.contains("@")) return "";
        String[] parts = email.trim().split("@", 2);
        String local = parts[0];
        if (local.length() <= 2) return "*@" + parts[1];
        return local.charAt(0) + "*".repeat(local.length() - 2) + local.charAt(local.length() - 1) + "@" + parts[1];
    }

    /**
     * Primeiro e último nome — o que sobrevive a "Ana Nogueira" x "Ana Paula Nogueira"
     * sem casar duas pessoas diferentes só porque compartilham o primeiro nome.
     */
    private static String firstLast(String normalized) {
        String[] parts = normalized.split(" ");
        if (parts.length <= 1) return normalized;
        return parts[0] + " " + parts[parts.length - 1];
    }

    /**
     * Candidatos a "é você?": linhas do roster cujo nome bate com o nome da conta
     * logada e que ainda não pertencem a outra conta.
     */
    @Transactional(readOnly = true)
    public List<OnboardingRosterDto> suggestionsFor(User user) {
        String myName = normalize(user.getName());
        if (myName.isBlank()) return List.of();
        String myFirstLast = firstLast(myName);

        List<ProjectMemberRole> matches = projectMemberRoleRepository.findAll().stream()
                .filter(r -> {
                    String candidate = normalize(r.getDisplayName());
                    if (candidate.isBlank()) return false;
                    return candidate.equals(myName) || firstLast(candidate).equals(myFirstLast);
                })
                .filter(r -> r.getUserId() == null || r.getUserId().isBlank() || r.getUserId().equals(user.getId()))
                // Linha de liderança não entra como sugestão de 1 clique: ela não pode
                // ser autovinculada, e oferecer um "sim, sou eu" que depois nega seria pior
                // do que não oferecer.
                .filter(r -> !r.isLeadership())
                .sorted(Comparator.comparing(ProjectMemberRole::getProjectId))
                .limit(MAX_SUGGESTIONS)
                .toList();

        return matches.stream()
                .map(r -> toDto(r.getProjectId(), List.of(r), countMembers(r.getProjectId()), user))
                .toList();
    }

    /** Busca por projeto (chave/nome) ou por pessoa (nome de quem já está no roster). */
    @Transactional(readOnly = true)
    public List<OnboardingRosterDto> search(String query, User user) {
        String q = normalize(query);
        if (q.length() < 2) return List.of();

        Map<String, ProjectConfig> byProject = new LinkedHashMap<>();
        for (ProjectConfig p : projectConfigRepository.findAllByOrderBySegmentNameAscNameAsc()) {
            if (normalize(p.getId()).contains(q) || normalize(p.getName()).contains(q)) {
                byProject.put(p.getId(), p);
            }
        }

        // Projetos alcançados por nome de pessoa ("entrar no time da Camila").
        if (byProject.size() < MAX_SEARCH_RESULTS) {
            for (ProjectMemberRole r : projectMemberRoleRepository.findAll()) {
                if (byProject.size() >= MAX_SEARCH_RESULTS) break;
                if (byProject.containsKey(r.getProjectId())) continue;
                if (normalize(r.getDisplayName()).contains(q)) {
                    projectConfigRepository.findById(r.getProjectId())
                            .ifPresent(p -> byProject.put(p.getId(), p));
                }
            }
        }

        List<OnboardingRosterDto> result = new ArrayList<>();
        for (ProjectConfig p : byProject.values()) {
            List<ProjectMemberRole> members = projectMemberRoleRepository.findByProjectId(p.getId());
            List<ProjectMemberRole> preview = members.stream().limit(PREVIEW_MEMBERS).toList();
            result.add(toDto(p.getId(), preview, members.size(), user));
            if (result.size() >= MAX_SEARCH_RESULTS) break;
        }
        return result;
    }

    /** Roster completo de um projeto, pra pessoa se achar na lista. */
    @Transactional(readOnly = true)
    public OnboardingRosterDto roster(String projectKey, User user) {
        String key = projectKey.trim().toUpperCase();
        if (!projectConfigRepository.existsById(key)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Projeto não encontrado");
        }
        List<ProjectMemberRole> members = projectMemberRoleRepository.findByProjectId(key);
        return toDto(key, members, members.size(), user);
    }

    /**
     * "Sou eu": liga a conta logada a uma linha que já existe no roster, preservando
     * o papel que veio do Jira. É o que substitui o aviso de "seu e-mail do Jira é
     * diferente do login" — em vez de explicar o problema, resolve.
     *
     * Liderança fica de fora de propósito: papel de liderança dá governança da squad
     * (ver JiraProfieldsService.SELF_SERVICE_JOIN_ROLE_NAMES) e o roster é visível a
     * qualquer autenticado, então autovínculo viraria escalada de privilégio. Esse
     * caminho é o convite.
     *
     * @return a chave do projeto reivindicado, pra quem chamou trocar o projeto ativo.
     */
    @Transactional
    public String claim(String memberId, User user) {
        ProjectMemberRole row = projectMemberRoleRepository.findById(memberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pessoa não encontrada no roster"));

        if (row.getUserId() != null && !row.getUserId().isBlank() && !row.getUserId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Essa pessoa já está vinculada a outra conta. Se for um engano, peça pra quem administra o projeto desfazer o vínculo.");
        }

        if (row.isLeadership()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Papel de liderança não pode ser autovinculado. Peça um link de convite a quem já lidera o time.");
        }

        row.setUserId(user.getId());
        projectMemberRoleRepository.save(row);

        // O accountId do Jira é o que liga a pessoa a issue, worklog e capacidade no
        // resto do sistema. Se a conta ainda não tem um, herda o da linha reivindicada.
        boolean userChanged = false;
        if ((user.getJiraAccountId() == null || user.getJiraAccountId().isBlank())
                && row.getJiraAccountId() != null && !row.getJiraAccountId().isBlank()) {
            user.setJiraAccountId(row.getJiraAccountId().trim());
            userChanged = true;
        }
        if (userChanged) {
            userRepository.save(user);
        }

        // Mesma pessoa no roster da squad (horas/capacidade) passa a apontar pra conta.
        for (String identifier : new String[] { row.getJiraAccountId(), row.getEmail(), row.getDisplayName() }) {
            if (identifier == null || identifier.isBlank()) continue;
            for (SquadMember sm : squadMemberRepository.findByUserIdentifier(identifier.trim())) {
                if (sm.getClaimedByUid() == null || sm.getClaimedByUid().isBlank()) {
                    sm.setClaimedByUid(user.getId());
                    squadMemberRepository.save(sm);
                }
            }
        }

        log.info("Onboarding: usuário {} reivindicou a linha {} ({}) no projeto {}",
                user.getId(), row.getId(), row.getRoleName(), row.getProjectId());

        return row.getProjectId();
    }

    private int countMembers(String projectId) {
        return projectMemberRoleRepository.findByProjectId(projectId).size();
    }

    private OnboardingRosterDto toDto(String projectId, List<ProjectMemberRole> members, int total, User user) {
        Optional<ProjectConfig> project = projectConfigRepository.findById(projectId);
        return OnboardingRosterDto.builder()
                .projectId(projectId)
                .projectName(project.map(ProjectConfig::getName).orElse(projectId))
                .segmentName(project.map(ProjectConfig::getSegmentName).orElse(""))
                .tribeName(project.map(ProjectConfig::getTribeName).orElse(""))
                .memberCount(total)
                .members(members.stream().map(r -> toCandidate(r, user)).toList())
                .build();
    }

    private OnboardingRosterDto.Candidate toCandidate(ProjectMemberRole r, User user) {
        boolean claimedByMe = r.getUserId() != null && r.getUserId().equals(user.getId());
        boolean claimed = r.getUserId() != null && !r.getUserId().isBlank();
        return OnboardingRosterDto.Candidate.builder()
                .memberId(r.getId())
                .displayName(r.getDisplayName())
                .roleName(r.getRoleName())
                .roleKey(r.getRoleKey())
                .avatarUrl(r.getAvatarUrl())
                .leadership(r.isLeadership())
                .claimed(claimed)
                .claimedByMe(claimedByMe)
                .claimable(!r.isLeadership() && (!claimed || claimedByMe))
                .emailHint(maskEmail(r.getEmail()))
                .build();
    }
}
