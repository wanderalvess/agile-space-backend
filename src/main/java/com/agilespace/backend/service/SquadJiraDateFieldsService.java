package com.agilespace.backend.service;

import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.domain.SquadIssueSnapshot;
import com.agilespace.backend.domain.UserJiraConfig;
import com.agilespace.backend.repository.SquadIssueSnapshotRepository;
import com.agilespace.backend.repository.UserJiraConfigRepository;
import com.agilespace.backend.service.JiraDateFieldResolver.FieldRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Leitura e configuração, por squad, dos campos do Jira com data/hora planejadas do cronograma. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SquadJiraDateFieldsService {

    private static final Pattern CUSTOM_FIELD = Pattern.compile("customfield_\\d{1,9}");
    private static final Set<String> KNOWN_FIELDS = Set.of("duedate", "startDate", "created", "updated", "resolutiondate");

    public record Candidates(List<FieldRef> start, List<FieldRef> end) {}
    public record Sample(int total, int startFilled, int endFilled) {}

    public record Response(String startFieldId, String endFieldId, String startFieldName, String endFieldName,
                           boolean autoDetected, Candidates candidates, Sample sample, String warning) {}

    private final SquadService squadService;
    private final SquadIssueSnapshotRepository snapshotRepository;
    private final UserJiraConfigRepository userJiraConfigRepository;
    private final JiraDateFieldResolver resolver;

    public Response get(String squadId, String callerUserId) {
        Optional<Squad> squad = squadService.getSquad(squadId);
        return build(squadId, squad.orElse(null), callerUserId);
    }

    public Response update(String squadId, String callerUserId, String startFieldId, String endFieldId) {
        String start = validate(startFieldId, "início");
        String end = validate(endFieldId, "fim");
        Squad saved = squadService.saveJiraDateFields(squadId, start, end);
        log.info("Campos de data do Jira da squad {} atualizados por {}: início={}, fim={}",
                squadId, callerUserId, start == null ? "(autodetectar)" : start, end == null ? "(autodetectar)" : end);
        return build(squadId, saved, callerUserId);
    }

    /** Vazio/nulo = autodetectar (devolve null). */
    static String validate(String id, String label) {
        if (id == null || id.isBlank()) return null;
        String v = id.trim();
        if (CUSTOM_FIELD.matcher(v).matches() || KNOWN_FIELDS.contains(v)) return v;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Campo de " + label + " inválido: use o id de um campo do Jira (ex. customfield_12345).");
    }

    private Response build(String squadId, Squad squad, String callerUserId) {
        String cfgStart = squad == null ? null : blankToNull(squad.getJiraStartField());
        String cfgEnd = squad == null ? null : blankToNull(squad.getJiraEndField());
        Sample sample = sample(squadId);

        UserJiraConfig cfg = callerUserId == null ? null : userJiraConfigRepository.findById(callerUserId).orElse(null);
        if (cfg == null || cfg.getToken() == null || cfg.getToken().isBlank()) {
            return new Response(cfgStart, cfgEnd, null, null, false, new Candidates(List.of(), List.of()), sample,
                    "Jira não conectado: configure seu Token de Acesso em Conexão Jira para detectar os campos.");
        }
        String domain = cfg.getDomain() != null && !cfg.getDomain().isBlank() ? cfg.getDomain()
                : (squad == null ? null : squad.getJiraDomain());
        if (domain == null || domain.isBlank()) {
            return new Response(cfgStart, cfgEnd, null, null, false, new Candidates(List.of(), List.of()), sample,
                    "Domínio do Jira não configurado.");
        }

        JiraDateFieldResolver.Resolution r;
        try {
            r = resolver.resolve(domain, cfg.getToken());
        } catch (RuntimeException e) {
            return new Response(cfgStart, cfgEnd, null, null, false, new Candidates(List.of(), List.of()), sample,
                    e instanceof JiraDateFieldResolver.LookupException ? e.getMessage()
                            : "Não foi possível consultar os campos do Jira.");
        }

        String start = JiraDateFieldResolver.pick(cfgStart, r.start());
        String end = JiraDateFieldResolver.pick(cfgEnd, r.end());
        boolean auto = (cfgStart == null && start != null) || (cfgEnd == null && end != null);

        StringBuilder warning = new StringBuilder();
        if (cfgStart == null && r.start().isEmpty()) warning.append("Nenhum campo \"").append(JiraDateFieldResolver.START_NAME).append("\" encontrado no Jira. ");
        if (cfgEnd == null && r.end().isEmpty()) warning.append("Nenhum campo \"").append(JiraDateFieldResolver.END_NAME).append("\" encontrado no Jira. ");
        if (cfgStart == null && r.start().size() > 1) warning.append("Há mais de um campo \"").append(JiraDateFieldResolver.START_NAME).append("\"; escolha qual usar. ");
        if (cfgEnd == null && r.end().size() > 1) warning.append("Há mais de um campo \"").append(JiraDateFieldResolver.END_NAME).append("\"; escolha qual usar. ");

        return new Response(start, end,
                start == null ? null : r.namesById().get(start),
                end == null ? null : r.namesById().get(end),
                auto, new Candidates(r.start(), r.end()), sample,
                warning.length() == 0 ? null : warning.toString().trim());
    }

    private Sample sample(String squadId) {
        List<SquadIssueSnapshot> all = snapshotRepository.findBySquadId(squadId);
        int startFilled = 0;
        int endFilled = 0;
        for (SquadIssueSnapshot s : all) {
            if (Boolean.TRUE.equals(s.getDatesAreInferred())) continue;
            if (s.getTargetStart() != null && !s.getTargetStart().isBlank()) startFilled++;
            if (s.getTargetEnd() != null && !s.getTargetEnd().isBlank()) endFilled++;
        }
        return new Sample(all.size(), startFilled, endFilled);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
