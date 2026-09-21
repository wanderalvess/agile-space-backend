package com.agilespace.backend.service;

import com.agilespace.backend.domain.*;
import com.agilespace.backend.dto.JiraSearchRequest;
import com.agilespace.backend.repository.UserJiraConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Motor de sync do Squad, portado de agile-space-frontend/src/store/useSquadStore.ts
 * pra rodar no servidor em vez de no navegador (ver plano de unificação Squad Pulse
 * + jiradash, Fase 2). Reaproveita JiraService (chamadas HTTP já endurecidas: guarda
 * SSRF, TLS com fallback, retry em 429) e os métodos @Transactional de SquadService
 * pra persistir — esta classe não é @Transactional: uma sync percorre potencialmente
 * dezenas de chamadas HTTP ao Jira, e prender isso numa transação de banco só
 * seguraria conexões/locks pelo tempo todo sem necessidade.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SquadSyncService {

    private static final int STALE_THRESHOLD_DAYS = 3;
    private static final int DUE_SOON_THRESHOLD_DAYS = 3;
    private static final int SPRINT_WORKDAYS_ASSUMED = 10;
    private static final int RECONCILE_INTERVAL_DEFAULT_HOURS = 6;
    private static final String UNMAPPED_SPRINT_ID = "UNMAPPED";
    private static final int BATCH_CHUNK_SIZE = 400;
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 20; // teto de segurança: 2000 issues

    private static final List<String> SQUAD_SYNC_FIELDS_BASE = List.of(
            "summary", "issuetype", "status", "created", "updated", "duedate",
            "assignee", "parent", "timeoriginalestimate", "timeestimate", "timespent",
            "aggregatetimeoriginalestimate", "aggregatetimeestimate", "aggregatetimespent",
            "customfield_10015", "customfield_10014", "startDate",
            "customfield_10005", "customfield_10008", "customfield_10007",
            "customfield_10010", "customfield_10020", "customfield_10016",
            "customfield_10100", "customfield_10101", "customfield_10004",
            "subtasks", "resolutiondate"
    );
    private static final List<String> SQUAD_SYNC_FIELDS_WITH_WORKLOG =
            concat(SQUAD_SYNC_FIELDS_BASE, "worklog");

    private static final String[] SPRINT_FIELD_CANDIDATES = {
            "customfield_10005", "customfield_10008", "customfield_10007",
            "customfield_10010", "customfield_10020", "customfield_10016",
            "customfield_10100", "customfield_10101", "customfield_10004", "sprint"
    };

    private static final Pattern BUG_TYPE_PATTERN = Pattern.compile("\\b(bug|defeito|erro)\\b", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter JIRA_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private final SquadService squadService;
    private final JiraService jiraService;
    private final SquadCapacityService squadCapacityService;
    private final UserJiraConfigRepository userJiraConfigRepository;
    private final ObjectMapper objectMapper;

    private static List<String> concat(List<String> base, String extra) {
        List<String> out = new ArrayList<>(base);
        out.add(extra);
        return out;
    }

    // ===================== API pública =====================

    public void syncSquad(String squadId, String callerUserId, boolean forceFull) {
        Squad config = ensureSquadConfig(squadId);
        JiraCreds creds = resolveCredentials(config, callerUserId);
        config = withDiscoveredSprintFieldId(config, creds.domain(), creds.token());

        boolean rankingEnabled = Boolean.TRUE.equals(config.getRankingEnabled());
        boolean sprintFieldConfigured = config.getSprintFieldId() != null && !config.getSprintFieldId().isBlank();
        int reconcileIntervalHours = config.getReconcileIntervalHours() != null ? config.getReconcileIntervalHours() : RECONCILE_INTERVAL_DEFAULT_HOURS;
        boolean isMigrationSync = config.getSchemaVersion() == null;

        boolean rankingJustEnabled = rankingEnabled && isRankingJustEnabled(config);

        boolean isFull = forceFull || !sprintFieldConfigured;
        if (!isFull) isFull = isBlank(config.getLastSyncAt());
        if (!isFull) isFull = isBlank(config.getLastFullReconcileAt());
        if (!isFull) isFull = isBlank(config.getActiveSprintId());
        if (!isFull) {
            Instant lastFull = parseInstantFlexible(config.getLastFullReconcileAt());
            isFull = lastFull == null || Duration.between(lastFull, Instant.now()).toMillis() > reconcileIntervalHours * 3_600_000L;
        }
        if (!isFull) isFull = "error".equals(config.getLastSyncStatus());
        if (!isFull) isFull = rankingJustEnabled;

        List<String> baseFields = rankingEnabled ? SQUAD_SYNC_FIELDS_WITH_WORKLOG : SQUAD_SYNC_FIELDS_BASE;
        List<String> fields = sprintFieldConfigured ? concat(baseFields, config.getSprintFieldId()) : baseFields;

        String deltaJql = isFull ? config.getSyncJql()
                : "(" + config.getSyncJql() + ") AND updated >= \"" + formatForJql(subtractMinutesToIso(config.getLastSyncAt(), 2)) + "\"";

        FetchResult fetched = fetchAllIssues(creds.domain(), creds.token(), deltaJql, fields, config.getSprintFieldId(), isFull);
        List<ParsedJiraIssue> issues = fetched.issues();
        boolean truncated = fetched.truncated();

        String syncedAt = Instant.now().toString();

        MappingResult mapped = mapIssuesToSnapshots(issues, syncedAt);
        List<SquadIssueSnapshot> snapshots = mapped.snapshots();
        Map<String, SprintMeta> sprintMeta = mapped.sprintMeta();

        boolean hasAnySprint = !sprintMeta.isEmpty() || sprintFieldConfigured;
        applyParentSprintFallback(squadId, snapshots, hasAnySprint);
        normalizeBlankSprintIds(snapshots, hasAnySprint);

        String effectiveSprintId = hasAnySprint ? pickEffectiveSprint(sprintMeta) : UNMAPPED_SPRINT_ID;

        if (!isFull && !isBlank(config.getActiveSprintId())
                && !effectiveSprintId.equals(config.getActiveSprintId()) && !UNMAPPED_SPRINT_ID.equals(effectiveSprintId)) {
            isFull = true;
            FetchResult full = fetchAllIssues(creds.domain(), creds.token(), config.getSyncJql(), fields, config.getSprintFieldId(), true);
            issues = full.issues();
            truncated = truncated || full.truncated();
            mapped = mapIssuesToSnapshots(issues, syncedAt);
            snapshots = mapped.snapshots();
            sprintMeta = mapped.sprintMeta();
            normalizeBlankSprintIds(snapshots, true);
            effectiveSprintId = pickEffectiveSprint(sprintMeta);
        }

        reconcileSprintMetaWithJira(sprintMeta, creds.domain(), creds.token());
        applyReconciledSprintNames(snapshots, sprintMeta);

        if (truncated) {
            log.warn("[squad:{}] sync truncado no teto de páginas — pode haver issues fora do escopo sincronizado.", squadId);
        }

        Map<String, Double> defaultCapacityHolder = new HashMap<>();
        defaultCapacityHolder.put("v", config.getDefaultDailyCapacityHours() != null ? config.getDefaultDailyCapacityHours() : 6.0);
        Function<String, Double> capacityFn = seedRosterAndBuildCapacityFn(squadId, snapshots, syncedAt, defaultCapacityHolder.get("v"));

        if (isMigrationSync) {
            runMigrationSafetyCheck(squadId, snapshots);
        }

        Map<String, ParsedJiraIssue> issuesByKey = issues.stream().collect(Collectors.toMap(ParsedJiraIssue::key, i -> i, (a, b) -> a));
        Map<String, List<SquadIssueSnapshot>> groups = snapshots.stream()
                .collect(Collectors.groupingBy(SquadIssueSnapshot::getSprintId, LinkedHashMap::new, Collectors.toList()));

        List<ObjectNode> sprintHistory = loadSprintHistory(config);
        int totalIssueCountForActiveSprint = 0;
        SquadMetricsRollup rollupForActiveSprint = null;
        List<SquadMemberMetric> memberMetricsForActiveSprint = new ArrayList<>();

        for (Map.Entry<String, List<SquadIssueSnapshot>> entry : groups.entrySet()) {
            String sprintId = entry.getKey();
            List<SquadIssueSnapshot> groupSnapshots = entry.getValue();

            PartitionMergeResult merge = mergePartition(squadId, sprintId, groupSnapshots, isMigrationSync, isFull);
            List<SquadIssueSnapshot> mergedSnapshots = merge.merged();
            List<String> keysToDelete = merge.keysToDelete();

            persistIssueBatch(squadId, groupSnapshots, keysToDelete);

            List<ParsedJiraIssue> groupRawIssues = groupSnapshots.stream()
                    .map(s -> issuesByKey.get(s.getJiraKey())).filter(Objects::nonNull).toList();
            List<SquadIssueWorklogCache> mergedWorklogCache = rankingEnabled
                    ? syncWorklogCache(squadId, sprintId, groupRawIssues, keysToDelete, isMigrationSync, isFull, syncedAt)
                    : List.of();

            SprintMeta meta = sprintMeta.get(sprintId);
            int sprintWorkdays = meta != null ? countWorkdays(meta.startDate, meta.endDate) : 0;
            SquadMetricsRollup rollupForGroup = buildRollup(squadId, sprintId, mergedSnapshots, groupRawIssues, meta, sprintWorkdays, syncedAt);
            squadService.saveRollup(rollupForGroup);

            if (sprintId.equals(effectiveSprintId)) {
                rollupForActiveSprint = rollupForGroup;
                totalIssueCountForActiveSprint = mergedSnapshots.size();
                if (rankingEnabled) {
                    int workdays = sprintWorkdays > 0 ? sprintWorkdays : SPRINT_WORKDAYS_ASSUMED;
                    memberMetricsForActiveSprint = isFull
                            ? computeMemberMetrics(groupRawIssues, true, workdays, capacityFn)
                            : computeMemberMetricsFromCache(mergedSnapshots, mergedWorklogCache, workdays, capacityFn);
                }
            }

            upsertSprintHistoryEntry(sprintHistory, sprintId, meta, sprintWorkdays, syncedAt);
        }

        closePreviousSprintIfRolledOver(sprintHistory, config.getActiveSprintId(), effectiveSprintId, isFull, syncedAt);

        squadService.batchUpsertMemberMetrics(squadId, rankingEnabled ? memberMetricsForActiveSprint : List.of());

        if (rollupForActiveSprint != null) {
            squadService.batchUpsertDailySnapshots(squadId, List.of(SquadDailySnapshot.builder()
                    .dbId(squadId + "_" + todayStr()).squadId(squadId).snapshotDate(todayStr())
                    .totalIssues(rollupForActiveSprint.getTotalIssues()).doneIssues(rollupForActiveSprint.getDoneIssues())
                    .inProgressIssues(rollupForActiveSprint.getInProgressIssues()).bugIssues(rollupForActiveSprint.getBugIssues())
                    .staleIssues(rollupForActiveSprint.getStaleIssues()).loggedSec(rollupForActiveSprint.getLoggedTotalSec())
                    .syncedAt(syncedAt).build()));
        }

        ArrayNode historyArray = objectMapper.createArrayNode();
        sprintHistory.forEach(historyArray::add);
        squadService.saveSquad(Squad.builder()
                .id(squadId).lastSyncAt(syncedAt).lastSyncBy(callerUserId).lastSyncStatus("success")
                .lastSyncIssueCount(totalIssueCountForActiveSprint)
                .lastSyncError(truncated ? "Sync truncado no teto de páginas (2000 issues) — pode haver issues fora do escopo." : "")
                .activeSprintId(effectiveSprintId)
                .lastFullReconcileAt(isFull ? syncedAt : (config.getLastFullReconcileAt() != null ? config.getLastFullReconcileAt() : syncedAt))
                .schemaVersion(2).sprintHistory(historyArray)
                .build());
    }

    public void forceResyncSprint(String squadId, String callerUserId, String targetSprintId) {
        Squad config = squadService.getSquad(squadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Squad sem configuração."));
        JiraCreds creds = resolveCredentials(config, callerUserId);
        config = withDiscoveredSprintFieldId(config, creds.domain(), creds.token());

        boolean rankingEnabled = Boolean.TRUE.equals(config.getRankingEnabled());
        List<String> baseFields = rankingEnabled ? SQUAD_SYNC_FIELDS_WITH_WORKLOG : SQUAD_SYNC_FIELDS_BASE;
        List<String> fields = !isBlank(config.getSprintFieldId()) ? concat(baseFields, config.getSprintFieldId()) : baseFields;

        String targetJql = Pattern.compile("sprint\\s+in\\s+openSprints\\(\\)", Pattern.CASE_INSENSITIVE)
                .matcher(config.getSyncJql()).find()
                ? Pattern.compile("sprint\\s+in\\s+openSprints\\(\\)", Pattern.CASE_INSENSITIVE)
                        .matcher(config.getSyncJql()).replaceFirst("sprint = " + Matcher.quoteReplacement(targetSprintId))
                : "project = " + config.getJiraProjectKey() + " AND sprint = " + targetSprintId;

        FetchResult fetched = fetchAllIssues(creds.domain(), creds.token(), targetJql, fields, config.getSprintFieldId(), true);
        if (fetched.truncated()) {
            log.warn("[squad:{}] forceResyncSprint truncado no teto de páginas — pode haver issues fora do escopo sincronizado.", squadId);
        }
        String syncedAt = Instant.now().toString();
        ParsedSprint sprintInfo = fetchSprintInfo(creds.domain(), creds.token(), targetSprintId);

        List<SquadIssueSnapshot> snapshots = new ArrayList<>();
        for (ParsedJiraIssue issue : fetched.issues()) {
            SquadIssueSnapshot snap = toSnapshotBase(issue, syncedAt);
            snap.setSprintId(targetSprintId);
            snap.setSprintName(sprintInfo != null ? sprintInfo.name() : "");
            snapshots.add(snap);
        }
        applyOrderIndex(fetched.issues(), snapshots);

        List<SquadIssueSnapshot> existingPartition = squadService.getIssues(squadId, targetSprintId);
        Set<String> newKeys = snapshots.stream().map(SquadIssueSnapshot::getJiraKey).collect(Collectors.toSet());
        List<String> keysToDelete = existingPartition.stream().map(SquadIssueSnapshot::getJiraKey).filter(k -> !newKeys.contains(k)).toList();

        persistIssueBatch(squadId, snapshots, keysToDelete);

        if (rankingEnabled) {
            List<SquadIssueWorklogCache> entries = fetched.issues().stream().map(issue -> SquadIssueWorklogCache.builder()
                    .dbId(squadId + "_" + issue.key()).squadId(squadId).jiraKey(issue.key()).sprintId(targetSprintId)
                    .worklogByAuthor(worklogByAuthorNode(issue.worklogs())).worklogAuthorNames(worklogAuthorNamesNode(issue.worklogs()))
                    .updatedAtJira(issue.updatedAtJira()).syncedAt(syncedAt).build()).toList();
            for (List<SquadIssueWorklogCache> group : chunk(entries, BATCH_CHUNK_SIZE)) {
                squadService.batchUpsertWorklogCache(squadId, group);
            }
        }

        SprintMeta targetMeta = sprintInfo != null ? new SprintMeta(sprintInfo) : null;
        int targetWorkdays = targetMeta != null ? countWorkdays(targetMeta.startDate, targetMeta.endDate) : 0;
        SquadMetricsRollup rollup = buildRollup(squadId, targetSprintId, snapshots, fetched.issues(), targetMeta, targetWorkdays, syncedAt);
        squadService.saveRollup(rollup);

        List<ObjectNode> sprintHistory = loadSprintHistory(config);
        for (ObjectNode entry : sprintHistory) {
            if (targetSprintId.equals(entry.path("sprintId").asText(""))) {
                entry.put("lastSyncedAt", syncedAt);
                break;
            }
        }
        ArrayNode historyArray = objectMapper.createArrayNode();
        sprintHistory.forEach(historyArray::add);
        squadService.saveSquad(Squad.builder().id(squadId).sprintHistory(historyArray).build());
    }

    // ===================== Config / credenciais =====================

    private Squad ensureSquadConfig(String squadId) {
        Squad config = squadService.getSquad(squadId).orElse(null);
        String defaultProjKey = (config != null && config.getJiraProjectKey() != null && !"MISSI".equals(config.getJiraProjectKey()))
                ? config.getJiraProjectKey()
                : ("MISSI".equals(squadId) ? "DDWMISSI" : squadId);

        boolean needsDefault = config == null || isBlank(config.getJiraProjectKey()) || "MISSI".equals(config.getJiraProjectKey());
        if (!needsDefault) return config;

        String existingSyncJql = config != null ? config.getSyncJql() : null;
        String jql = (isBlank(existingSyncJql) || existingSyncJql.contains("project = \"MISSI\"") || existingSyncJql.contains("project = MISSI"))
                ? String.format("project = \"%s\" AND sprint in openSprints()", defaultProjKey)
                : existingSyncJql;

        Squad update = Squad.builder()
                .id(squadId).name(squadId).jiraProjectKey(defaultProjKey).syncJql(jql)
                .defaultDailyCapacityHours(6.0).rankingEnabled(false)
                .updatedAt(Instant.now().toString())
                .build();
        try {
            return squadService.saveSquad(update);
        } catch (Exception e) {
            return update;
        }
    }

    private record JiraCreds(String domain, String token) {}

    private JiraCreds resolveCredentials(Squad squad, String callerUserId) {
        UserJiraConfig cfg = userJiraConfigRepository.findById(callerUserId).orElse(null);
        if (cfg == null || isBlank(cfg.getToken())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Configure seu Token de Acesso do Jira em Conexão Jira antes de sincronizar.");
        }
        String domain = !isBlank(squad.getJiraDomain()) ? squad.getJiraDomain() : cfg.getDomain();
        return new JiraCreds(domain, cfg.getToken());
    }

    private boolean isRankingJustEnabled(Squad config) {
        if (isBlank(config.getLastFullReconcileAt())) return true;
        if (isBlank(config.getRankingEnabledAt())) return false;
        Instant lastFull = parseInstantFlexible(config.getLastFullReconcileAt());
        Instant rankingEnabledAt = parseInstantFlexible(config.getRankingEnabledAt());
        return lastFull != null && rankingEnabledAt != null && lastFull.isBefore(rankingEnabledAt);
    }

    private Squad withDiscoveredSprintFieldId(Squad config, String domain, String token) {
        if (!isBlank(config.getSprintFieldId())) return config;
        String discovered = discoverSprintFieldId(domain, token);
        if (discovered == null) return config;
        try {
            squadService.saveSquad(Squad.builder().id(config.getId()).sprintFieldId(discovered).build());
        } catch (Exception ignored) {
            // falha aqui só mantém o comportamento antigo (UNMAPPED_SPRINT_ID), sem quebrar o sync
        }
        config.setSprintFieldId(discovered);
        return config;
    }

    private String discoverSprintFieldId(String domain, String token) {
        try {
            ResponseEntity<String> resp = jiraService.getFields(domain, token);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) return null;
            JsonNode fields = objectMapper.readTree(resp.getBody());
            if (!fields.isArray()) return null;
            String byName = null;
            for (JsonNode f : fields) {
                if ("com.pyxis.greenhopper.jira:gh-sprint".equals(f.path("schema").path("custom").asText(""))) {
                    String id = f.path("id").asText("");
                    if (!id.isBlank()) return id;
                }
                if (byName == null && "sprint".equalsIgnoreCase(f.path("name").asText("").trim())) {
                    byName = f.path("id").asText("");
                }
            }
            return isBlank(byName) ? null : byName;
        } catch (Exception e) {
            return null;
        }
    }

    private ParsedSprint fetchSprintInfo(String domain, String token, String sprintId) {
        try {
            ResponseEntity<String> resp = jiraService.getSprint(domain, token, sprintId);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) return null;
            JsonNode data = objectMapper.readTree(resp.getBody());
            if (data.path("id").isMissingNode()) return null;
            return new ParsedSprint(data.path("id").asText(""), data.path("name").asText(""),
                    data.path("state").asText(""), data.path("startDate").asText(""), data.path("endDate").asText(""));
        } catch (Exception e) {
            return null;
        }
    }

    private void reconcileSprintMetaWithJira(Map<String, SprintMeta> sprintMeta, String domain, String token) {
        for (String id : sprintMeta.keySet()) {
            if (isBlank(id) || UNMAPPED_SPRINT_ID.equals(id)) continue;
            ParsedSprint info = fetchSprintInfo(domain, token, id);
            if (info == null) continue;
            SprintMeta existing = sprintMeta.get(id);
            if (!isBlank(info.name())) existing.name = info.name();
            if (!isBlank(info.state())) existing.state = info.state();
            if (!isBlank(info.startDate())) existing.startDate = info.startDate();
            if (!isBlank(info.endDate())) existing.endDate = info.endDate();
        }
    }

    // ===================== Fetch + parsing do Jira =====================

    private record FetchResult(List<ParsedJiraIssue> issues, boolean truncated) {}

    private record WorklogEntry(String authorId, String authorName, long timeSpentSeconds) {}

    private record ParsedJiraIssue(
            String key, String type, boolean isBug, String status, String statusCategory,
            long estimateSec, long remainingSec, long loggedSec,
            String updatedAtJira, String createdAtJira, String resolutionDate, String dueDate,
            String targetStart, String targetEnd, boolean datesAreInferred,
            String assigneeId, String assigneeName, String parentKey, String parentTitle,
            JsonNode sprintRaw, List<String> subtaskKeys, List<WorklogEntry> worklogs,
            List<ChangelogEntry> changelog
    ) {}

    // field usa nomes diferentes conforme o campo do Jira: status manda fromString/toString
    // (nome de exibição); Sprint manda from/to (IDs separados por vírgula) — por isso os 4.
    private record ChangelogItem(String field, String from, String fromDisplay, String to, String toDisplay) {}
    private record ChangelogEntry(Instant created, List<ChangelogItem> items) {}

    private FetchResult fetchAllIssues(String domain, String token, String jql, List<String> fields, String sprintFieldId) {
        return fetchAllIssues(domain, token, jql, fields, sprintFieldId, false);
    }

    // includeChangelog liga expand=changelog no Jira — payload bem mais pesado (histórico
    // inteiro de transições por issue), então só o sync FULL pede isso, pra cycle
    // time/scope churn (ver buildRollup) — nunca no sync delta. Ver plano de unificação
    // Squad Pulse + jiradash, Fase 7.
    private FetchResult fetchAllIssues(String domain, String token, String jql, List<String> fields, String sprintFieldId, boolean includeChangelog) {
        List<ParsedJiraIssue> all = new ArrayList<>();
        int total = Integer.MAX_VALUE;
        int startAt = 0;
        int page = 0;
        while (all.size() < total && page < MAX_PAGES) {
            JiraSearchRequest req = new JiraSearchRequest();
            req.setDomain(domain);
            req.setToken(token);
            req.setJql(jql);
            req.setMaxResults(PAGE_SIZE);
            req.setStartAt(startAt);
            req.setFields(fields);
            req.setIncludeChangelog(includeChangelog);
            ResponseEntity<String> resp = jiraService.searchIssues(req);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Erro ao consultar o Jira: " + resp.getStatusCode() + " " + resp.getBody());
            }
            JsonNode root;
            try {
                root = objectMapper.readTree(resp.getBody());
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Resposta inválida do Jira ao buscar issues.");
            }
            total = root.path("total").asInt(0);
            List<ParsedJiraIssue> pageIssues = new ArrayList<>();
            for (JsonNode issueNode : root.path("issues")) pageIssues.add(parseIssue(issueNode, sprintFieldId));
            if (pageIssues.isEmpty()) break;
            all.addAll(pageIssues);
            startAt += pageIssues.size();
            page++;
        }
        boolean truncated = all.size() < total && page >= MAX_PAGES;
        return new FetchResult(all, truncated);
    }

    private ParsedJiraIssue parseIssue(JsonNode issueNode, String sprintFieldId) {
        String key = issueNode.path("key").asText("");
        JsonNode fields = issueNode.path("fields");

        String type = fields.path("issuetype").path("name").asText("");
        String status = fields.path("status").path("name").asText("");
        String statusCategory = fields.path("status").path("statusCategory").path("key").asText("");
        if (statusCategory.isBlank()) statusCategory = "unknown";
        boolean isBug = BUG_TYPE_PATTERN.matcher(type).find();

        long estimateSec = firstNonZeroLong(fields, "aggregatetimeoriginalestimate", "timeoriginalestimate");
        long remainingSec = firstNonZeroLong(fields, "aggregatetimeestimate", "timeestimate");
        long loggedSec = firstNonZeroLong(fields, "aggregatetimespent", "timespent");

        String updatedAtJira = toSafeString(fields.get("updated"));
        String resolutionDate = toSafeString(fields.get("resolutiondate"));
        String dueDate = toSafeString(fields.get("duedate"));
        String created = toSafeString(fields.get("created"));

        String realTargetStart = firstNonBlank(toSafeString(fields.get("customfield_10015")), toSafeString(fields.get("startDate")), toSafeString(fields.get("target_start")));
        String realTargetEnd = firstNonBlank(toSafeString(fields.get("customfield_10014")), dueDate, toSafeString(fields.get("target_end")));
        String targetStart = !realTargetStart.isBlank() ? realTargetStart : substring10(created);
        String targetEnd = !realTargetEnd.isBlank() ? realTargetEnd : substring10(updatedAtJira);
        // toSquadIssueSnapshotBase aplica uma segunda camada de fallback por cima desta.
        targetStart = firstNonBlank(targetStart, dueDate, substring10(updatedAtJira));
        targetEnd = firstNonBlank(targetEnd, dueDate, substring10(updatedAtJira));
        boolean datesAreInferred = realTargetStart.isBlank() || realTargetEnd.isBlank();

        JsonNode assignee = fields.path("assignee");
        String assigneeId = firstNonBlank(assignee.path("accountId").asText(""), assignee.path("key").asText(""));
        String assigneeName = assignee.path("displayName").asText("");

        JsonNode parent = fields.path("parent");
        String parentKey = parent.isTextual() ? parent.asText("") : parent.path("key").asText("");
        String parentTitle = parent.path("fields").path("summary").asText("");

        List<String> subtaskKeys = new ArrayList<>();
        for (JsonNode st : fields.path("subtasks")) {
            String k = st.path("key").asText("");
            if (!k.isBlank()) subtaskKeys.add(k);
        }

        List<WorklogEntry> worklogs = new ArrayList<>();
        for (JsonNode wl : fields.path("worklog").path("worklogs")) {
            JsonNode author = wl.path("author");
            String wid = firstNonBlank(author.path("accountId").asText(""), author.path("key").asText(""), author.path("name").asText(""));
            if (wid.isBlank()) continue;
            String wname = author.path("displayName").asText("");
            worklogs.add(new WorklogEntry(wid, wname.isBlank() ? wid : wname, wl.path("timeSpentSeconds").asLong(0)));
        }

        JsonNode sprintRaw = extractSprintRaw(fields, sprintFieldId);

        List<ChangelogEntry> changelog = new ArrayList<>();
        for (JsonNode history : issueNode.path("changelog").path("histories")) {
            Instant historyCreated = parseInstantFlexible(history.path("created").asText(""));
            if (historyCreated == null) continue;
            List<ChangelogItem> items = new ArrayList<>();
            for (JsonNode item : history.path("items")) {
                items.add(new ChangelogItem(
                        item.path("field").asText(""),
                        item.hasNonNull("from") ? item.path("from").asText("") : null,
                        item.hasNonNull("fromString") ? item.path("fromString").asText("") : null,
                        item.hasNonNull("to") ? item.path("to").asText("") : null,
                        item.hasNonNull("toString") ? item.path("toString").asText("") : null));
            }
            changelog.add(new ChangelogEntry(historyCreated, items));
        }

        return new ParsedJiraIssue(key, type, isBug, status, statusCategory, estimateSec, remainingSec, loggedSec,
                updatedAtJira, created, resolutionDate, dueDate, targetStart, targetEnd, datesAreInferred,
                assigneeId, assigneeName, parentKey, parentTitle, sprintRaw, subtaskKeys, worklogs, changelog);
    }

    private static JsonNode extractSprintRaw(JsonNode fields, String sprintFieldId) {
        if (!isBlank(sprintFieldId)) {
            JsonNode v = fields.get(sprintFieldId);
            if (v != null && !v.isNull()) return v;
        }
        for (String f : SPRINT_FIELD_CANDIDATES) {
            JsonNode v = fields.get(f);
            if (looksLikeSprintArray(v)) return v;
        }
        Iterator<Map.Entry<String, JsonNode>> it = fields.fields();
        while (it.hasNext()) {
            JsonNode v = it.next().getValue();
            if (looksLikeSprintArray(v)) return v;
        }
        return null;
    }

    private static boolean looksLikeSprintArray(JsonNode v) {
        if (v == null || !v.isArray() || v.isEmpty()) return false;
        JsonNode first = v.get(0);
        if (first.isTextual()) {
            String s = first.asText();
            return s.contains("com.atlassian.greenhopper.service.sprint.Sprint") || s.contains("state=") || (s.contains("[id=") && s.contains("startDate="));
        }
        if (first.isObject()) {
            return first.has("startDate") || first.has("state") || first.has("name");
        }
        return false;
    }

    // ===================== Mapeamento issue -> snapshot / sprint =====================

    private record MappingResult(List<SquadIssueSnapshot> snapshots, Map<String, SprintMeta> sprintMeta) {}

    private static final class SprintMeta {
        final String id;
        String name, state, startDate, endDate;
        int count;
        SprintMeta(ParsedSprint p) { this.id = p.id(); this.name = p.name(); this.state = p.state(); this.startDate = p.startDate(); this.endDate = p.endDate(); }
    }

    private record ParsedSprint(String id, String name, String state, String startDate, String endDate) {}

    private MappingResult mapIssuesToSnapshots(List<ParsedJiraIssue> issues, String syncedAt) {
        Map<String, SprintMeta> sprintMeta = new LinkedHashMap<>();
        List<SquadIssueSnapshot> snapshots = new ArrayList<>();
        for (ParsedJiraIssue issue : issues) {
            ParsedSprint parsed = parseSprintFieldValue(issue.sprintRaw());
            SquadIssueSnapshot snap = toSnapshotBase(issue, syncedAt);
            if (parsed != null) {
                snap.setSprintId(parsed.id());
                snap.setSprintName(parsed.name());
                sprintMeta.computeIfAbsent(parsed.id(), k -> new SprintMeta(parsed)).count++;
            } else {
                snap.setSprintId("");
                snap.setSprintName("");
            }
            snapshots.add(snap);
        }
        applyOrderIndex(issues, snapshots);
        return new MappingResult(snapshots, sprintMeta);
    }

    private static ParsedSprint parseSprintFieldValue(JsonNode raw) {
        if (raw == null || raw.isNull()) return null;
        List<JsonNode> arr = new ArrayList<>();
        if (raw.isArray()) raw.forEach(arr::add); else arr.add(raw);

        List<ParsedSprint> parsed = new ArrayList<>();
        for (JsonNode v : arr) {
            if (v.isObject()) {
                parsed.add(new ParsedSprint(v.path("id").asText(""), v.path("name").asText(""),
                        v.path("state").asText(""), v.path("startDate").asText(""), v.path("endDate").asText("")));
            } else if (v.isTextual()) {
                String s = v.asText();
                parsed.add(new ParsedSprint(extractGreenhopperField(s, "id"), extractGreenhopperField(s, "name"),
                        extractGreenhopperField(s, "state"), extractGreenhopperField(s, "startDate"), extractGreenhopperField(s, "endDate")));
            }
        }
        if (parsed.isEmpty()) return null;
        ParsedSprint active = parsed.stream().filter(p -> "ACTIVE".equalsIgnoreCase(p.state())).findFirst().orElse(null);
        ParsedSprint target = active != null ? active : parsed.get(parsed.size() - 1);
        if (isBlank(target.startDate()) || "<null>".equals(target.startDate()) || isBlank(target.endDate()) || "<null>".equals(target.endDate())) {
            return null;
        }
        return target;
    }

    private static String extractGreenhopperField(String raw, String key) {
        Matcher m = Pattern.compile(key + "=([^,\\]]+)").matcher(raw);
        return m.find() ? m.group(1) : "";
    }

    private void applyParentSprintFallback(String squadId, List<SquadIssueSnapshot> snapshots, boolean hasAnySprint) {
        if (!hasAnySprint) return;
        Map<String, SquadIssueSnapshot> byKey = new HashMap<>();
        for (SquadIssueSnapshot s : snapshots) byKey.put(s.getJiraKey(), s);
        for (SquadIssueSnapshot s : snapshots) {
            if (!isBlank(s.getSprintId()) || isBlank(s.getParentKey())) continue;
            SquadIssueSnapshot sibling = byKey.get(s.getParentKey());
            if (sibling != null && !isBlank(sibling.getSprintId())) {
                s.setSprintId(sibling.getSprintId());
                s.setSprintName(sibling.getSprintName());
                continue;
            }
            squadService.getIssueByKey(squadId, s.getParentKey()).ifPresent(parentSnap -> {
                if (!isBlank(parentSnap.getSprintId())) {
                    s.setSprintId(parentSnap.getSprintId());
                    s.setSprintName(parentSnap.getSprintName());
                }
            });
        }
    }

    private static void normalizeBlankSprintIds(List<SquadIssueSnapshot> snapshots, boolean hasAnySprint) {
        for (SquadIssueSnapshot s : snapshots) {
            if (!hasAnySprint) {
                s.setSprintId(UNMAPPED_SPRINT_ID);
                s.setSprintName("");
            } else if (isBlank(s.getSprintId())) {
                s.setSprintId(UNMAPPED_SPRINT_ID);
            }
        }
    }

    private static void applyReconciledSprintNames(List<SquadIssueSnapshot> snapshots, Map<String, SprintMeta> sprintMeta) {
        for (SquadIssueSnapshot s : snapshots) {
            if (isBlank(s.getSprintId())) continue;
            SprintMeta meta = sprintMeta.get(s.getSprintId());
            if (meta != null) s.setSprintName(meta.name);
        }
    }

    private static String pickEffectiveSprint(Map<String, SprintMeta> sprintMeta) {
        if (sprintMeta.isEmpty()) return UNMAPPED_SPRINT_ID;
        List<SprintMeta> active = sprintMeta.values().stream().filter(s -> "ACTIVE".equalsIgnoreCase(s.state)).toList();
        List<SprintMeta> candidates = !active.isEmpty() ? active : new ArrayList<>(sprintMeta.values());
        List<SprintMeta> byStart = candidates.stream().filter(s -> !isBlank(s.startDate))
                .sorted((a, b) -> b.startDate.compareTo(a.startDate)).toList();
        if (!byStart.isEmpty()) return byStart.get(0).id;
        List<SprintMeta> byCount = new ArrayList<>(candidates);
        byCount.sort((a, b) -> b.count - a.count);
        return byCount.isEmpty() ? UNMAPPED_SPRINT_ID : byCount.get(0).id;
    }

    private static SquadIssueSnapshot toSnapshotBase(ParsedJiraIssue issue, String syncedAt) {
        return SquadIssueSnapshot.builder()
                .dbId(null)
                .jiraKey(issue.key())
                .type(issue.type())
                .isBug(issue.isBug())
                .status(issue.status())
                .statusCategory(isBlank(issue.statusCategory()) ? "unknown" : issue.statusCategory())
                .estimateSec(issue.estimateSec())
                .remainingSec(issue.remainingSec())
                .loggedSec(issue.loggedSec())
                .updatedAtJira(issue.updatedAtJira())
                .createdAtJira(issue.createdAtJira())
                .resolutionDate(issue.resolutionDate())
                .staleSinceDays((int) daysSince(issue.updatedAtJira()))
                .dueDate(issue.dueDate())
                .targetStart(issue.targetStart())
                .targetEnd(issue.targetEnd())
                .datesAreInferred(issue.datesAreInferred())
                .assigneeId(issue.assigneeId())
                .assigneeName(issue.assigneeName())
                .parentKey(issue.parentKey())
                .parentTitle(issue.parentTitle())
                .syncedAt(syncedAt)
                .build();
    }

    private static void applyOrderIndex(List<ParsedJiraIssue> issues, List<SquadIssueSnapshot> snapshots) {
        Map<String, List<String>> siblingsByParent = new HashMap<>();
        for (ParsedJiraIssue iss : issues) {
            if (iss.subtaskKeys() != null && !iss.subtaskKeys().isEmpty()) siblingsByParent.put(iss.key(), iss.subtaskKeys());
        }
        if (siblingsByParent.isEmpty()) return;
        for (SquadIssueSnapshot s : snapshots) {
            if (isBlank(s.getParentKey())) continue;
            List<String> siblings = siblingsByParent.get(s.getParentKey());
            if (siblings == null) continue;
            int idx = siblings.indexOf(s.getJiraKey());
            if (idx >= 0) s.setOrderIndex(idx);
        }
    }

    // ===================== Roster / capacidade =====================

    private Function<String, Double> seedRosterAndBuildCapacityFn(String squadId, List<SquadIssueSnapshot> snapshots, String syncedAt, double defaultCapacity) {
        List<SquadMember> existingMembers = squadService.getMembers(squadId);
        Map<String, SquadMember> rosterMap = new HashMap<>();
        for (SquadMember m : existingMembers) rosterMap.put(m.getJiraAccountId(), m);

        Map<String, String> seenAssignees = new LinkedHashMap<>();
        for (SquadIssueSnapshot s : snapshots) {
            if (!isBlank(s.getAssigneeId())) seenAssignees.put(s.getAssigneeId(), s.getAssigneeName());
        }
        List<SquadMember> rosterSeeds = new ArrayList<>();
        for (Map.Entry<String, String> e : seenAssignees.entrySet()) {
            if (rosterMap.containsKey(e.getKey())) continue;
            SquadMember seed = SquadMember.builder()
                    .dbId(squadId + "_" + e.getKey()).squadId(squadId).jiraAccountId(e.getKey())
                    .displayName(isBlank(e.getValue()) ? e.getKey() : e.getValue())
                    .capacityHoursPerDay(defaultCapacity).updatedAt(syncedAt)
                    .build();
            rosterMap.put(e.getKey(), seed);
            rosterSeeds.add(seed);
        }
        for (List<SquadMember> group : chunk(rosterSeeds, BATCH_CHUNK_SIZE)) {
            squadService.batchUpsertMembers(squadId, group);
        }

        Map<String, SquadMember> finalRosterMap = rosterMap;
        return assigneeId -> {
            SquadMember m = finalRosterMap.get(assigneeId);
            return (m != null && m.getCapacityHoursPerDay() != null) ? m.getCapacityHoursPerDay() : defaultCapacity;
        };
    }

    // ===================== Segurança de migração =====================

    private void runMigrationSafetyCheck(String squadId, List<SquadIssueSnapshot> snapshots) {
        List<SquadIssueSnapshot> allExisting = squadService.getIssues(squadId, null);
        Set<String> allNewKeys = snapshots.stream().map(SquadIssueSnapshot::getJiraKey).collect(Collectors.toSet());
        if (snapshots.isEmpty() && !allExisting.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A sincronização voltou com 0 issues, mas havia " + allExisting.size() + " antes. Verifique a JQL — nada foi apagado.");
        }
        List<String> migrationToDelete = allExisting.stream().map(SquadIssueSnapshot::getJiraKey).filter(k -> !allNewKeys.contains(k)).toList();
        if (allExisting.size() > 5 && migrationToDelete.size() > allExisting.size() * 0.9) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A sincronização ia apagar " + migrationToDelete.size() + " de " + allExisting.size() + " issues de uma vez — abortado por segurança. Confira a JQL.");
        }
        for (List<String> group : chunk(migrationToDelete, BATCH_CHUNK_SIZE)) {
            squadService.batchDeleteIssues(squadId, group);
        }
    }

    // ===================== Merge de partição por sprint =====================

    private record PartitionMergeResult(List<SquadIssueSnapshot> merged, List<String> keysToDelete) {}

    private PartitionMergeResult mergePartition(String squadId, String sprintId, List<SquadIssueSnapshot> groupSnapshots, boolean isMigrationSync, boolean isFull) {
        if (isMigrationSync) {
            return new PartitionMergeResult(groupSnapshots, List.of());
        }
        List<SquadIssueSnapshot> existingPartition = squadService.getIssues(squadId, sprintId);
        if (isFull) {
            if (groupSnapshots.isEmpty() && !existingPartition.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A sincronização da sprint " + sprintId + " voltou com 0 issues, mas havia " + existingPartition.size() + " antes. Verifique a JQL — nada foi apagado.");
            }
            Set<String> newKeys = groupSnapshots.stream().map(SquadIssueSnapshot::getJiraKey).collect(Collectors.toSet());
            List<String> keysToDelete = existingPartition.stream().map(SquadIssueSnapshot::getJiraKey).filter(k -> !newKeys.contains(k)).toList();
            if (existingPartition.size() > 5 && keysToDelete.size() > existingPartition.size() * 0.9) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A sincronização ia apagar " + keysToDelete.size() + " de " + existingPartition.size() + " issues da sprint " + sprintId + " — abortado por segurança. Confira a JQL.");
            }
            return new PartitionMergeResult(groupSnapshots, keysToDelete);
        }
        Map<String, SquadIssueSnapshot> existingByKey = new LinkedHashMap<>();
        for (SquadIssueSnapshot s : existingPartition) existingByKey.put(s.getJiraKey(), s);
        for (SquadIssueSnapshot s : groupSnapshots) {
            if (s.getOrderIndex() == null) {
                SquadIssueSnapshot prior = existingByKey.get(s.getJiraKey());
                if (prior != null && prior.getOrderIndex() != null) s.setOrderIndex(prior.getOrderIndex());
            }
        }
        for (SquadIssueSnapshot s : groupSnapshots) existingByKey.put(s.getJiraKey(), s);
        return new PartitionMergeResult(new ArrayList<>(existingByKey.values()), List.of());
    }

    private void persistIssueBatch(String squadId, List<SquadIssueSnapshot> groupSnapshots, List<String> keysToDelete) {
        for (List<SquadIssueSnapshot> group : chunk(groupSnapshots, BATCH_CHUNK_SIZE)) {
            squadService.batchUpsertIssues(squadId, group);
        }
        for (List<String> group : chunk(keysToDelete, BATCH_CHUNK_SIZE)) {
            squadService.batchDeleteIssues(squadId, group);
        }
    }

    private List<SquadIssueWorklogCache> syncWorklogCache(String squadId, String sprintId, List<ParsedJiraIssue> groupRawIssues,
                                                            List<String> keysToDelete, boolean isMigrationSync, boolean isFull, String syncedAt) {
        List<SquadIssueWorklogCache> existing = squadService.getWorklogCache(squadId, isMigrationSync ? null : sprintId);
        List<SquadIssueWorklogCache> entries = groupRawIssues.stream().map(issue -> SquadIssueWorklogCache.builder()
                .dbId(squadId + "_" + issue.key()).squadId(squadId).jiraKey(issue.key()).sprintId(sprintId)
                .worklogByAuthor(worklogByAuthorNode(issue.worklogs())).worklogAuthorNames(worklogAuthorNamesNode(issue.worklogs()))
                .updatedAtJira(issue.updatedAtJira()).syncedAt(syncedAt).build()).toList();
        for (List<SquadIssueWorklogCache> group : chunk(entries, BATCH_CHUNK_SIZE)) {
            squadService.batchUpsertWorklogCache(squadId, group);
        }
        if (isFull) {
            for (String key : keysToDelete) squadService.deleteWorklogCacheEntry(squadId, key);
        }
        Map<String, SquadIssueWorklogCache> byKey = new LinkedHashMap<>();
        for (SquadIssueWorklogCache w : existing) byKey.put(w.getJiraKey(), w);
        for (SquadIssueWorklogCache w : entries) byKey.put(w.getJiraKey(), w);
        return new ArrayList<>(byKey.values());
    }

    private ObjectNode worklogByAuthorNode(List<WorklogEntry> worklogs) {
        Map<String, Double> hours = new LinkedHashMap<>();
        for (WorklogEntry w : worklogs) hours.merge(w.authorId(), w.timeSpentSeconds() / 3600.0, Double::sum);
        ObjectNode node = objectMapper.createObjectNode();
        hours.forEach(node::put);
        return node;
    }

    private ObjectNode worklogAuthorNamesNode(List<WorklogEntry> worklogs) {
        ObjectNode node = objectMapper.createObjectNode();
        for (WorklogEntry w : worklogs) if (!node.has(w.authorId())) node.put(w.authorId(), w.authorName());
        return node;
    }

    // ===================== Rollup =====================

    private SquadMetricsRollup buildRollup(String squadId, String sprintId, List<SquadIssueSnapshot> mergedSnapshots,
                                            List<ParsedJiraIssue> groupRawIssues, SprintMeta meta, int sprintWorkdays, String syncedAt) {
        int totalIssues = mergedSnapshots.size();
        int doneIssues = (int) mergedSnapshots.stream().filter(s -> "done".equals(s.getStatusCategory())).count();
        int inProgressIssues = (int) mergedSnapshots.stream().filter(s -> "indeterminate".equals(s.getStatusCategory())).count();
        int bugIssues = (int) mergedSnapshots.stream().filter(s -> Boolean.TRUE.equals(s.getIsBug())).count();
        int staleIssues = (int) mergedSnapshots.stream()
                .filter(s -> !"done".equals(s.getStatusCategory()) && s.getStaleSinceDays() != null && s.getStaleSinceDays() > STALE_THRESHOLD_DAYS)
                .count();
        long estimateTotalSec = mergedSnapshots.stream().mapToLong(s -> s.getEstimateSec() != null ? s.getEstimateSec() : 0).sum();
        long loggedTotalSec = mergedSnapshots.stream().mapToLong(s -> s.getLoggedSec() != null ? s.getLoggedSec() : 0).sum();
        long remainingTotalSec = mergedSnapshots.stream().mapToLong(s -> s.getRemainingSec() != null ? s.getRemainingSec() : 0).sum();

        ObjectNode byType = objectMapper.createObjectNode();
        for (SquadIssueSnapshot s : mergedSnapshots) {
            String t = s.getType() != null ? s.getType() : "";
            byType.put(t, byType.path(t).asInt(0) + 1);
        }
        ObjectNode byStatus = objectMapper.createObjectNode();
        for (SquadIssueSnapshot s : mergedSnapshots) {
            String st = s.getStatus() != null ? s.getStatus() : "";
            byStatus.put(st, byStatus.path(st).asInt(0) + 1);
        }
        List<SquadIssueSnapshot> openWithDueDate = mergedSnapshots.stream()
                .filter(s -> !"done".equals(s.getStatusCategory()) && !isBlank(s.getDueDate())).toList();
        int overdueIssues = (int) openWithDueDate.stream().filter(s -> daysUntil(s.getDueDate()) < 0).count();
        int dueSoonIssues = (int) openWithDueDate.stream()
                .filter(s -> { int d = daysUntil(s.getDueDate()); return d >= 0 && d <= DUE_SOON_THRESHOLD_DAYS; }).count();

        ObjectNode extraMetrics = objectMapper.createObjectNode();
        extraMetrics.set("byType", byType);
        extraMetrics.set("byStatus", byStatus);
        boolean hasMeta = meta != null && sprintWorkdays > 0;
        if (hasMeta) {
            extraMetrics.put("activeSprintStart", meta.startDate);
            extraMetrics.put("activeSprintEnd", meta.endDate);
            extraMetrics.set("bugEscapeRate", buildBugEscapeRate(mergedSnapshots, meta));
            Instant sprintStartInstant = parseInstantFlexible(meta.startDate);
            if (sprintStartInstant != null) {
                extraMetrics.set("cycleTimeByStatus", buildCycleTimeByStatus(squadId, sprintId, groupRawIssues));
                extraMetrics.set("scopeChurn", buildScopeChurn(groupRawIssues, sprintId, sprintStartInstant));
            }
        }

        return SquadMetricsRollup.builder()
                .dbId(squadId + "_" + sprintId).squadId(squadId).sprintId(sprintId)
                .computedAt(syncedAt)
                .totalIssues(totalIssues).doneIssues(doneIssues).inProgressIssues(inProgressIssues)
                .bugIssues(bugIssues).staleIssues(staleIssues)
                .overdueIssues(overdueIssues).dueSoonIssues(dueSoonIssues)
                .estimateTotalSec(estimateTotalSec).loggedTotalSec(loggedTotalSec).remainingTotalSec(remainingTotalSec)
                .sprintName(hasMeta ? meta.name : null)
                .workdaysTotal(hasMeta ? sprintWorkdays : null)
                .extraMetrics(extraMetrics)
                .build();
    }

    // Taxa de escape de bugs — adaptado de issue-service.js:getSprintBugs (jiradash).
    // Simplificações conscientes: sem o "Tipo do Defeito" quebrado por customfield
    // específico deste tenant (mesma razão de não copiar customfields hardcoded pra cá),
    // e horas logadas somam a partição inteira de bugs desta sprint (não só o intervalo de
    // datas exato do worklog) — o schema atual (squad_issue_worklog_cache) guarda hora
    // total por autor, não por data, então "hora gasta dentro da janela" não é
    // reconstruível com o dado que já persiste.
    private ObjectNode buildBugEscapeRate(List<SquadIssueSnapshot> mergedSnapshots, SprintMeta meta) {
        LocalDate windowStart = parseToLocalDate(meta.startDate);
        LocalDate windowEnd = parseToLocalDate(meta.endDate);
        ObjectNode node = objectMapper.createObjectNode();
        if (windowStart == null || windowEnd == null) return node;

        List<SquadIssueSnapshot> bugs = mergedSnapshots.stream().filter(s -> Boolean.TRUE.equals(s.getIsBug())).toList();
        List<SquadIssueSnapshot> createdInWindow = bugs.stream()
                .filter(s -> isWithinWindow(s.getCreatedAtJira(), windowStart, windowEnd)).toList();
        long resolvedInWindow = createdInWindow.stream()
                .filter(s -> isWithinWindow(s.getResolutionDate(), windowStart, windowEnd)).count();
        long stillOpen = createdInWindow.size() - resolvedInWindow;
        long bugHoursLoggedSec = bugs.stream().mapToLong(s -> s.getLoggedSec() != null ? s.getLoggedSec() : 0).sum();

        node.put("created", createdInWindow.size());
        node.put("resolvedInSprint", (int) resolvedInWindow);
        node.put("stillOpen", (int) stillOpen);
        node.put("loggedSec", bugHoursLoggedSec);
        return node;
    }

    private static boolean isWithinWindow(String iso, LocalDate windowStart, LocalDate windowEnd) {
        LocalDate date = parseToLocalDate(iso);
        return date != null && !date.isBefore(windowStart) && !date.isAfter(windowEnd);
    }

    // ===================== Cycle time por status (horas produtivas) =====================
    // Adaptado de issue-service.js:getStatusEvents/cycleTimeByStatus (jiradash). Só roda
    // com changelog disponível (sync FULL — ver fetchAllIssues), e só soma issues resolvidas.
    // Simplificação consciente: o original pula o status TERMINAL nomeando-o via
    // issueRules.isClosed (lista de nomes PT-BR tipo "Concluído"/"Cancelado", não portada
    // aqui) — em vez disso, esta versão nunca processa o ÚLTIMO evento de status de uma
    // issue resolvida, que por construção É o status terminal (é o motivo dela ter
    // resolutionDate) — mesmo efeito prático, sem depender de nomes de status específicos.
    private record RawStatusTransition(Instant date, String from, String to) {}
    private record StatusEvent(Instant date, String status) {}

    private List<StatusEvent> buildStatusEvents(ParsedJiraIssue issue) {
        Instant created = parseInstantFlexible(issue.createdAtJira());
        if (created == null) return List.of();
        List<RawStatusTransition> transitions = new ArrayList<>();
        for (ChangelogEntry h : issue.changelog()) {
            for (ChangelogItem item : h.items()) {
                if ("status".equals(item.field())) {
                    transitions.add(new RawStatusTransition(h.created(), item.fromDisplay(), item.toDisplay()));
                }
            }
        }
        transitions.sort(Comparator.comparing(RawStatusTransition::date));
        String initialStatus = !transitions.isEmpty() ? transitions.get(0).from() : issue.status();

        List<StatusEvent> events = new ArrayList<>();
        events.add(new StatusEvent(created, initialStatus));
        for (RawStatusTransition t : transitions) events.add(new StatusEvent(t.date(), t.to()));
        return events;
    }

    private void accumulateCycleTime(ParsedJiraIssue issue, double horasProdutivas, Map<String, Double> acc) {
        if (isBlank(issue.resolutionDate()) || !(horasProdutivas > 0)) return;
        List<StatusEvent> events = buildStatusEvents(issue);
        if (events.size() < 2) return; // sem transição nenhuma não há intervalo pra medir
        for (int i = 0; i < events.size() - 1; i++) {
            Instant start = events.get(i).date();
            Instant end = events.get(i + 1).date();
            if (!end.isAfter(start)) continue;
            String status = events.get(i).status();
            if (isBlank(status)) continue;
            double prodHours = productiveHoursBetween(start, end, horasProdutivas);
            if (prodHours > 0) acc.merge(status, prodHours, Double::sum);
        }
    }

    private ObjectNode buildCycleTimeByStatus(String squadId, String sprintId, List<ParsedJiraIssue> groupRawIssues) {
        Map<String, Double> totalsByStatus = new LinkedHashMap<>();
        Map<String, Double> capacityCache = new HashMap<>();
        for (ParsedJiraIssue issue : groupRawIssues) {
            if (isBlank(issue.assigneeId())) continue;
            double horasProdutivas = capacityCache.computeIfAbsent(issue.assigneeId(),
                    id -> squadCapacityService.resolve(squadId, sprintId, id).horasProdutivas());
            accumulateCycleTime(issue, horasProdutivas, totalsByStatus);
        }
        ObjectNode node = objectMapper.createObjectNode();
        totalsByStatus.forEach(node::put);
        return node;
    }

    // Horas produtivas entre dois instantes: intersecta cada dia útil (seg-sex) com a
    // janela 8h-18h, escala pelo prodRatio (horasProdutivas / 10h). Fins de semana e
    // horário fora da janela contam 0. Adaptado de issue-service.js:productiveHoursBetween.
    private static double productiveHoursBetween(Instant start, Instant end, double horasProdutivasPorDia) {
        if (!(horasProdutivasPorDia > 0) || !end.isAfter(start)) return 0;
        int workStartHour = 8;
        int workEndHour = 18;
        double prodRatio = horasProdutivasPorDia / (workEndHour - workStartHour);
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime a = start.atZone(zone);
        ZonedDateTime b = end.atZone(zone);

        double total = 0;
        ZonedDateTime cursor = a.toLocalDate().atStartOfDay(zone);
        while (cursor.isBefore(b)) {
            DayOfWeek dow = cursor.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                ZonedDateTime dayStart = cursor.withHour(workStartHour);
                ZonedDateTime dayEnd = cursor.withHour(workEndHour);
                ZonedDateTime segStart = dayStart.isBefore(a) ? a : dayStart;
                ZonedDateTime segEnd = dayEnd.isAfter(b) ? b : dayEnd;
                if (segEnd.isAfter(segStart)) {
                    total += Duration.between(segStart, segEnd).toMillis() / 3_600_000.0 * prodRatio;
                }
            }
            cursor = cursor.plusDays(1);
        }
        return total;
    }

    // ===================== Scope churn =====================
    // Adaptado de issue-service.js:getSprintChurn (jiradash). Classifica issues de topo
    // (sem parentKey — jiradash chama de "parents") em planejada vs. adicionada depois do
    // início, e marca carryover (veio de outra sprint antes de entrar nesta). Não inclui
    // "removidas da sprint" (extractRemovedFromCandidates no original) — isso precisa de
    // uma segunda JQL derivada (`project=X AND updated>=inicio AND sprint!=sprintAtual`,
    // filtrada por changelog), deixada pra um passo futuro.
    private record RawSprintTransition(Instant date, List<String> fromIds, List<String> toIds) {}

    private List<RawSprintTransition> sprintFieldTransitions(ParsedJiraIssue issue) {
        List<RawSprintTransition> out = new ArrayList<>();
        for (ChangelogEntry h : issue.changelog()) {
            for (ChangelogItem item : h.items()) {
                if ("Sprint".equals(item.field())) {
                    out.add(new RawSprintTransition(h.created(), parseIds(item.from()), parseIds(item.to())));
                }
            }
        }
        out.sort(Comparator.comparing(RawSprintTransition::date));
        return out;
    }

    private static List<String> parseIds(String raw) {
        if (isBlank(raw)) return List.of();
        return Arrays.stream(raw.split("[,\\s]+")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private ObjectNode buildScopeChurn(List<ParsedJiraIssue> groupRawIssues, String sprintId, Instant sprintStart) {
        int planned = 0;
        int added = 0;
        int carryover = 0;
        for (ParsedJiraIssue issue : groupRawIssues) {
            if (!isBlank(issue.parentKey())) continue; // churn é só sobre issues de topo, não subtasks
            List<RawSprintTransition> changes = sprintFieldTransitions(issue);

            Instant addedDate = null;
            for (RawSprintTransition t : changes) {
                if (t.toIds().contains(sprintId) && !t.fromIds().contains(sprintId)) {
                    addedDate = t.date();
                    break;
                }
            }
            if (addedDate == null) addedDate = parseInstantFlexible(issue.createdAtJira());
            if (addedDate == null) addedDate = sprintStart;

            if (addedDate.isAfter(sprintStart)) added++; else planned++;

            boolean isCarryover = false;
            for (RawSprintTransition t : changes) {
                if (t.date().isAfter(addedDate)) break;
                if (t.date().isBefore(addedDate)) {
                    if (t.toIds().stream().anyMatch(id -> !id.equals(sprintId))) { isCarryover = true; break; }
                } else if (t.fromIds().stream().anyMatch(id -> !id.equals(sprintId))) {
                    isCarryover = true;
                    break;
                }
            }
            if (isCarryover) carryover++;
        }

        ObjectNode node = objectMapper.createObjectNode();
        node.put("planned", planned);
        node.put("added", added);
        node.put("carryover", carryover);
        node.put("total", planned + added);
        return node;
    }

    // ===================== Métricas por pessoa =====================

    private static final class MutableMetric {
        final String assigneeId;
        final String assigneeName;
        int issuesInProgress;
        int issuesCompleted;
        double hoursLogged;
        MutableMetric(String id, String name) { this.assigneeId = id; this.assigneeName = isBlank(name) ? id : name; }
    }

    private List<SquadMemberMetric> computeMemberMetrics(List<ParsedJiraIssue> issues, boolean rankingEnabled, int workdays, Function<String, Double> capacityFn) {
        Map<String, MutableMetric> map = new LinkedHashMap<>();
        for (ParsedJiraIssue issue : issues) {
            if (!isBlank(issue.assigneeId())) {
                MutableMetric m = map.computeIfAbsent(issue.assigneeId(), id -> new MutableMetric(id, issue.assigneeName()));
                if ("indeterminate".equals(issue.statusCategory())) m.issuesInProgress++;
                if ("done".equals(issue.statusCategory())) m.issuesCompleted++;
            }
            if (rankingEnabled) {
                for (WorklogEntry wl : issue.worklogs()) {
                    MutableMetric m = map.computeIfAbsent(wl.authorId(), id -> new MutableMetric(id, wl.authorName()));
                    m.hoursLogged += wl.timeSpentSeconds() / 3600.0;
                }
            }
        }
        return finalizeMetrics(map, workdays, capacityFn);
    }

    private List<SquadMemberMetric> computeMemberMetricsFromCache(List<SquadIssueSnapshot> partitionIssues, List<SquadIssueWorklogCache> worklogCacheDocs,
                                                                    int workdays, Function<String, Double> capacityFn) {
        Map<String, MutableMetric> map = new LinkedHashMap<>();
        for (SquadIssueSnapshot s : partitionIssues) {
            if (isBlank(s.getAssigneeId())) continue;
            MutableMetric m = map.computeIfAbsent(s.getAssigneeId(), id -> new MutableMetric(id, s.getAssigneeName()));
            if ("indeterminate".equals(s.getStatusCategory())) m.issuesInProgress++;
            if ("done".equals(s.getStatusCategory())) m.issuesCompleted++;
        }
        for (SquadIssueWorklogCache w : worklogCacheDocs) {
            JsonNode byAuthor = w.getWorklogByAuthor();
            if (byAuthor == null) continue;
            JsonNode names = w.getWorklogAuthorNames();
            Iterator<Map.Entry<String, JsonNode>> it = byAuthor.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String wid = e.getKey();
                double hours = e.getValue().asDouble(0);
                String name = (names != null && names.has(wid)) ? names.get(wid).asText(wid) : wid;
                MutableMetric m = map.computeIfAbsent(wid, id -> new MutableMetric(id, name));
                m.hoursLogged += hours;
            }
        }
        return finalizeMetrics(map, workdays, capacityFn);
    }

    private List<SquadMemberMetric> finalizeMetrics(Map<String, MutableMetric> map, int workdays, Function<String, Double> capacityFn) {
        String computedAt = Instant.now().toString();
        List<SquadMemberMetric> result = new ArrayList<>();
        for (MutableMetric m : map.values()) {
            double capacityHours = capacityFn.apply(m.assigneeId) * workdays;
            result.add(SquadMemberMetric.builder()
                    .assigneeId(m.assigneeId).assigneeName(m.assigneeName)
                    .issuesInProgress(m.issuesInProgress).issuesCompleted(m.issuesCompleted)
                    .hoursLogged(m.hoursLogged).capacityHours(capacityHours)
                    .utilizationPct(capacityHours > 0 ? m.hoursLogged / capacityHours : 0)
                    .computedAt(computedAt).build());
        }
        return result;
    }

    // ===================== Histórico de sprints =====================

    private List<ObjectNode> loadSprintHistory(Squad config) {
        List<ObjectNode> list = new ArrayList<>();
        JsonNode existing = config.getSprintHistory();
        if (existing != null && existing.isArray()) {
            for (JsonNode n : existing) if (n.isObject()) list.add(n.deepCopy());
        }
        return list;
    }

    private void upsertSprintHistoryEntry(List<ObjectNode> history, String sprintId, SprintMeta meta, int sprintWorkdays, String syncedAt) {
        int idx = -1;
        for (int i = 0; i < history.size(); i++) {
            if (sprintId.equals(history.get(i).path("sprintId").asText(""))) { idx = i; break; }
        }
        ObjectNode prior = idx >= 0 ? history.get(idx) : null;
        ObjectNode merged = prior != null ? prior.deepCopy() : objectMapper.createObjectNode();
        merged.put("sprintId", sprintId);
        merged.put("sprintName", meta != null && !isBlank(meta.name) ? meta.name : (prior != null ? prior.path("sprintName").asText("") : ""));
        merged.put("sprintStart", meta != null && !isBlank(meta.startDate) ? meta.startDate : (prior != null ? prior.path("sprintStart").asText("") : ""));
        merged.put("sprintEnd", meta != null && !isBlank(meta.endDate) ? meta.endDate : (prior != null ? prior.path("sprintEnd").asText("") : ""));
        merged.put("sprintWorkdays", sprintWorkdays);
        merged.put("state", "active");
        merged.put("lastSyncedAt", syncedAt);
        if (idx >= 0) history.set(idx, merged); else history.add(merged);
    }

    private void closePreviousSprintIfRolledOver(List<ObjectNode> history, String previousActiveSprintId, String effectiveSprintId, boolean isFull, String syncedAt) {
        if (!isFull || isBlank(previousActiveSprintId) || previousActiveSprintId.equals(effectiveSprintId) || UNMAPPED_SPRINT_ID.equals(previousActiveSprintId)) {
            return;
        }
        for (int i = 0; i < history.size(); i++) {
            if (previousActiveSprintId.equals(history.get(i).path("sprintId").asText(""))) {
                ObjectNode closed = history.get(i).deepCopy();
                closed.put("state", "closed");
                closed.put("closedAt", syncedAt);
                history.set(i, closed);
                break;
            }
        }
    }

    // ===================== Utilitários =====================

    private static <T> List<List<T>> chunk(List<T> items, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) out.add(items.subList(i, Math.min(items.size(), i + size)));
        return out;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return "";
    }

    private static long firstNonZeroLong(JsonNode fields, String... names) {
        for (String n : names) {
            JsonNode v = fields.get(n);
            if (v != null && !v.isNull()) {
                long val = v.asLong(0);
                if (val != 0) return val;
            }
        }
        return 0L;
    }

    private static String toSafeString(JsonNode val) {
        if (val == null || val.isNull()) return "";
        if (val.isTextual()) return val.asText();
        if (val.isNumber()) return val.asText();
        if (val.isObject()) {
            if (val.has("value") && val.get("value").isTextual()) return val.get("value").asText();
            if (val.has("name") && val.get("name").isTextual()) return val.get("name").asText();
            if (val.has("key") && val.get("key").isTextual()) return val.get("key").asText();
            if (val.has("id")) return val.get("id").asText();
        }
        return "";
    }

    private static String substring10(String s) {
        return (s != null && s.length() >= 10) ? s.substring(0, 10) : "";
    }

    private static long daysSince(String isoDate) {
        Instant then = parseInstantFlexible(isoDate);
        if (then == null) return 0;
        long days = Duration.between(then, Instant.now()).toMillis() / 86_400_000L;
        return Math.max(0, days);
    }

    private static int daysUntil(String isoDate) {
        LocalDate due = parseToLocalDate(isoDate);
        if (due == null) return Integer.MIN_VALUE;
        return (int) java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), due);
    }

    private static String todayStr() {
        return LocalDate.now().toString();
    }

    private static String formatForJql(String iso) {
        Instant instant = parseInstantFlexible(iso);
        if (instant == null) instant = Instant.now();
        ZonedDateTime zdt = instant.atZone(ZoneId.systemDefault());
        return String.format("%04d-%02d-%02d %02d:%02d", zdt.getYear(), zdt.getMonthValue(), zdt.getDayOfMonth(), zdt.getHour(), zdt.getMinute());
    }

    private static String subtractMinutesToIso(String iso, int minutes) {
        Instant instant = parseInstantFlexible(iso);
        if (instant == null) instant = Instant.now();
        return instant.minus(minutes, java.time.temporal.ChronoUnit.MINUTES).toString();
    }

    private static Instant parseInstantFlexible(String iso) {
        if (isBlank(iso)) return null;
        try { return OffsetDateTime.parse(iso, JIRA_DATETIME).toInstant(); } catch (Exception ignored) {}
        try { return OffsetDateTime.parse(iso).toInstant(); } catch (Exception ignored) {}
        try { return Instant.parse(iso); } catch (Exception ignored) {}
        try { return LocalDate.parse(substring10(iso)).atStartOfDay(ZoneOffset.UTC).toInstant(); } catch (Exception ignored) {}
        return null;
    }

    private static LocalDate parseToLocalDate(String iso) {
        if (iso == null || iso.length() < 10) return null;
        try { return LocalDate.parse(iso.substring(0, 10)); } catch (Exception e) { return null; }
    }

    /** Dias úteis (seg-sex) entre duas datas ISO, inclusive. */
    private static int countWorkdays(String startIso, String endIso) {
        LocalDate start = parseToLocalDate(startIso);
        LocalDate end = parseToLocalDate(endIso);
        if (start == null || end == null || end.isBefore(start)) return 0;
        int count = 0;
        LocalDate cur = start;
        while (!cur.isAfter(end)) {
            java.time.DayOfWeek dow = cur.getDayOfWeek();
            if (dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY) count++;
            cur = cur.plusDays(1);
        }
        return count;
    }
}
