package com.agilespace.backend;

import com.agilespace.backend.security.JwtTokenUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@AutoConfigureMockMvc
public class E2EAgileCycleTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenUtil jwtTokenUtil;

    @Autowired
    private com.agilespace.backend.repository.WorkItemRepository workItemRepository;

    private String token;

    @BeforeEach
    public void setup() {
        workItemRepository.deleteAll();
        token = jwtTokenUtil.generateToken("test-user-id", "admin@agilespace.com", "Admin User", "ADMIN", "TESTPROJ", "Segment", "Tribe");
    }

    @Test
    public void testAgileCycle() throws Exception {
        // 1. Sync Jira via JiraAdminController (Triggers AuditLog via interceptor)
        mockMvc.perform(post("/api/admin/jira/sync-project")
                .header("Authorization", "Bearer " + token)
                .param("projectKey", "TESTPROJ"))
                .andExpect(status().is4xxClientError());

        // 2. Scrum Poker: Estimar item inexistente (Testa Upsert automático no Postgres)
        mockMvc.perform(put("/api/work-items/TESTPROJ/TEST-101/estimate")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"points_estimated\": 5.0}"))
                .andExpect(status().isOk());

        // 3. Sprint Planner: Consultar backlog estimado
        mockMvc.perform(get("/api/work-items/TESTPROJ/backlog-estimated")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jiraKey").value("TEST-101"))
                .andExpect(jsonPath("$[0].pointsEstimated").value(5.0));

        // 4. Sprint Planner: Commitar na Sprint
        mockMvc.perform(put("/api/work-items/TESTPROJ/TEST-101/commit")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sprint_id\": \"SPRINT-1\"}"))
                .andExpect(status().isOk());

        // 5. Showcase: Registrar decisão de entrega (Delivered)
        mockMvc.perform(put("/api/work-items/TESTPROJ/TEST-101/showcase-decision")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"delivered\", \"feedback\": \"Critérios validados com sucesso\"}"))
                .andExpect(status().isOk());

        // 6. Retro: Consultar estatísticas da Sprint
        mockMvc.perform(get("/api/work-items/TESTPROJ/sprint/SPRINT-1/stats")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.velocityReal").value(5.0))
                .andExpect(jsonPath("$.previsto").value(5.0));

        // 7. Retro: Criar board ligado à sprint e buscar por sprintId
        mockMvc.perform(post("/api/retros")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\": \"retro-e2e-1\", \"creatorId\": \"test-user-id\", \"title\": \"Retro Sprint 1\", \"sprintId\": \"SPRINT-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sprintId").value("SPRINT-1"));

        mockMvc.perform(get("/api/retros")
                .header("Authorization", "Bearer " + token)
                .param("sprintId", "SPRINT-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("retro-e2e-1"));

        // 8. Action Plan: Criar board ligado à sprint e buscar por sprintId
        mockMvc.perform(post("/api/action-plans")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"creatorId\": \"test-user-id\", \"title\": \"Ação Sprint 1\", \"sprintId\": \"SPRINT-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sprintId").value("SPRINT-1"));

        mockMvc.perform(get("/api/action-plans")
                .header("Authorization", "Bearer " + token)
                .param("sprintId", "SPRINT-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sprintId").value("SPRINT-1"));
    }
}

