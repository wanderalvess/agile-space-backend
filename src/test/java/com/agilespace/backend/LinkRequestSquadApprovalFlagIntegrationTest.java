package com.agilespace.backend;

import com.agilespace.backend.domain.ProjectConfig;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.NotificationRepository;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtTokenUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contra um Postgres real: (1) com {@code app.link-requests.squad-approval=off} as rotas por squad respondem 404, o AM não
 * é notificado nem aprova, e o ADMIN continua decidindo por /api/admin/link-requests (reversão sem migração); (2) a V76
 * preenche {@code kind} das linhas antigas e é idempotente (rodar o script de novo não muda nem quebra nada).
 */
@SpringBootTest(properties = "app.link-requests.squad-approval=off")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
public class LinkRequestSquadApprovalFlagIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenUtil jwtTokenUtil;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectConfigRepository projectConfigRepository;
    @Autowired private ProjectMemberRoleRepository projectMemberRoleRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    private String token(User u) {
        return "Bearer " + jwtTokenUtil.generateToken(u.getId(), u.getEmail(), u.getName(), u.getRole(), "OFFSQ", "Seg", "Tribo");
    }

    private User newUser(String id, String role) {
        return userRepository.save(User.builder().id(id).email(id + "@totvs.com.br").name("Pessoa " + id).role(role).build());
    }

    private String json(Object... kv) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return mapper.writeValueAsString(m);
    }

    @Test
    public void flagOffKeepsOnlyAdminApproval() throws Exception {
        projectConfigRepository.save(ProjectConfig.builder().id("OFFSQ").name("Squad Off").tribeName("TriboOff").build());
        User admin = newUser("off-admin", "ADMIN");
        User am = newUser("off-am", "MEMBER");
        User dev = newUser("off-dev", "MEMBER");
        projectMemberRoleRepository.save(ProjectMemberRole.builder().projectId("OFFSQ").roleName("Agile Master").roleKey("AGILE_MASTER")
                .displayName("AM").isLeadership(true).userId(am.getId()).source("MANUAL").build());

        String reqId = mapper.readTree(mockMvc.perform(post("/api/link-requests").header("Authorization", token(dev))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("projectKey", "OFFSQ", "emailInJira", "dev@jira.com", "requestedRoleName", "Developer")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();

        // Notificação: só ADMIN, com o link antigo da fila de admin
        assertTrue(notificationRepository.findAll().stream().anyMatch(n -> "off-admin".equals(n.getUserId())
                && ("LINK_REQUEST_CREATED:" + reqId).equals(n.getDedupeKey()) && "/admin?tab=aprovacoes".equals(n.getLink())));
        assertFalse(notificationRepository.findAll().stream().anyMatch(n -> "off-am".equals(n.getUserId())
                && ("LINK_REQUEST_CREATED:" + reqId).equals(n.getDedupeKey())));

        // Rotas por squad "não existem"
        mockMvc.perform(get("/api/squads/OFFSQ/link-requests/can-approve").header("Authorization", token(am))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/squads/OFFSQ/link-requests").header("Authorization", token(am))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/squads/OFFSQ/link-requests").header("Authorization", token(admin))).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/squads/OFFSQ/link-requests/" + reqId + "/approve").header("Authorization", token(am))).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/squads/OFFSQ/link-requests/" + reqId + "/reject").header("Authorization", token(am))).andExpect(status().isNotFound());

        // ADMIN decide pela rota de admin, como antes (e a pmr nasce para o contribuidor sem linha)
        mockMvc.perform(post("/api/admin/link-requests/" + reqId + "/approve").header("Authorization", token(am))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/link-requests/" + reqId + "/approve").header("Authorization", token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertTrue(projectMemberRoleRepository.findByUserId("off-dev").stream()
                .anyMatch(r -> "OFFSQ".equals(r.getProjectId()) && "APPROVED".equals(r.getSource()) && !r.isLeadership()));
    }

    @Test
    public void v76FillsKindOfOldRowsAndIsIdempotent() throws Exception {
        newUser("v76-user", "MEMBER");
        projectConfigRepository.save(ProjectConfig.builder().id("V76SQ").name("Squad V76").build());
        ProjectMemberRole lead = projectMemberRoleRepository.save(ProjectMemberRole.builder().projectId("V76SQ").roleName("Product Owner")
                .roleKey("PRODUCT_OWNER").displayName("PO").isLeadership(true).build());
        ProjectMemberRole dev = projectMemberRoleRepository.save(ProjectMemberRole.builder().projectId("V76SQ").roleName("Developer")
                .roleKey("DEVELOPER").displayName("Dev").isLeadership(false).build());

        insertOld("v76-lead", lead.getId());
        insertOld("v76-dev", dev.getId());
        insertOld("v76-none", null);
        insertOld("v76-gone", "linha-que-nao-existe-mais");
        insertOld("v76-keep", lead.getId());
        jdbc.update("UPDATE link_requests SET kind = 'CONTRIBUTOR' WHERE id = 'v76-keep'");     // já preenchido: o script não toca

        runMigrationScript();
        runMigrationScript(); // idempotente

        assertEquals("LEADERSHIP", kindOf("v76-lead"));
        assertEquals("CONTRIBUTOR", kindOf("v76-dev"));
        assertEquals("CONTRIBUTOR", kindOf("v76-none"));
        assertEquals("CONTRIBUTOR", kindOf("v76-gone"));
        assertEquals("CONTRIBUTOR", kindOf("v76-keep"));
        assertEquals(Integer.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'idx_link_requests_project_status_created'", Integer.class));
    }

    private void insertOld(String id, String memberId) {
        jdbc.update("INSERT INTO link_requests (id, requester_user_id, project_key, member_id, email_in_jira, status, created_at, kind) "
                        + "VALUES (?, 'v76-user', 'V76SQ', ?, 'x@jira.com', 'APPROVED', ?, NULL)",
                id, memberId, Timestamp.valueOf(LocalDateTime.now()));
    }

    private String kindOf(String id) {
        return jdbc.queryForObject("SELECT kind FROM link_requests WHERE id = ?", String.class, id);
    }

    private void runMigrationScript() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/V76__link_requests_kind_and_role.sql"));
        }
    }
}
