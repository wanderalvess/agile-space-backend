package com.agilespace.backend.service;

import com.agilespace.backend.domain.JiraIssueTypeCache;
import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.domain.SquadIssueSnapshot;
import com.agilespace.backend.domain.UserJiraConfig;
import com.agilespace.backend.dto.JiraSearchRequest;
import com.agilespace.backend.repository.JiraIssueTypeCacheRepository;
import com.agilespace.backend.repository.SquadIssueSnapshotRepository;
import com.agilespace.backend.repository.UserJiraConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlannerBacklogSyncService - JQL, paginação, 429, lotes de parent, gravação e limite de 5 min")
class PlannerBacklogSyncServiceTest {

    @Mock private SquadService squadService;
    @Mock private JiraService jiraService;
    @Mock private UserJiraConfigRepository userJiraConfigRepository;
    @Mock private SquadIssueSnapshotRepository snapshotRepository;
    @Mock private JiraIssueTypeCacheRepository typeCacheRepository;

    private final ObjectMapper om = new ObjectMapper();
    private PlannerBacklogSyncService service;
    private final List<Long> sleeps = new ArrayList<>();
    private Instant now = Instant.parse("2026-10-20T12:00:00Z");

    @BeforeEach
    void setUp() {
        service = new PlannerBacklogSyncService(squadService, jiraService, userJiraConfigRepository, snapshotRepository,
                typeCacheRepository, om, "Refinamento Concluído", "", 300);
        service.setSleeper(sleeps::add);
        service.setClock(new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now; }
        });
        lenient().when(squadService.getSquad("SQ")).thenReturn(Optional.of(
                Squad.builder().id("SQ").jiraProjectKey("DDWX").activeSprintId("777").build()));
        UserJiraConfig cfg = new UserJiraConfig();
        cfg.setDomain("jira.empresa.com.br");
        cfg.setToken("tok");
        lenient().when(userJiraConfigRepository.findById("u1")).thenReturn(Optional.of(cfg));
        lenient().when(snapshotRepository.findBySquadIdAndJiraKeyIn(anyString(), any())).thenReturn(List.of());
        lenient().when(snapshotRepository.findBySquadIdAndInPlannerBacklogTrue("SQ")).thenReturn(List.of());
        lenient().when(typeCacheRepository.findBySquadIdOrderByNameAsc("SQ")).thenReturn(List.of());
        lenient().when(jiraService.exchangeGet(any(), any())).thenReturn(ResponseEntity.ok("[]"));
    }

    private ObjectNode issue(String key, String parentKey, String typeId, String status, Long est, Long rem) {
        ObjectNode n = om.createObjectNode();
        n.put("key", key);
        ObjectNode f = n.putObject("fields");
        f.put("summary", "Título " + key);
        f.putObject("issuetype").put("id", typeId).put("name", "Tipo " + typeId).put("subtask", parentKey != null);
        ObjectNode st = f.putObject("status").put("id", "1").put("name", status);
        st.putObject("statusCategory").put("key", "new");
        if (parentKey != null) f.putObject("parent").put("key", parentKey);
        if (est != null) f.put("timeoriginalestimate", est);
        if (rem != null) f.put("timeestimate", rem);
        f.put("aggregatetimeoriginalestimate", 999999); // nunca deve ser usado
        return n;
    }

    private ResponseEntity<String> page(int total, ObjectNode... issues) {
        ObjectNode root = om.createObjectNode();
        root.put("total", total);
        ArrayNode arr = root.putArray("issues");
        for (ObjectNode i : issues) arr.add(i);
        return ResponseEntity.ok(root.toString());
    }

    @Test
    @DisplayName("JQL do refinamento (nome configurável) e da sobra (sprint ativa, não concluídas)")
    void jqls() {
        assertEquals("project = \"DDWX\" AND status = \"Refinamento Concluído\" AND issuetype not in subTaskIssueTypes() ORDER BY Rank ASC",
                service.refinementJql("DDWX"));
        PlannerBacklogSyncService byId = new PlannerBacklogSyncService(null, null, null, null, null, om, "X", "10500", 300);
        assertTrue(byId.refinementJql("DDWX").contains("status = 10500"));
        assertTrue(PlannerBacklogSyncService.carryOverJql("DDWX", "777").contains("sprint = 777 AND statusCategory != Done"));
        assertTrue(PlannerBacklogSyncService.carryOverJql("DDWX", "UNMAPPED").contains("sprint in openSprints()"));
    }

    @Test
    @DisplayName("pagina por startAt, junta refinadas + sobra, busca subtarefas por parent e grava com estimativa própria")
    @SuppressWarnings("unchecked")
    void paginatesAndPersists() {
        when(jiraService.searchIssues(any())).thenAnswer(inv -> {
            JiraSearchRequest r = inv.getArgument(0);
            assertFalse(r.getFields().stream().anyMatch(f -> f.startsWith("aggregate")), "sem aggregate*");
            if (r.getJql().contains("Refinamento")) {
                return r.getStartAt() == 0 ? page(3, issue("DDWX-1", null, "1", "Refinamento Concluído", 0L, 0L),
                        issue("DDWX-2", null, "1", "Refinamento Concluído", null, null))
                        : page(3, issue("DDWX-3", null, "1", "Refinamento Concluído", null, null));
            }
            if (r.getJql().contains("sprint = 777")) {
                return page(2, issue("DDWX-3", null, "1", "Em andamento", null, null), issue("DDWX-9", null, "1", "Em andamento", null, null));
            }
            return page(1, issue("DDWX-4", "DDWX-1", "10036", "A fazer", 28800L, 7200L));
        });

        PlannerBacklogSyncService.SyncResult result = service.sync("SQ", "u1");
        assertEquals(4, result.parents());
        assertEquals(1, result.carryOver());
        assertEquals(1, result.subtasks());

        ArgumentCaptor<List<SquadIssueSnapshot>> cap = ArgumentCaptor.forClass(List.class);
        verify(snapshotRepository).saveAll(cap.capture());
        Map<String, SquadIssueSnapshot> saved = new HashMap<>();
        cap.getValue().forEach(s -> saved.put(s.getJiraKey(), s));
        SquadIssueSnapshot sub = saved.get("DDWX-4");
        assertTrue(sub.isInPlannerBacklog());
        assertEquals(SquadIssueSnapshot.PLANNER_BACKLOG_SPRINT_ID, sub.getSprintId());
        assertEquals("SQ_DDWX-4", sub.getDbId());
        assertEquals(28800L, sub.getOwnOriginalEstimateSec());
        assertEquals(7200L, sub.getOwnRemainingSec());
        assertEquals("10036", sub.getIssueTypeId());
        assertEquals("DDWX-1", sub.getParentKey());
        assertEquals("Título DDWX-1", sub.getParentTitle());
        assertEquals(now.toString(), sub.getPlannerSyncedAt());
    }

    @Test
    @DisplayName("subtarefas em lotes de no máximo 50 pais por JQL")
    void parentBatchesOf50() {
        List<String> parentJqls = new ArrayList<>();
        when(jiraService.searchIssues(any())).thenAnswer(inv -> {
            JiraSearchRequest r = inv.getArgument(0);
            if (r.getJql().contains("Refinamento")) {
                int start = r.getStartAt();
                List<ObjectNode> list = new ArrayList<>();
                for (int i = start; i < Math.min(120, start + 100); i++) list.add(issue("DDWX-" + (i + 1), null, "1", "Refinamento Concluído", null, null));
                return page(120, list.toArray(new ObjectNode[0]));
            }
            if (r.getJql().startsWith("parent in")) parentJqls.add(r.getJql());
            return page(0);
        });
        service.sync("SQ", "u1");
        assertEquals(3, parentJqls.size());
        for (String jql : parentJqls) {
            int count = jql.substring(jql.indexOf('(') + 1, jql.indexOf(')')).split(",").length;
            assertTrue(count <= 50, "lote com " + count);
        }
    }

    @Test
    @DisplayName("429 do Jira: espera o Retry-After e tenta de novo; esgotado vira 502 com mensagem clara")
    void retriesOn429() {
        ResponseEntity<String> tooMany = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "2").body("");
        when(jiraService.searchIssues(any())).thenReturn(tooMany).thenReturn(page(0));
        service.sync("SQ", "u1");
        assertEquals(List.of(2000L), sleeps);

        now = now.plusSeconds(301);
        when(jiraService.searchIssues(any())).thenReturn(tooMany);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.sync("SQ", "u1"));
        assertEquals(502, ex.getStatusCode().value());
        assertTrue(ex.getReason().contains("429"));
    }

    @Test
    @DisplayName("limite de 1 sync por squad a cada 5 min -> 429 com o tempo que falta; outra squad não é afetada")
    void rateLimitPerSquad() {
        when(jiraService.searchIssues(any())).thenReturn(page(0));
        service.sync("SQ", "u1");
        now = now.plusSeconds(60);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.sync("SQ", "u1"));
        assertEquals(429, ex.getStatusCode().value());
        assertTrue(ex.getReason().contains("240 s"), ex.getReason());
        assertDoesNotThrow(() -> service.acquireRateLimit("OUTRA"));
        now = now.plusSeconds(241);
        assertDoesNotThrow(() -> service.sync("SQ", "u1"));
    }

    @Test
    @DisplayName("sem token do Jira -> 400 e a janela de 5 min não é consumida")
    void missingTokenDoesNotBurnWindow() {
        when(userJiraConfigRepository.findById("u2")).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> service.sync("SQ", "u2"));
        when(jiraService.searchIssues(any())).thenReturn(page(0));
        assertDoesNotThrow(() -> service.sync("SQ", "u1"));
    }

    @Test
    @DisplayName("linha da sprint já sincronizada só ganha as colunas do planner; saiu do backlog: da sprint perde a marca, só-backlog é apagada")
    @SuppressWarnings("unchecked")
    void persistKeepsSprintRowsAndCleansBacklogOnly() {
        SquadIssueSnapshot sprintRow = SquadIssueSnapshot.builder().dbId("SQ_DDWX-1").squadId("SQ").jiraKey("DDWX-1")
                .sprintId("777").title("Título da sprint").estimateSec(50000L).build();
        SquadIssueSnapshot oldSprintFlag = SquadIssueSnapshot.builder().jiraKey("DDWX-50").sprintId("777").inPlannerBacklog(true).build();
        SquadIssueSnapshot oldBacklogOnly = SquadIssueSnapshot.builder().jiraKey("DDWX-60")
                .sprintId(SquadIssueSnapshot.PLANNER_BACKLOG_SPRINT_ID).inPlannerBacklog(true).build();
        when(snapshotRepository.findBySquadIdAndJiraKeyIn(eq("SQ"), any())).thenReturn(List.of(sprintRow));
        when(snapshotRepository.findBySquadIdAndInPlannerBacklogTrue("SQ")).thenReturn(List.of(oldSprintFlag, oldBacklogOnly));

        service.persist("SQ", List.of(PlannerBacklogSyncService.parse(issue("DDWX-1", null, "1", "Refinamento Concluído", 3600L, 1800L))), "T");

        assertEquals("777", sprintRow.getSprintId());
        assertEquals("Título da sprint", sprintRow.getTitle(), "campos do sync da sprint ficam");
        assertEquals(50000L, sprintRow.getEstimateSec(), "estimate_sec agregado não muda");
        assertEquals(3600L, sprintRow.getOwnOriginalEstimateSec());
        assertTrue(sprintRow.isInPlannerBacklog());
        assertFalse(oldSprintFlag.isInPlannerBacklog());
        verify(snapshotRepository).deleteBySquadIdAndJiraKeyIn("SQ", List.of("DDWX-60"));
    }

    @Test
    @DisplayName("atualiza o cache de tipos de issue quando vazio")
    @SuppressWarnings("unchecked")
    void refreshesTypeCache() {
        when(jiraService.searchIssues(any())).thenReturn(page(0));
        when(jiraService.exchangeGet(any(), any())).thenReturn(ResponseEntity.ok(
                "[{\"id\":\"10036\",\"name\":\"Codificação\",\"subtask\":true},{\"id\":\"1\",\"name\":\"História\",\"subtask\":false}]"));
        service.sync("SQ", "u1");
        ArgumentCaptor<Collection<JiraIssueTypeCache>> cap = ArgumentCaptor.forClass(Collection.class);
        verify(typeCacheRepository).saveAll(cap.capture());
        assertEquals(2, cap.getValue().size());
        verify(typeCacheRepository).deleteBySquadId("SQ");
    }
}
