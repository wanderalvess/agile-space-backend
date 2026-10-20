package com.agilespace.backend.service;

import com.agilespace.backend.domain.JiraIssueTypeCache;
import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.domain.SquadIssueSnapshot;
import com.agilespace.backend.domain.UserJiraConfig;
import com.agilespace.backend.dto.JiraSearchRequest;
import com.agilespace.backend.repository.JiraIssueTypeCacheRepository;
import com.agilespace.backend.repository.SquadIssueSnapshotRepository;
import com.agilespace.backend.repository.UserJiraConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;

/**
 * Sync do backlog do Sprint Planner, separado do sync da sprint (SquadSyncService) para não mudar Quadro/Cronograma.
 * <ol>
 *   <li>Pais "Refinamento Concluído" do projeto da squad (status por id, quando configurado, ou pelo nome);</li>
 *   <li>Sobra: pais não concluídas da sprint ativa;</li>
 *   <li>Subtarefas: {@code parent in (...)} em lotes de 50 chaves.</li>
 * </ol>
 * Campos próprios (sem aggregate*), paginação por startAt e nova tentativa em 429 (além da que o JiraService já faz).
 * Grava em squad_issue_snapshots com in_planner_backlog = true: linha nova (fora de sprint sincronizada) entra com
 * sprint_id = PLANNER_BACKLOG; linha que já existe pelo sync da sprint só ganha as colunas do planner.
 * Só roda por pedido explícito (POST do backlog), no máximo uma vez a cada 5 min por squad.
 */
@Service
@Slf4j
public class PlannerBacklogSyncService {

    static final int SUBTASK_PARENT_BATCH = 50;
    static final int PAGE_SIZE = 100;
    static final int MAX_PAGES = 20;
    static final int MAX_PARENTS = 1000;
    static final int MAX_429_RETRIES = 3;
    static final Duration TYPE_CACHE_TTL = Duration.ofHours(24);

    static final List<String> FIELDS = List.of("summary", "issuetype", "status", "parent",
            "timeoriginalestimate", "timeestimate", "assignee");

    private static final Pattern PROJECT_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,49}");
    private static final Pattern ISSUE_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]*-\\d+");
    private static final Pattern SPRINT_ID = Pattern.compile("\\d{1,18}");
    private static final Pattern STATUS_ID = Pattern.compile("\\d{1,18}");

    public record SyncResult(String syncedAt, int parents, int carryOver, int subtasks, boolean truncated) {}

    record JiraIssue(String key, String summary, String typeId, String typeName, boolean subtask,
                     String statusId, String statusName, String statusCategory,
                     String parentKey, String parentSummary, Long ownEstimateSec, Long ownRemainingSec,
                     String assigneeId, String assigneeName) {}

    private final SquadService squadService;
    private final JiraService jiraService;
    private final UserJiraConfigRepository userJiraConfigRepository;
    private final SquadIssueSnapshotRepository snapshotRepository;
    private final JiraIssueTypeCacheRepository typeCacheRepository;
    private final ObjectMapper objectMapper;
    private final String refinementStatusName;
    private final String refinementStatusId;
    private final Duration minInterval;

    private final Map<String, Instant> lastSyncBySquad = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();
    private LongConsumer sleeper = ms -> {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };

    public PlannerBacklogSyncService(SquadService squadService, JiraService jiraService,
                                     UserJiraConfigRepository userJiraConfigRepository,
                                     SquadIssueSnapshotRepository snapshotRepository,
                                     JiraIssueTypeCacheRepository typeCacheRepository, ObjectMapper objectMapper,
                                     @Value("${app.planner.backlog.status-name:Refinamento Concluído}") String refinementStatusName,
                                     @Value("${app.planner.backlog.status-id:}") String refinementStatusId,
                                     @Value("${app.planner.backlog.min-interval-sec:300}") long minIntervalSec) {
        this.squadService = squadService;
        this.jiraService = jiraService;
        this.userJiraConfigRepository = userJiraConfigRepository;
        this.snapshotRepository = snapshotRepository;
        this.typeCacheRepository = typeCacheRepository;
        this.objectMapper = objectMapper;
        this.refinementStatusName = refinementStatusName == null ? "" : refinementStatusName.trim();
        this.refinementStatusId = refinementStatusId == null ? "" : refinementStatusId.trim();
        this.minInterval = Duration.ofSeconds(Math.max(0, minIntervalSec));
    }

    void setClock(Clock clock) { this.clock = clock; }
    void setSleeper(LongConsumer sleeper) { this.sleeper = sleeper; }

    public String refinementStatusName() { return refinementStatusName; }
    public String refinementStatusId() { return refinementStatusId; }

    // ===================== Limite por squad =====================

    /** Reserva a janela de 5 min da squad ou lança 429 com o tempo que falta. */
    void acquireRateLimit(String squadId) {
        Instant now = clock.instant();
        Instant[] blockedUntil = new Instant[1];
        lastSyncBySquad.compute(squadId, (k, last) -> {
            if (last != null && last.plus(minInterval).isAfter(now)) {
                blockedUntil[0] = last.plus(minInterval);
                return last;
            }
            return now;
        });
        if (blockedUntil[0] != null) {
            long secs = Math.max(1, Duration.between(now, blockedUntil[0]).toSeconds());
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "O backlog desta squad foi sincronizado há pouco. Tente de novo em " + secs + " s (limite: 1 sincronização a cada "
                            + Math.max(1, minInterval.toMinutes()) + " min por squad).");
        }
    }

    // ===================== Sync =====================

    public SyncResult sync(String squadId, String callerUserId) {
        acquireRateLimit(squadId);
        try {
            return doSync(squadId, callerUserId);
        } catch (ResponseStatusException e) {
            // Erro de configuração (sem token/projeto) não queima a janela de 5 min: a pessoa corrige e tenta de novo.
            if (e.getStatusCode().value() == 400) lastSyncBySquad.remove(squadId);
            throw e;
        }
    }

    private SyncResult doSync(String squadId, String callerUserId) {
        Squad squad = squadService.getSquad(squadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Squad sem configuração do Jira."));
        String projectKey = squad.getJiraProjectKey();
        if (projectKey == null || !PROJECT_KEY.matcher(projectKey.trim()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A squad não tem um projeto do Jira válido configurado.");
        }
        projectKey = projectKey.trim();
        String[] creds = resolveCredentials(squad, callerUserId);
        String domain = creds[0];
        String token = creds[1];

        boolean[] truncated = {false};
        Map<String, JiraIssue> parents = new LinkedHashMap<>();
        for (JiraIssue i : fetchAll(domain, token, refinementJql(projectKey), truncated)) parents.putIfAbsent(i.key(), i);
        int carryOver = 0;
        for (JiraIssue i : fetchAll(domain, token, carryOverJql(projectKey, squad.getActiveSprintId()), truncated)) {
            if (parents.putIfAbsent(i.key(), i) == null) carryOver++;
        }
        if (parents.size() > MAX_PARENTS) {
            log.warn("[squad:{}] backlog do planner com {} pais; mantendo as primeiras {}.", squadId, parents.size(), MAX_PARENTS);
            truncated[0] = true;
            Map<String, JiraIssue> capped = new LinkedHashMap<>();
            for (JiraIssue i : parents.values()) {
                if (capped.size() >= MAX_PARENTS) break;
                capped.put(i.key(), i);
            }
            parents = capped;
        }

        List<String> parentKeys = parents.keySet().stream().filter(k -> ISSUE_KEY.matcher(k).matches()).toList();
        Map<String, JiraIssue> subtasks = new LinkedHashMap<>();
        for (List<String> batch : chunk(parentKeys, SUBTASK_PARENT_BATCH)) {
            for (JiraIssue i : fetchAll(domain, token, "parent in (" + String.join(",", batch) + ")", truncated)) {
                if (!parents.containsKey(i.key())) subtasks.putIfAbsent(i.key(), i);
            }
        }

        refreshTypeCacheIfStale(squadId, domain, token);

        String syncedAt = clock.instant().toString();
        List<JiraIssue> all = new ArrayList<>(parents.values());
        all.addAll(subtasks.values());
        persist(squadId, all, syncedAt);
        return new SyncResult(syncedAt, parents.size(), carryOver, subtasks.size(), truncated[0]);
    }

    String refinementJql(String projectKey) {
        String statusClause;
        if (!refinementStatusId.isEmpty() && STATUS_ID.matcher(refinementStatusId).matches()) {
            statusClause = "status = " + refinementStatusId;
        } else {
            statusClause = "status = \"" + refinementStatusName.replace("\\", "").replace("\"", "") + "\"";
        }
        return "project = \"" + projectKey + "\" AND " + statusClause
                + " AND issuetype not in subTaskIssueTypes() ORDER BY Rank ASC";
    }

    static String carryOverJql(String projectKey, String activeSprintId) {
        String sprintClause = activeSprintId != null && SPRINT_ID.matcher(activeSprintId.trim()).matches()
                ? "sprint = " + activeSprintId.trim() : "sprint in openSprints()";
        return "project = \"" + projectKey + "\" AND " + sprintClause
                + " AND statusCategory != Done AND issuetype not in subTaskIssueTypes() ORDER BY Rank ASC";
    }

    private String[] resolveCredentials(Squad squad, String callerUserId) {
        UserJiraConfig cfg = callerUserId == null ? null : userJiraConfigRepository.findById(callerUserId).orElse(null);
        if (cfg == null || cfg.getToken() == null || cfg.getToken().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Configure seu Token de Acesso do Jira em Conexão Jira antes de sincronizar.");
        }
        // Mesmo critério do sync da sprint: o token só vai para o domínio salvo na conta de quem sincroniza.
        String domain = cfg.getDomain() != null && !cfg.getDomain().isBlank() ? cfg.getDomain() : squad.getJiraDomain();
        if (domain == null || domain.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Domínio do Jira não configurado.");
        }
        return new String[]{domain, cfg.getToken()};
    }

    // ===================== Jira =====================

    List<JiraIssue> fetchAll(String domain, String token, String jql, boolean[] truncated) {
        List<JiraIssue> out = new ArrayList<>();
        int startAt = 0;
        int total = Integer.MAX_VALUE;
        int page = 0;
        while (out.size() < total && page < MAX_PAGES) {
            JiraSearchRequest req = new JiraSearchRequest();
            req.setDomain(domain);
            req.setToken(token);
            req.setJql(jql);
            req.setStartAt(startAt);
            req.setMaxResults(PAGE_SIZE);
            req.setFields(FIELDS);
            req.setIncludeChangelog(false);
            JsonNode root = searchWithRetry(req);
            total = root.path("total").asInt(0);
            int got = 0;
            for (JsonNode n : root.path("issues")) {
                out.add(parse(n));
                got++;
            }
            if (got == 0) break;
            startAt += got;
            page++;
        }
        if (out.size() < total && page >= MAX_PAGES) truncated[0] = true;
        return out;
    }

    private JsonNode searchWithRetry(JiraSearchRequest req) {
        for (int attempt = 0; ; attempt++) {
            ResponseEntity<String> resp;
            String retryAfter = null;
            try {
                resp = jiraService.searchIssues(req);
            } catch (HttpStatusCodeException e) {
                if (e.getStatusCode().value() != 429) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Erro ao consultar o Jira: " + e.getStatusCode());
                }
                resp = ResponseEntity.status(429).build();
                if (e.getResponseHeaders() != null) retryAfter = e.getResponseHeaders().getFirst("Retry-After");
            }
            int status = resp.getStatusCode().value();
            if (status == 429) {
                if (retryAfter == null) retryAfter = resp.getHeaders().getFirst("Retry-After");
                if (attempt >= MAX_429_RETRIES) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "O Jira limitou as consultas (429). Tente sincronizar o backlog de novo em alguns minutos.");
                }
                long waitMs = retryAfterMillis(retryAfter, attempt);
                log.warn("Backlog do planner: Jira respondeu 429, nova tentativa {}/{} em {} ms", attempt + 1, MAX_429_RETRIES, waitMs);
                sleeper.accept(waitMs);
                continue;
            }
            if (!resp.getStatusCode().is2xxSuccessful()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Erro ao consultar o Jira: " + resp.getStatusCode());
            }
            try {
                return objectMapper.readTree(resp.getBody() == null ? "{}" : resp.getBody());
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Resposta inválida do Jira ao buscar o backlog.");
            }
        }
    }

    static long retryAfterMillis(String retryAfter, int attempt) {
        if (retryAfter != null) {
            try {
                return Math.min(30_000L, Math.max(0, Long.parseLong(retryAfter.trim())) * 1000L);
            } catch (NumberFormatException ignored) {
                // data HTTP: cai no backoff
            }
        }
        return Math.min(30_000L, 1000L << attempt);
    }

    static JiraIssue parse(JsonNode n) {
        JsonNode f = n.path("fields");
        JsonNode type = f.path("issuetype");
        JsonNode status = f.path("status");
        JsonNode parent = f.path("parent");
        JsonNode assignee = f.path("assignee");
        String parentKey = parent.isTextual() ? parent.asText("") : parent.path("key").asText("");
        String assigneeId = firstNonBlank(assignee.path("accountId").asText(""), assignee.path("key").asText(""),
                assignee.path("name").asText(""));
        return new JiraIssue(n.path("key").asText(""), f.path("summary").asText(""),
                blankToNull(type.path("id").asText("")), type.path("name").asText(""), type.path("subtask").asBoolean(false),
                blankToNull(status.path("id").asText("")), status.path("name").asText(""),
                firstNonBlank(status.path("statusCategory").path("key").asText(""), "unknown"),
                blankToNull(parentKey), blankToNull(parent.path("fields").path("summary").asText("")),
                SquadSyncService.ownSeconds(f, "timeoriginalestimate"), SquadSyncService.ownSeconds(f, "timeestimate"),
                blankToNull(assigneeId), blankToNull(assignee.path("displayName").asText("")));
    }

    private void refreshTypeCacheIfStale(String squadId, String domain, String token) {
        try {
            List<JiraIssueTypeCache> current = typeCacheRepository.findBySquadIdOrderByNameAsc(squadId);
            Instant now = clock.instant();
            boolean fresh = !current.isEmpty() && current.stream().allMatch(c -> {
                try {
                    return Instant.parse(c.getFetchedAt()).plus(TYPE_CACHE_TTL).isAfter(now);
                } catch (Exception e) {
                    return false;
                }
            });
            if (fresh) return;
            String clean = domain.replaceFirst("^https?://", "").replaceAll("/+$", "");
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + token.trim());
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            ResponseEntity<String> resp = jiraService.exchangeGet(new URI("https://" + clean + "/rest/api/2/issuetype"), new HttpEntity<>(headers));
            if (resp == null || !resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) return;
            JsonNode arr = objectMapper.readTree(resp.getBody());
            if (!arr.isArray() || arr.isEmpty()) return;
            String fetchedAt = now.toString();
            Map<String, JiraIssueTypeCache> rows = new LinkedHashMap<>();
            for (JsonNode t : arr) {
                String id = t.path("id").asText("");
                String name = t.path("name").asText("");
                if (id.isBlank() || id.length() > 32 || name.isBlank()) continue;
                rows.put(id, JiraIssueTypeCache.builder().squadId(squadId).typeId(id)
                        .name(name.length() > 255 ? name.substring(0, 255) : name)
                        .subtask(t.path("subtask").asBoolean(false)).fetchedAt(fetchedAt).build());
            }
            if (rows.isEmpty()) return;
            typeCacheRepository.deleteBySquadId(squadId);
            typeCacheRepository.saveAll(rows.values());
        } catch (Exception e) {
            // Cache de tipos é enriquecimento da tela de mapeamento; não derruba o sync do backlog.
            log.warn("[squad:{}] não foi possível atualizar os tipos de issue do Jira: {}", squadId, e.getMessage());
        }
    }

    // ===================== Persistência =====================

    /**
     * Marca o backlog novo e desmarca o que saiu dele. Linhas criadas só pelo backlog (sprint PLANNER_BACKLOG) que
     * saíram são apagadas; linhas do sync da sprint só perdem a marca (nunca são apagadas por aqui).
     */
    void persist(String squadId, List<JiraIssue> issues, String syncedAt) {
        Set<String> keys = new LinkedHashSet<>();
        for (JiraIssue i : issues) if (i.key() != null && !i.key().isBlank()) keys.add(i.key());
        Map<String, SquadIssueSnapshot> existing = new HashMap<>();
        for (List<String> batch : chunk(new ArrayList<>(keys), 500)) {
            for (SquadIssueSnapshot s : snapshotRepository.findBySquadIdAndJiraKeyIn(squadId, batch)) existing.put(s.getJiraKey(), s);
        }
        Map<String, String> parentTitles = new HashMap<>();
        for (JiraIssue i : issues) parentTitles.put(i.key(), i.summary());

        List<SquadIssueSnapshot> toSave = new ArrayList<>();
        for (JiraIssue i : issues) {
            if (i.key() == null || i.key().isBlank()) continue;
            SquadIssueSnapshot row = existing.get(i.key());
            boolean backlogOnly = row == null || SquadIssueSnapshot.PLANNER_BACKLOG_SPRINT_ID.equals(row.getSprintId());
            if (row == null) {
                row = SquadIssueSnapshot.builder().dbId(squadId + "_" + i.key()).squadId(squadId).jiraKey(i.key())
                        .sprintId(SquadIssueSnapshot.PLANNER_BACKLOG_SPRINT_ID).sprintName("").syncedAt(syncedAt).build();
            }
            if (backlogOnly) {
                row.setTitle(i.summary());
                row.setType(i.typeName());
                row.setStatus(i.statusName());
                row.setStatusCategory(i.statusCategory());
                row.setParentKey(i.parentKey());
                row.setParentTitle(i.parentSummary() != null ? i.parentSummary() : parentTitles.get(i.parentKey()));
                row.setAssigneeId(i.assigneeId());
                row.setAssigneeName(i.assigneeName());
                row.setEstimateSec(i.ownEstimateSec());
                row.setRemainingSec(i.ownRemainingSec());
            }
            row.setIssueTypeId(i.typeId());
            row.setStatusId(i.statusId());
            row.setOwnOriginalEstimateSec(i.ownEstimateSec());
            row.setOwnRemainingSec(i.ownRemainingSec());
            row.setInPlannerBacklog(true);
            row.setPlannerSyncedAt(syncedAt);
            toSave.add(row);
        }

        List<String> toDelete = new ArrayList<>();
        for (SquadIssueSnapshot prior : snapshotRepository.findBySquadIdAndInPlannerBacklogTrue(squadId)) {
            if (keys.contains(prior.getJiraKey())) continue;
            if (SquadIssueSnapshot.PLANNER_BACKLOG_SPRINT_ID.equals(prior.getSprintId())) {
                toDelete.add(prior.getJiraKey());
            } else {
                prior.setInPlannerBacklog(false);
                toSave.add(prior);
            }
        }
        if (!toSave.isEmpty()) snapshotRepository.saveAll(toSave);
        for (List<String> batch : chunk(toDelete, 500)) snapshotRepository.deleteBySquadIdAndJiraKeyIn(squadId, batch);
    }

    // ===================== util =====================

    static <T> List<List<T>> chunk(List<T> list, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) out.add(list.subList(i, Math.min(list.size(), i + size)));
        return out;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private static String firstNonBlank(String... vs) {
        for (String v : vs) if (v != null && !v.isBlank()) return v;
        return "";
    }
}
