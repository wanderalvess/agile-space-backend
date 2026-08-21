package com.agilespace.backend;

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

    @Test
    public void testAgileCycle() throws Exception {
        // 1. Sync Jira via JiraAdminController (Triggers AuditLog via interceptor)
        mockMvc.perform(post("/api/admin/jira/sync-project")
                .param("projectKey", "TESTPROJ"))
                .andExpect(status().is4xxClientError());

        // 2. Scrum Poker: Estimar item inexistente (Testa Upsert automático no Postgres)
        mockMvc.perform(put("/api/work-items/TESTPROJ/TEST-101/estimate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"points_estimated\": 5.0}"))
                .andExpect(status().isOk());

        // 3. Sprint Planner: Consultar backlog estimado
        mockMvc.perform(get("/api/work-items/TESTPROJ/backlog-estimated"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jiraKey").value("TEST-101"))
                .andExpect(jsonPath("$[0].pointsEstimated").value(5.0));

        // 4. Sprint Planner: Commitar na Sprint
        mockMvc.perform(put("/api/work-items/TESTPROJ/TEST-101/commit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sprint_id\": \"SPRINT-1\"}"))
                .andExpect(status().isOk());

        // 5. Showcase: Registrar decisão de entrega (Delivered)
        mockMvc.perform(put("/api/work-items/TESTPROJ/TEST-101/showcase-decision")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"delivered\", \"feedback\": \"Critérios validados com sucesso\"}"))
                .andExpect(status().isOk());

        // 6. Retro: Consultar estatísticas da Sprint
        mockMvc.perform(get("/api/work-items/TESTPROJ/sprint/SPRINT-1/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.velocityReal").value(5.0))
                .andExpect(jsonPath("$.previsto").value(5.0));
    }
}
