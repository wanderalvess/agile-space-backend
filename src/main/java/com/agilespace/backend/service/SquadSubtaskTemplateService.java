package com.agilespace.backend.service;

import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.domain.SquadSubtaskTemplate;
import com.agilespace.backend.domain.UserJiraConfig;
import com.agilespace.backend.repository.SquadRepository;
import com.agilespace.backend.repository.SquadSubtaskTemplateRepository;
import com.agilespace.backend.repository.UserJiraConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Configuração "quais sub-tarefas o time cria por tipo de issue pai", por squad. Fonte única para o Poker e
 * para o Sprint Planner. Os nomes dos tipos vêm do Jira do projeto (createmeta) — nada fixo aqui.
 */
@Service
@RequiredArgsConstructor
public class SquadSubtaskTemplateService {

    public static final Set<String> ROLES = Set.of("DEV", "QA", "NONE");
    private static final int MAX_TEMPLATES = 30;

    private final SquadSubtaskTemplateRepository repository;
    private final SquadRepository squadRepository;
    private final UserJiraConfigRepository userJiraConfigRepository;
    private final JiraService jiraService;

    @Transactional(readOnly = true)
    public List<SquadSubtaskTemplate> list(String squadId) {
        return repository.findBySquadIdIgnoreCaseOrderByPositionAsc(squadId);
    }

    /**
     * Substitui a configuração inteira da squad pela lista recebida (a tela envia tudo). Ids ausentes são
     * gerados; a posição segue a ordem da lista.
     */
    @Transactional
    public List<SquadSubtaskTemplate> replaceAll(String squadId, List<SquadSubtaskTemplate> incoming) {
        List<SquadSubtaskTemplate> items = incoming == null ? List.of() : incoming;
        if (items.size() > MAX_TEMPLATES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No máximo " + MAX_TEMPLATES + " sub-tarefas por squad.");
        }
        List<SquadSubtaskTemplate> toSave = new ArrayList<>();
        int position = 0;
        for (SquadSubtaskTemplate in : items) {
            String type = trimToNull(in.getJiraIssueTypeName());
            if (type == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Toda sub-tarefa precisa do nome do tipo no Jira.");
            }
            String title = trimToNull(in.getTitleTemplate());
            if (title == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Toda sub-tarefa precisa de um padrão de título.");
            }
            String role = in.getRole() == null ? "NONE" : in.getRole().trim().toUpperCase(Locale.ROOT);
            if (!ROLES.contains(role)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Papel inválido: use DEV, QA ou NONE.");
            }
            toSave.add(SquadSubtaskTemplate.builder()
                    .id(trimToNull(in.getId()) != null ? in.getId().trim() : UUID.randomUUID().toString())
                    .squadId(squadId)
                    .parentIssueType(trimToNull(in.getParentIssueType()))
                    .jiraIssueTypeName(type)
                    .role(role)
                    .titleTemplate(title)
                    .commentTemplate(trimToNull(in.getCommentTemplate()))
                    .position(position++)
                    .active(in.getActive() == null || in.getActive())
                    .build());
        }
        repository.deleteBySquadIdIgnoreCase(squadId);
        repository.flush();
        return repository.saveAll(toSave);
    }

    /**
     * Tipos de issue do projeto da squad no Jira, com a flag `subtask`, usando o token Jira de quem pede.
     * Serve para a tela listar os nomes exatos (sub-tarefas e tipos de pai).
     */
    public List<Map<String, Object>> listJiraIssueTypes(String squadId, String callerId) {
        Squad squad = squadRepository.findById(squadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Squad não encontrada."));
        UserJiraConfig cfg = callerId == null ? null : userJiraConfigRepository.findById(callerId).orElse(null);
        if (cfg == null || isBlank(cfg.getToken())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Configure seu token do Jira no perfil antes.");
        }
        String domain = !isBlank(cfg.getDomain()) ? cfg.getDomain() : squad.getJiraDomain();
        if (isBlank(domain)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nenhum domínio do Jira configurado.");
        }
        String projectKey = squad.getJiraProjectKey() != null ? squad.getJiraProjectKey() : squad.getId();
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (JsonNode t : jiraService.listProjectIssueTypes(domain, cfg.getToken(), projectKey)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", t.path("id").asText(null));
                m.put("name", t.path("name").asText(null));
                m.put("subtask", t.path("subtask").asBoolean(false));
                out.add(m);
            }
            return out;
        } catch (JiraService.JiraWriteException e) {
            throw new ResponseStatusException(e.getStatus() >= 400 && e.getStatus() < 600 ? HttpStatus.valueOf(e.getStatus()) : HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
