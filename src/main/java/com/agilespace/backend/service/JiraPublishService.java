package com.agilespace.backend.service;

import com.agilespace.backend.domain.*;
import com.agilespace.backend.dto.JiraPublishDtos;
import com.agilespace.backend.repository.*;
import com.agilespace.backend.websocket.PokerWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Publica no Jira o resultado do refinamento de uma tarefa do Poker: renomeia a pai com a estimativa
 * "(DEV 10h / QA 12h)", cria as sub-tarefas configuradas pela squad com as horas de cada papel e comenta
 * em cada sub-tarefa o que foi combinado (notas DEV e QA). Tudo vira um {@link JiraPublishJob} com passos
 * ordenados: um passo concluído nunca é refeito, então a retentativa só continua de onde parou.
 *
 * <p>Guard rails: só existem três tipos de passo (os três métodos de escrita do {@link JiraService}); o tipo
 * de sub-tarefa precisa estar entre os configurados pela squad; comentários só em sub-tarefas criadas pelo
 * próprio job; antes de criar, confere no Jira se a sub com o mesmo título e tipo já existe sob a pai.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JiraPublishService {

    public static final String WS_EVENT = "JIRA_PUBLISH_UPDATED";
    /** Sufixo que o time já põe no título da pai: "(DEV 10h / QA 12h)", "(DEV 8h)", "(QA 4h)". */
    static final Pattern ESTIMATE_SUFFIX = Pattern.compile("\\s*\\((?:DEV\\s*[0-9.,]+h?)?(?:\\s*/\\s*)?(?:QA\\s*[0-9.,]+h?)?\\)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> KINDS = Set.of(JiraPublishStep.RENAME_PARENT, JiraPublishStep.CREATE_SUBTASK, JiraPublishStep.ADD_COMMENT);
    private static final int MAX_STEPS = 60;
    private static final int MAX_COMMENT_LENGTH = 30_000;

    private final JiraPublishJobRepository jobRepository;
    private final JiraPublishStepRepository stepRepository;
    private final SquadSubtaskTemplateRepository templateRepository;
    private final PokerRoomRepository roomRepository;
    private final PokerParticipantRepository participantRepository;
    private final SquadRepository squadRepository;
    private final UserJiraConfigRepository userJiraConfigRepository;
    private final JiraService jiraService;
    private final PokerWebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper;

    record Creds(String domain, String token) {}

    // ------------------------------------------------------------------ prévia

    public JiraPublishDtos.Preview preview(String roomId, String issueId, String callerId, String callerRole) {
        PokerRoom room = requireRoom(roomId);
        requireRoomParticipant(room, callerId, callerRole);
        JsonNode issue = requireIssue(room, issueId);

        String parentKey = text(issue, "key");
        if (parentKey == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Esta tarefa não tem chave do Jira (não foi importada).");
        }
        parentKey = JiraService.assertIssueKey(parentKey);
        String squadId = room.getTeam();
        if (isBlank(squadId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A sala não está ligada a uma squad; não dá para saber quais sub-tarefas criar.");
        }
        String parentType = text(issue, "jiraType");
        List<SquadSubtaskTemplate> templates = templatesFor(squadId, parentType);

        boolean hoursDeck = "hours".equalsIgnoreCase(room.getDeckType());
        Double devHours = hoursDeck ? parseHours(roleValue(issue, "dev")) : null;
        Double qaHours = hoursDeck ? parseHours(roleValue(issue, "qa")) : null;
        List<String> warnings = new ArrayList<>();
        if (templates.isEmpty()) {
            warnings.add("Nenhuma sub-tarefa configurada para a squad " + squadId
                    + (parentType != null ? " com issue pai do tipo \"" + parentType + "\"" : "")
                    + ": o envio só renomeia a pai (e comenta nela, se você escrever um comentário). Configure em Sub-tarefas do time.");
        }
        if (!hoursDeck) warnings.add("A sala não usa o baralho de horas: as sub-tarefas vão sem estimativa.");
        if (hoursDeck && devHours == null) warnings.add("Sem estimativa DEV para esta tarefa.");
        if (hoursDeck && qaHours == null) warnings.add("Sem estimativa QA para esta tarefa.");

        String currentSummary = text(issue, "title");
        String baseSummary = stripEstimateSuffix(currentSummary == null ? parentKey : currentSummary);
        String newSummary = baseSummary + estimateSuffix(devHours, qaHours);

        List<JiraPublishDtos.Step> steps = new ArrayList<>();
        steps.add(JiraPublishDtos.Step.builder()
                .index(0).kind(JiraPublishStep.RENAME_PARENT).targetKey(parentKey).summary(newSummary).build());

        String devNotes = text(issue, "devNotes");
        String qaNotes = text(issue, "qaNotes");
        for (SquadSubtaskTemplate t : templates) {
            Double hours = "DEV".equals(t.getRole()) ? devHours : "QA".equals(t.getRole()) ? qaHours : null;
            String notes = "DEV".equals(t.getRole()) ? devNotes : "QA".equals(t.getRole()) ? qaNotes : null;
            Map<String, String> vars = vars(parentKey, baseSummary, t.getJiraIssueTypeName(), t.getRole(), hours, notes);
            int createIndex = steps.size();
            steps.add(JiraPublishDtos.Step.builder()
                    .index(createIndex).kind(JiraPublishStep.CREATE_SUBTASK)
                    .issueType(t.getJiraIssueTypeName()).role(t.getRole()).templateId(t.getId())
                    .summary(truncate(render(t.getTitleTemplate(), vars), 255))
                    .estimateHours(hours)
                    .build());
            String comment = isBlank(t.getCommentTemplate()) ? null : render(t.getCommentTemplate(), vars).trim();
            if (!isBlank(comment)) {
                steps.add(JiraPublishDtos.Step.builder()
                        .index(steps.size()).kind(JiraPublishStep.ADD_COMMENT).dependsOnIndex(createIndex)
                        .comment(comment).build());
            }
        }

        return JiraPublishDtos.Preview.builder()
                .roomId(room.getId()).issueId(issueId).squadId(squadId)
                .projectKey(projectKeyOf(parentKey)).parentKey(parentKey).parentIssueType(parentType)
                .currentSummary(currentSummary).hoursDeck(hoursDeck).devHours(devHours).qaHours(qaHours)
                .steps(steps).warnings(warnings)
                .build();
    }

    // ------------------------------------------------------------------ envio

    /** Valida os passos editados pelo cliente, grava o job e executa na hora. Devolve o job com o estado final. */
    public JiraPublishDtos.Job submit(String roomId, JiraPublishDtos.SubmitRequest request, String callerId, String callerRole) {
        PokerRoom room = requireRoom(roomId);
        requireRoomParticipant(room, callerId, callerRole);
        if (request == null || isBlank(request.getIssueId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe a tarefa.");
        }
        JsonNode issue = requireIssue(room, request.getIssueId());
        String parentKey = JiraService.assertIssueKey(text(issue, "key"));
        String squadId = room.getTeam();
        if (isBlank(squadId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A sala não está ligada a uma squad.");
        }
        Creds creds = requireCreds(callerId, squadId);

        for (JiraPublishJob existing : jobRepository.findByRoomIdAndIssueIdOrderByCreatedAtDesc(roomId, request.getIssueId())) {
            if (JiraPublishJob.DONE.equals(existing.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta tarefa já foi publicada no Jira (" + existing.getParentKey() + ").");
            }
            if (JiraPublishJob.RUNNING.equals(existing.getStatus()) || JiraPublishJob.PENDING.equals(existing.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um envio em andamento para esta tarefa.");
            }
            if (JiraPublishJob.ERROR.equals(existing.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "O envio anterior desta tarefa falhou: use \"Tentar novamente\" na fila em vez de enviar de novo.");
            }
        }

        Set<String> allowedTypes = new HashSet<>();
        for (SquadSubtaskTemplate t : templateRepository.findBySquadIdIgnoreCaseOrderByPositionAsc(squadId)) {
            allowedTypes.add(t.getJiraIssueTypeName().trim().toLowerCase(Locale.ROOT));
        }
        List<JiraPublishStep> steps = validateSteps(request.getSteps(), parentKey, allowedTypes);

        JiraPublishJob job = JiraPublishJob.builder()
                .id(UUID.randomUUID().toString())
                .squadId(squadId).roomId(roomId).issueId(request.getIssueId())
                .parentKey(parentKey).parentSummary(text(issue, "title"))
                .requestedBy(callerId).status(JiraPublishJob.PENDING)
                .build();
        job = jobRepository.save(job);
        for (JiraPublishStep s : steps) s.setJobId(job.getId());
        steps = stepRepository.saveAll(steps);

        run(job, steps, creds);
        return toDto(job, stepRepository.findByJobIdOrderByPositionAsc(job.getId()));
    }

    /** Reexecuta do primeiro passo não concluído, com o token de quem pediu a retentativa. */
    public JiraPublishDtos.Job retry(String roomId, String jobId, String callerId, String callerRole) {
        PokerRoom room = requireRoom(roomId);
        requireRoomParticipant(room, callerId, callerRole);
        JiraPublishJob job = jobRepository.findById(jobId)
                .filter(j -> roomId.equals(j.getRoomId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Envio não encontrado."));
        if (JiraPublishJob.DONE.equals(job.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este envio já foi concluído.");
        }
        if (JiraPublishJob.RUNNING.equals(job.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este envio está em andamento.");
        }
        Creds creds = requireCreds(callerId, job.getSquadId());
        List<JiraPublishStep> steps = stepRepository.findByJobIdOrderByPositionAsc(jobId);
        for (JiraPublishStep s : steps) {
            if (JiraPublishStep.ERROR.equals(s.getStatus())) {
                s.setStatus(JiraPublishStep.PENDING);
                s.setErrorMessage(null);
            }
        }
        steps = stepRepository.saveAll(steps);
        run(job, steps, creds);
        return toDto(job, stepRepository.findByJobIdOrderByPositionAsc(job.getId()));
    }

    public List<JiraPublishDtos.Job> listForRoom(String roomId, String callerId, String callerRole) {
        PokerRoom room = requireRoom(roomId);
        requireRoomParticipant(room, callerId, callerRole);
        List<JiraPublishJob> jobs = jobRepository.findByRoomIdOrderByCreatedAtDesc(roomId);
        if (jobs.isEmpty()) return List.of();
        Map<String, List<JiraPublishStep>> byJob = new HashMap<>();
        for (JiraPublishStep s : stepRepository.findByJobIdInOrderByJobIdAscPositionAsc(jobs.stream().map(JiraPublishJob::getId).toList())) {
            byJob.computeIfAbsent(s.getJobId(), k -> new ArrayList<>()).add(s);
        }
        List<JiraPublishDtos.Job> out = new ArrayList<>();
        for (JiraPublishJob j : jobs) out.add(toDto(j, byJob.getOrDefault(j.getId(), List.of())));
        return out;
    }

    // ------------------------------------------------------------------ execução

    /**
     * Executa os passos pendentes na ordem. Para no primeiro erro (os seguintes dependem dele: a sub precisa
     * existir antes do comentário). Sem transação envolvendo as chamadas ao Jira: cada passo grava seu estado
     * assim que termina, então uma queda no meio não perde o que já foi feito.
     */
    void run(JiraPublishJob job, List<JiraPublishStep> steps, Creds creds) {
        job.setStatus(JiraPublishJob.RUNNING);
        job.setErrorMessage(null);
        jobRepository.save(job);
        broadcast(job, steps);

        Map<String, JiraPublishStep> byId = new HashMap<>();
        for (JiraPublishStep s : steps) byId.put(s.getId(), s);
        List<JsonNode> existingSubtasks = null;

        for (JiraPublishStep step : steps) {
            if (JiraPublishStep.DONE.equals(step.getStatus()) || JiraPublishStep.SKIPPED.equals(step.getStatus())) continue;
            step.setAttempts((step.getAttempts() == null ? 0 : step.getAttempts()) + 1);
            try {
                switch (step.getKind()) {
                    case JiraPublishStep.RENAME_PARENT -> {
                        String desired = step.getPayload().path("summary").asText();
                        JsonNode current = jiraService.getIssue(creds.domain(), creds.token(), step.getTargetKey(), List.of("summary"));
                        String currentSummary = current.path("fields").path("summary").asText("");
                        if (!desired.trim().equals(currentSummary.trim())) {
                            jiraService.updateIssueSummary(creds.domain(), creds.token(), step.getTargetKey(), desired);
                        }
                        step.setResultKey(step.getTargetKey());
                    }
                    case JiraPublishStep.CREATE_SUBTASK -> {
                        if (existingSubtasks == null) {
                            existingSubtasks = jiraService.listSubtasks(creds.domain(), creds.token(), job.getParentKey());
                        }
                        String summary = step.getPayload().path("summary").asText();
                        String type = step.getPayload().path("issueType").asText();
                        String found = findExisting(existingSubtasks, summary, type);
                        if (found != null) {
                            step.setResultKey(found); // já existe: não duplica
                        } else {
                            Double hours = step.getPayload().hasNonNull("estimateHours") ? step.getPayload().get("estimateHours").asDouble() : null;
                            String created = jiraService.createSubtask(creds.domain(), creds.token(),
                                    projectKeyOf(job.getParentKey()), job.getParentKey(), type, summary, hours);
                            step.setResultKey(created);
                            ObjectNode fake = objectMapper.createObjectNode();
                            fake.put("key", created);
                            fake.putObject("fields").put("summary", summary).putObject("issuetype").put("name", type);
                            existingSubtasks.add(fake);
                        }
                    }
                    case JiraPublishStep.ADD_COMMENT -> {
                        String target = step.getTargetKey();
                        if (target == null && step.getDependsOn() != null) {
                            JiraPublishStep dep = byId.get(step.getDependsOn());
                            target = dep == null ? null : dep.getResultKey();
                        }
                        if (target == null) {
                            throw new JiraService.JiraWriteException(409, "A sub-tarefa deste comentário ainda não foi criada.");
                        }
                        step.setTargetKey(target);
                        String id = jiraService.addComment(creds.domain(), creds.token(), target, step.getPayload().path("body").asText());
                        step.setResultKey(id);
                    }
                    default -> throw new JiraService.JiraWriteException(400, "Tipo de passo desconhecido: " + step.getKind());
                }
                step.setStatus(JiraPublishStep.DONE);
                step.setErrorMessage(null);
                stepRepository.save(step);
            } catch (JiraService.JiraWriteException e) {
                step.setStatus(JiraPublishStep.ERROR);
                step.setErrorMessage(e.getMessage());
                stepRepository.save(step);
                job.setStatus(JiraPublishJob.ERROR);
                job.setErrorMessage("Passo " + (step.getPosition() + 1) + " (" + describeKind(step.getKind()) + "): " + e.getMessage());
                jobRepository.save(job);
                broadcast(job, steps);
                return;
            } catch (RuntimeException e) {
                log.error("Erro inesperado publicando no Jira (job {}, passo {})", job.getId(), step.getPosition(), e);
                step.setStatus(JiraPublishStep.ERROR);
                step.setErrorMessage("Erro inesperado no servidor.");
                stepRepository.save(step);
                job.setStatus(JiraPublishJob.ERROR);
                job.setErrorMessage("Passo " + (step.getPosition() + 1) + " (" + describeKind(step.getKind()) + "): erro inesperado no servidor.");
                jobRepository.save(job);
                broadcast(job, steps);
                return;
            }
        }
        job.setStatus(JiraPublishJob.DONE);
        job.setFinishedAt(LocalDateTime.now());
        jobRepository.save(job);
        broadcast(job, steps);
    }

    // ------------------------------------------------------------------ validação

    List<JiraPublishStep> validateSteps(List<JiraPublishDtos.Step> in, String parentKey, Set<String> allowedTypes) {
        if (in == null || in.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nenhum passo para publicar.");
        }
        if (in.size() > MAX_STEPS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Envio com passos demais (máx. " + MAX_STEPS + ").");
        }
        List<JiraPublishStep> out = new ArrayList<>();
        Map<Integer, String> createIdsByIndex = new HashMap<>();
        boolean renamed = false;
        for (int i = 0; i < in.size(); i++) {
            JiraPublishDtos.Step s = in.get(i);
            String kind = s.getKind() == null ? "" : s.getKind().trim();
            if (!KINDS.contains(kind)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Passo " + (i + 1) + ": ação não permitida.");
            }
            ObjectNode payload = objectMapper.createObjectNode();
            JiraPublishStep.JiraPublishStepBuilder b = JiraPublishStep.builder()
                    .id(UUID.randomUUID().toString()).position(i).kind(kind).status(JiraPublishStep.PENDING).attempts(0);
            switch (kind) {
                case JiraPublishStep.RENAME_PARENT -> {
                    if (renamed) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Só um passo pode renomear a issue pai.");
                    renamed = true;
                    String summary = s.getSummary() == null ? "" : s.getSummary().trim();
                    if (summary.isEmpty() || summary.length() > 255) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Novo título da pai vazio ou maior que 255 caracteres.");
                    }
                    payload.put("summary", summary);
                    b.targetKey(parentKey); // nunca outra issue
                }
                case JiraPublishStep.CREATE_SUBTASK -> {
                    String type = s.getIssueType() == null ? "" : s.getIssueType().trim();
                    if (type.isEmpty() || !allowedTypes.contains(type.toLowerCase(Locale.ROOT))) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Passo " + (i + 1) + ": o tipo \"" + type + "\" não está entre as sub-tarefas configuradas da squad.");
                    }
                    String summary = s.getSummary() == null ? "" : s.getSummary().trim();
                    if (summary.isEmpty()) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Passo " + (i + 1) + ": título da sub-tarefa vazio.");
                    }
                    if (s.getEstimateHours() != null && (s.getEstimateHours() < 0 || s.getEstimateHours() > 10_000)) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Passo " + (i + 1) + ": horas inválidas.");
                    }
                    payload.put("issueType", type);
                    payload.put("summary", truncate(summary, 255));
                    if (s.getEstimateHours() != null && s.getEstimateHours() > 0) payload.put("estimateHours", s.getEstimateHours());
                    if (s.getRole() != null) payload.put("role", s.getRole());
                    if (s.getTemplateId() != null) payload.put("templateId", s.getTemplateId());
                    b.targetKey(parentKey);
                    JiraPublishStep built = b.payload(payload).build();
                    createIdsByIndex.put(i, built.getId());
                    out.add(built);
                    continue;
                }
                case JiraPublishStep.ADD_COMMENT -> {
                    String body = s.getComment() == null ? "" : s.getComment().trim();
                    if (body.isEmpty()) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Passo " + (i + 1) + ": comentário vazio.");
                    }
                    if (body.length() > MAX_COMMENT_LENGTH) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Passo " + (i + 1) + ": comentário longo demais.");
                    }
                    // Comentário só em sub-tarefa criada por este mesmo envio ou na própria issue pai: nunca em issue avulsa.
                    Integer dep = s.getDependsOnIndex();
                    if (dep != null) {
                        if (!createIdsByIndex.containsKey(dep)) {
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                    "Passo " + (i + 1) + ": o comentário precisa apontar para uma sub-tarefa criada neste envio.");
                        }
                        b.dependsOn(createIdsByIndex.get(dep));
                    } else {
                        String target = s.getTargetKey() == null ? "" : s.getTargetKey().trim();
                        if (!parentKey.equalsIgnoreCase(target)) {
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                    "Passo " + (i + 1) + ": comentário sem sub-tarefa só pode ir na issue pai " + parentKey + ".");
                        }
                        b.targetKey(parentKey);
                    }
                    payload.put("body", body);
                }
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Passo " + (i + 1) + ": ação não permitida.");
            }
            out.add(b.payload(payload).build());
        }
        return out;
    }

    // ------------------------------------------------------------------ apoio

    static String stripEstimateSuffix(String title) {
        if (title == null) return "";
        Matcher m = ESTIMATE_SUFFIX.matcher(title);
        String t = m.find() && m.start() > 0 ? title.substring(0, m.start()) : title;
        return t.trim();
    }

    static String estimateSuffix(Double devHours, Double qaHours) {
        List<String> parts = new ArrayList<>();
        if (devHours != null) parts.add("DEV " + formatHours(devHours) + "h");
        if (qaHours != null) parts.add("QA " + formatHours(qaHours) + "h");
        return parts.isEmpty() ? "" : " (" + String.join(" / ", parts) + ")";
    }

    static String formatHours(double h) {
        if (h == Math.rint(h)) return String.valueOf((long) h);
        return String.valueOf(h).replace('.', ',');
    }

    /** "10", "10h", "1,5", "1.5h" → horas; "?", "☕", vazio → null. */
    static Double parseHours(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT).replace("h", "").replace(',', '.').trim();
        if (s.isEmpty()) return null;
        try {
            double v = Double.parseDouble(s);
            return v < 0 ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Map<String, String> vars(String parentKey, String baseSummary, String type, String role, Double hours, String notes) {
        Map<String, String> v = new HashMap<>();
        v.put("pai", parentKey);
        v.put("resumo", baseSummary == null ? "" : baseSummary);
        v.put("tipo", type == null ? "" : type);
        v.put("papel", role == null ? "" : role);
        v.put("horas", hours == null ? "" : formatHours(hours) + "h");
        v.put("notas", notes == null ? "" : notes);
        return v;
    }

    static String render(String template, Map<String, String> vars) {
        if (template == null) return "";
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    static String projectKeyOf(String issueKey) {
        int dash = issueKey.lastIndexOf('-');
        return dash > 0 ? issueKey.substring(0, dash) : issueKey;
    }

    private static String findExisting(List<JsonNode> subtasks, String summary, String type) {
        if (subtasks == null) return null;
        for (JsonNode s : subtasks) {
            String sum = s.path("fields").path("summary").asText("");
            String typ = s.path("fields").path("issuetype").path("name").asText("");
            if (sum.trim().equalsIgnoreCase(summary.trim()) && typ.trim().equalsIgnoreCase(type.trim())) {
                return s.path("key").asText(null);
            }
        }
        return null;
    }

    private List<SquadSubtaskTemplate> templatesFor(String squadId, String parentType) {
        List<SquadSubtaskTemplate> all = templateRepository.findBySquadIdIgnoreCaseOrderByPositionAsc(squadId);
        List<SquadSubtaskTemplate> out = new ArrayList<>();
        for (SquadSubtaskTemplate t : all) {
            if (!Boolean.TRUE.equals(t.getActive())) continue;
            if (isBlank(t.getParentIssueType()) || (parentType != null && t.getParentIssueType().trim().equalsIgnoreCase(parentType.trim()))) {
                out.add(t);
            }
        }
        return out;
    }

    private Creds requireCreds(String callerId, String squadId) {
        UserJiraConfig cfg = callerId == null ? null : userJiraConfigRepository.findById(callerId).orElse(null);
        if (cfg == null || isBlank(cfg.getToken())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Configure seu token do Jira no perfil antes de publicar.");
        }
        String domain = cfg.getDomain();
        if (isBlank(domain)) {
            Squad squad = squadRepository.findById(squadId).orElse(null);
            domain = squad == null ? null : squad.getJiraDomain();
        }
        if (isBlank(domain)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nenhum domínio do Jira configurado.");
        }
        return new Creds(domain, cfg.getToken());
    }

    private PokerRoom requireRoom(String roomId) {
        return roomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sala não encontrada."));
    }

    private JsonNode requireIssue(PokerRoom room, String issueId) {
        JsonNode queue = room.getIssuesQueue();
        if (queue != null && queue.isArray() && issueId != null) {
            for (JsonNode item : queue) {
                if (item.isObject() && issueId.equals(item.path("id").asText(null))) return item;
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tarefa não encontrada na sala.");
    }

    /** Mesma regra do PokerService: criador, participante com entrada registrada, ou ADMIN/LEAD. */
    private void requireRoomParticipant(PokerRoom room, String callerId, String callerRole) {
        if ("ADMIN".equalsIgnoreCase(callerRole) || "LEAD".equalsIgnoreCase(callerRole)) return;
        if (callerId != null && callerId.equals(room.getCreatorId())) return;
        if (callerId != null && participantRepository.existsById(room.getId() + "_" + callerId)) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Apenas participantes da sala podem publicar no Jira.");
    }

    private void broadcast(JiraPublishJob job, List<JiraPublishStep> steps) {
        try {
            if (job.getRoomId() != null) webSocketHandler.broadcastEvent(job.getRoomId(), WS_EVENT, toDto(job, steps));
        } catch (RuntimeException e) {
            log.warn("Falha ao avisar a sala {} sobre a publicação no Jira: {}", job.getRoomId(), e.getMessage());
        }
    }

    JiraPublishDtos.Job toDto(JiraPublishJob job, List<JiraPublishStep> steps) {
        Map<String, Integer> indexById = new HashMap<>();
        for (JiraPublishStep s : steps) indexById.put(s.getId(), s.getPosition());
        List<JiraPublishDtos.Step> dtoSteps = new ArrayList<>();
        for (JiraPublishStep s : steps) {
            JsonNode p = s.getPayload();
            dtoSteps.add(JiraPublishDtos.Step.builder()
                    .index(s.getPosition()).kind(s.getKind()).targetKey(s.getTargetKey())
                    .dependsOnIndex(s.getDependsOn() == null ? null : indexById.get(s.getDependsOn()))
                    .summary(p.hasNonNull("summary") ? p.get("summary").asText() : null)
                    .issueType(p.hasNonNull("issueType") ? p.get("issueType").asText() : null)
                    .estimateHours(p.hasNonNull("estimateHours") ? p.get("estimateHours").asDouble() : null)
                    .role(p.hasNonNull("role") ? p.get("role").asText() : null)
                    .templateId(p.hasNonNull("templateId") ? p.get("templateId").asText() : null)
                    .comment(p.hasNonNull("body") ? p.get("body").asText() : null)
                    .status(s.getStatus()).resultKey(s.getResultKey()).errorMessage(s.getErrorMessage()).attempts(s.getAttempts())
                    .build());
        }
        return JiraPublishDtos.Job.builder()
                .id(job.getId()).squadId(job.getSquadId()).roomId(job.getRoomId()).issueId(job.getIssueId())
                .parentKey(job.getParentKey()).parentSummary(job.getParentSummary()).requestedBy(job.getRequestedBy())
                .status(job.getStatus()).errorMessage(job.getErrorMessage())
                .createdAt(job.getCreatedAt()).updatedAt(job.getUpdatedAt()).finishedAt(job.getFinishedAt())
                .steps(dtoSteps)
                .build();
    }

    private static String describeKind(String kind) {
        return switch (kind) {
            case JiraPublishStep.RENAME_PARENT -> "renomear a issue pai";
            case JiraPublishStep.CREATE_SUBTASK -> "criar sub-tarefa";
            case JiraPublishStep.ADD_COMMENT -> "comentar";
            default -> kind;
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    /** Horas do papel: devPoints/qaPoints, ou rolePoints[dev|qa]. */
    private static String roleValue(JsonNode issue, String role) {
        String direct = text(issue, role + "Points");
        if (direct != null) return direct;
        JsonNode rp = issue.path("rolePoints");
        if (rp.isObject()) {
            String v = text(rp, role);
            if (v != null) return v;
            v = text(rp, role.toUpperCase(Locale.ROOT));
            if (v != null) return v;
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
