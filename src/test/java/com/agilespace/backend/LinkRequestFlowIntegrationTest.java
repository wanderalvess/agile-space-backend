package com.agilespace.backend;

import com.agilespace.backend.domain.ProjectConfig;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.repository.NotificationRepository;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtTokenUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pedido de vínculo ponta a ponta contra um Postgres real: a migration V74 roda do zero com o schema validado pelo
 * Hibernate (a entidade tem que bater com a tabela), o índice único parcial barra o segundo pendente, a fila é só de
 * admin e a aprovação grava o vínculo. Sem Docker o teste é ignorado, como os demais Testcontainers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
public class LinkRequestFlowIntegrationTest {

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
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private NotificationRepository notificationRepository;

    private String token(User u) {
        return "Bearer " + jwtTokenUtil.generateToken(u.getId(), u.getEmail(), u.getName(), u.getRole(), "LRPROJ", "Seg", "Tribo");
    }

    private User newUser(String id, String role) {
        return userRepository.save(User.builder().id(id).email(id + "@totvs.com.br").name("Pessoa " + id).role(role).build());
    }

    private String json(Object... kv) throws Exception {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return mapper.writeValueAsString(m);
    }

    @Test
    public void createApproveRejectFlowAgainstRealPostgres() throws Exception {
        projectConfigRepository.save(ProjectConfig.builder().id("LRPROJ").name("Squad LR").build());
        ProjectMemberRole lead = projectMemberRoleRepository.save(ProjectMemberRole.builder().projectId("LRPROJ")
                .roleName("Agile Master").roleKey("AGILE_MASTER").displayName("Lider Real").email("lider@jira.com")
                .jiraAccountId("acc-lider").isLeadership(true).build());
        ProjectMemberRole dev = projectMemberRoleRepository.save(ProjectMemberRole.builder().projectId("LRPROJ")
                .roleName("Developer").roleKey("DEVELOPER").displayName("Dev Real").email("dev@jira.com").isLeadership(false).build());

        User admin = newUser("lr-admin", "ADMIN");
        User ana = newUser("lr-ana", "MEMBER");
        User bia = newUser("lr-bia", "MEMBER");

        // Usuário comum cria (liderança, com e-mail diferente) -> 201 PENDING
        String created = mockMvc.perform(post("/api/link-requests").header("Authorization", token(ana))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("projectKey", "lrproj", "memberId", lead.getId(), "emailInJira", "lider@jira.com", "message", "Sou o AM")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String requestId = mapper.readTree(created).get("id").asText();

        // Segundo pendente para a mesma squad -> 409
        mockMvc.perform(post("/api/link-requests").header("Authorization", token(ana))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("projectKey", "LRPROJ", "emailInJira", "outro@jira.com")))
                .andExpect(status().isConflict());

        // mine só mostra os dele, com o nome da linha
        mockMvc.perform(get("/api/link-requests/mine").header("Authorization", token(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].memberName").value("Lider Real"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
        mockMvc.perform(get("/api/link-requests/mine").header("Authorization", token(bia)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        // Fila é só de admin
        mockMvc.perform(get("/api/admin/link-requests").header("Authorization", token(ana))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/link-requests/" + requestId + "/approve").header("Authorization", token(ana)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/link-requests").param("status", "PENDING").header("Authorization", token(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + requestId + "')].requester.email").value("lr-ana@totvs.com.br"));

        // Os admins foram avisados do pedido novo
        assertEquals(1, notificationRepository.findAll().stream()
                .filter(n -> "LINK_REQUEST".equals(n.getKind()) && "lr-admin".equals(n.getUserId()) && "/admin?tab=aprovacoes".equals(n.getLink())).count());

        // Aprovar vincula a conta à linha de liderança e herda o accountId
        mockMvc.perform(post("/api/admin/link-requests/" + requestId + "/approve").header("Authorization", token(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value("lr-admin@totvs.com.br"));
        assertEquals("lr-ana", projectMemberRoleRepository.findById(lead.getId()).orElseThrow().getUserId());
        assertEquals("acc-lider", userRepository.findById("lr-ana").orElseThrow().getJiraAccountId());
        assertTrue(auditLogRepository.findAll().stream().anyMatch(a -> "LINK_REQUEST_APPROVED".equals(a.getAction())));
        assertTrue(notificationRepository.findAll().stream().anyMatch(n -> "LINK_REQUEST".equals(n.getKind())
                && "lr-ana".equals(n.getUserId()) && "/painel".equals(n.getLink())));

        // Decidir de novo -> 409
        mockMvc.perform(post("/api/admin/link-requests/" + requestId + "/reject").header("Authorization", token(admin)))
                .andExpect(status().isConflict());

        // Linha já vinculada a outra conta: bia não consegue nem pedir, e se pedir sem linha o admin recebe 409 ao apontar
        mockMvc.perform(post("/api/link-requests").header("Authorization", token(bia))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("projectKey", "LRPROJ", "memberId", lead.getId(), "emailInJira", "lider@jira.com")))
                .andExpect(status().isConflict());
        String biaReq = mapper.readTree(mockMvc.perform(post("/api/link-requests").header("Authorization", token(bia))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("projectKey", "LRPROJ", "emailInJira", "bia@jira.com")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();

        // Sem linha -> 422
        mockMvc.perform(post("/api/admin/link-requests/" + biaReq + "/approve").header("Authorization", token(admin)))
                .andExpect(status().isUnprocessableEntity());
        // Aponta a linha que já é de outra conta -> 409 e o pedido segue pendente
        mockMvc.perform(post("/api/admin/link-requests/" + biaReq + "/approve").header("Authorization", token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json("memberId", lead.getId())))
                .andExpect(status().isConflict());
        // Aponta a linha livre -> aprova
        mockMvc.perform(post("/api/admin/link-requests/" + biaReq + "/approve").header("Authorization", token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json("memberId", dev.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.memberId").value(dev.getId()));
        assertEquals("lr-bia", projectMemberRoleRepository.findById(dev.getId()).orElseThrow().getUserId());

        // Recusar: outro usuário, motivo gravado, nada vinculado
        User caio = newUser("lr-caio", "MEMBER");
        String caioReq = mapper.readTree(mockMvc.perform(post("/api/link-requests").header("Authorization", token(caio))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("projectKey", "LRPROJ", "emailInJira", "caio@jira.com")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(post("/api/admin/link-requests/" + caioReq + "/reject").header("Authorization", token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json("reason", "Não encontramos você no Jira")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decisionNote").value("Não encontramos você no Jira"));
        JsonNode rejected = mapper.readTree(mockMvc.perform(get("/api/link-requests/mine").header("Authorization", token(caio)))
                .andReturn().getResponse().getContentAsString());
        assertEquals("REJECTED", rejected.get(0).get("status").asText());
        assertTrue(auditLogRepository.findAll().stream().anyMatch(a -> "LINK_REQUEST_REJECTED".equals(a.getAction())));

        // Depois de decidido, um novo pedido para a mesma squad é permitido (a unicidade é só entre pendentes)
        mockMvc.perform(post("/api/link-requests").header("Authorization", token(caio))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("projectKey", "LRPROJ", "emailInJira", "caio@jira.com")))
                .andExpect(status().isCreated());

        // Filtro por status
        mockMvc.perform(get("/api/admin/link-requests").param("status", "REJECTED").header("Authorization", token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/admin/link-requests").param("status", "XYZ").header("Authorization", token(admin)))
                .andExpect(status().isBadRequest());
    }
}
