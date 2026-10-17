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
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
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
        // (o banco é compartilhado com os outros testes da classe: filtra pela squad deste teste)
        mockMvc.perform(get("/api/admin/link-requests").param("status", "REJECTED").header("Authorization", token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.projectKey=='LRPROJ')].id").value(caioReq));
        mockMvc.perform(get("/api/admin/link-requests").param("status", "XYZ").header("Authorization", token(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void cancelOwnPendingRequestAgainstRealPostgres() throws Exception {
        projectConfigRepository.save(ProjectConfig.builder().id("LRCANC").name("Squad Cancelar").build());
        User admin = newUser("lc-admin", "ADMIN");
        User dona = newUser("lc-dona", "MEMBER");
        User outra = newUser("lc-outra", "MEMBER");

        String requestId = createRequest(dona, "projectKey", "LRCANC", "emailInJira", "dona@jira.com");
        String dedupe = "LINK_REQUEST_CREATED:" + requestId;
        assertTrue(notificationRepository.findAll().stream()
                .anyMatch(n -> dedupe.equals(n.getDedupeKey()) && "lc-admin".equals(n.getUserId()) && n.getReadAt() == null));

        // Outra conta: 403, e o pedido segue pendente. Inexistente: 404. Sem token: 401.
        mockMvc.perform(post("/api/link-requests/" + requestId + "/cancel").header("Authorization", token(outra)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/link-requests/nao-existe/cancel").header("Authorization", token(dona)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/link-requests/" + requestId + "/cancel")).andExpect(status().isUnauthorized());
        assertEquals("PENDING", jdbc.queryForObject("select status from link_requests where id = ?", String.class, requestId));

        // A dona cancela: 200 CANCELLED (a coluna VARCHAR aceita o valor, sem migração)
        mockMvc.perform(post("/api/link-requests/" + requestId + "/cancel").header("Authorization", token(dona)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(requestId))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.decidedAt").isNotEmpty());
        assertEquals("CANCELLED", jdbc.queryForObject("select status from link_requests where id = ?", String.class, requestId));

        // Idempotente: repetir devolve 200 e não duplica a auditoria
        mockMvc.perform(post("/api/link-requests/" + requestId + "/cancel").header("Authorization", token(dona)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertEquals(1, auditLogRepository.findAll().stream()
                .filter(a -> "LINK_REQUEST_CANCELLED".equals(a.getAction()) && a.getDetails().startsWith("pedido=" + requestId)).count());

        // O aviso dos aprovadores foi retirado (lido), nenhum aviso de "decidido" foi para a dona
        assertTrue(notificationRepository.findAll().stream()
                .filter(n -> dedupe.equals(n.getDedupeKey())).allMatch(n -> n.getReadAt() != null));
        assertFalse(notificationRepository.findAll().stream().anyMatch(n -> ("LINK_REQUEST_DECIDED:" + requestId).equals(n.getDedupeKey())));

        // Fila de admin: não está mais PENDING, e o admin não consegue decidir um cancelado (409)
        mockMvc.perform(get("/api/admin/link-requests").param("status", "PENDING").header("Authorization", token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id=='" + requestId + "')]").isEmpty());
        mockMvc.perform(get("/api/admin/link-requests").param("status", "CANCELLED").header("Authorization", token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id=='" + requestId + "')].status").value("CANCELLED"));
        mockMvc.perform(post("/api/admin/link-requests/" + requestId + "/reject").header("Authorization", token(admin)))
                .andExpect(status().isConflict());

        // Cancelar liberou o pedido único pendente da squad: a dona pode pedir de novo, e um aprovado não se cancela
        String second = createRequest(dona, "projectKey", "LRCANC", "emailInJira", "dona@jira.com");
        ProjectMemberRole livre = row("LRCANC", "Developer", "DEVELOPER", false, null);
        mockMvc.perform(post("/api/admin/link-requests/" + second + "/approve").header("Authorization", token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json("memberId", livre.getId())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/link-requests/" + second + "/cancel").header("Authorization", token(dona)))
                .andExpect(status().isConflict());
    }

    private ProjectMemberRole row(String project, String roleName, String roleKey, boolean leadership, String userId) {
        return projectMemberRoleRepository.save(ProjectMemberRole.builder().projectId(project).roleName(roleName).roleKey(roleKey)
                .displayName("Linha " + roleKey + " " + userId).isLeadership(leadership).userId(userId)
                .source(userId == null ? "JIRA" : "MANUAL").build());
    }

    private String createRequest(User who, Object... kv) throws Exception {
        return mapper.readTree(mockMvc.perform(post("/api/link-requests").header("Authorization", token(who))
                        .contentType(MediaType.APPLICATION_JSON).content(json(kv)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private boolean notified(String userId, String requestId, String link) {
        return notificationRepository.findAll().stream().anyMatch(n -> "LINK_REQUEST".equals(n.getKind())
                && userId.equals(n.getUserId()) && ("LINK_REQUEST_CREATED:" + requestId).equals(n.getDedupeKey())
                && link.equals(n.getLink()));
    }

    private static String byId(String id) {
        return "$[?(@.id==\"" + id + "\")]";
    }

    /**
     * Etapa 3 ponta a ponta contra o Postgres: contribuidor fora do roster pede papel; o Agile Master da squad (e não o
     * admin) aprova pela rota por squad; a pmr nasce (APPROVED, não-liderança) e /api/me/memberships do solicitante passa
     * a trazer a squad. Cobre também liderança, autoaprovação, rota de outra squad, expansão de tribo e fallback em ADMIN.
     */
    @Test
    public void squadLeadershipApprovesContributorEndToEnd() throws Exception {
        projectConfigRepository.save(ProjectConfig.builder().id("LRSQ").name("Squad A").tribeName("TriboLR").build());
        projectConfigRepository.save(ProjectConfig.builder().id("LRSQ2").name("Squad B").tribeName("TriboLR").build());
        projectConfigRepository.save(ProjectConfig.builder().id("LRSQ4").name("Squad sem lideranca").tribeName("TriboZ").build());

        User admin = newUser("lr2-admin", "ADMIN");
        User am = newUser("lr2-am", "MEMBER");
        User pl = newUser("lr2-pl", "MEMBER");          // People Lead da squad B, mesma tribo
        User dev = newUser("lr2-dev", "MEMBER");
        User ana = newUser("lr2-ana", "MEMBER");
        row("LRSQ", "Agile Master", "AGILE_MASTER", true, am.getId());
        row("LRSQ2", "People Lead", "PEOPLE_LEAD", true, pl.getId());
        row("LRSQ", "Developer", "DEVELOPER", false, null);
        ProjectMemberRole poRow = row("LRSQ", "Product Owner", "PRODUCT_OWNER", true, null);

        // O solicitante ainda não tem squad: /api/me/memberships vazio (e o cache guarda o vazio)
        mockMvc.perform(get("/api/me/memberships").header("Authorization", token(dev)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.memberships.length()").value(0));

        // Papel de liderança como contribuidor, ou papel + liderança: recusado (422); tipo desconhecido: 400
        mockMvc.perform(post("/api/link-requests").header("Authorization", token(dev)).contentType(MediaType.APPLICATION_JSON)
                        .content(json("projectKey", "LRSQ", "emailInJira", "dev@jira.com", "requestedRoleName", "Product Owner")))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/link-requests").header("Authorization", token(dev)).contentType(MediaType.APPLICATION_JSON)
                        .content(json("projectKey", "LRSQ", "emailInJira", "dev@jira.com", "kind", "LEADERSHIP", "requestedRoleName", "Developer")))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/link-requests").header("Authorization", token(dev)).contentType(MediaType.APPLICATION_JSON)
                        .content(json("projectKey", "LRSQ", "emailInJira", "dev@jira.com", "kind", "ROOT")))
                .andExpect(status().isBadRequest());

        // Contribuidor que não aparece no roster pede o papel Developer
        String devReq = createRequest(dev, "projectKey", "lrsq", "emailInJira", "dev@jira.com", "requestedRoleName", "Developer",
                "message", "Entrei no time agora");
        mockMvc.perform(get("/api/link-requests/mine").header("Authorization", token(dev)))
                .andExpect(jsonPath("$[0].kind").value("CONTRIBUTOR"))
                .andExpect(jsonPath("$[0].requestedRoleName").value("Developer"));

        // Notificação: AM da squad e PL da tribo recebem com o link da squad; ADMIN não (há aprovador)
        String squadLink = "/squad/roster?tab=pedidos&squadId=LRSQ";
        assertTrue(notified("lr2-am", devReq, squadLink));
        assertTrue(notified("lr2-pl", devReq, squadLink));
        assertFalse(notified("lr2-admin", devReq, "/admin?tab=aprovacoes"));
        assertFalse(notified("lr2-dev", devReq, squadLink));

        // can-approve: só do próprio chamador
        mockMvc.perform(get("/api/squads/lrsq/link-requests/can-approve").header("Authorization", token(am)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canApprove").value(true)).andExpect(jsonPath("$.canApproveLeadership").value(false));
        mockMvc.perform(get("/api/squads/LRSQ/link-requests/can-approve").header("Authorization", token(pl)))
                .andExpect(jsonPath("$.canApprove").value(true)).andExpect(jsonPath("$.canApproveLeadership").value(true));
        mockMvc.perform(get("/api/squads/LRSQ/link-requests/can-approve").header("Authorization", token(dev)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canApprove").value(false)).andExpect(jsonPath("$.canApproveLeadership").value(false));
        mockMvc.perform(get("/api/squads/LRSQ/link-requests/can-approve").header("Authorization", token(admin)))
                .andExpect(jsonPath("$.canApprove").value(true)).andExpect(jsonPath("$.canApproveLeadership").value(true));
        mockMvc.perform(get("/api/squads/NAOEXISTE/link-requests/can-approve").header("Authorization", token(am)))
                .andExpect(status().isNotFound());

        // Lista: AM vê, dev não (403)
        mockMvc.perform(get("/api/squads/LRSQ/link-requests").header("Authorization", token(am)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(devReq) + ".kind").value("CONTRIBUTOR"))
                .andExpect(jsonPath(byId(devReq) + ".requestedRoleName").value("Developer"))
                .andExpect(jsonPath(byId(devReq) + ".canDecide").value(true))
                .andExpect(jsonPath(byId(devReq) + ".requester.email").value("lr2-dev@totvs.com.br"));
        mockMvc.perform(get("/api/squads/LRSQ/link-requests").header("Authorization", token(dev))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/squads/LRSQ/link-requests").param("status", "XYZ").header("Authorization", token(am)))
                .andExpect(status().isBadRequest());

        // Pedido da OUTRA squad pela rota errada: 404 (e na rota certa o AM de LRSQ não tem poder em LRSQ2)
        String otherReq = createRequest(ana, "projectKey", "LRSQ2", "emailInJira", "ana@jira.com", "requestedRoleName", "QA");
        mockMvc.perform(post("/api/squads/LRSQ/link-requests/" + otherReq + "/approve").header("Authorization", token(am)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/squads/LRSQ/link-requests/" + otherReq + "/approve").header("Authorization", token(pl)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/squads/LRSQ2/link-requests/" + otherReq + "/approve").header("Authorization", token(am)))
                .andExpect(status().isForbidden());

        // Autoaprovação: o AM pede um segundo papel na própria squad e não consegue aprovar; outro aprovador (ADMIN) decide
        String selfReq = createRequest(am, "projectKey", "LRSQ", "emailInJira", "am@jira.com", "requestedRoleName", "QA");
        mockMvc.perform(post("/api/squads/LRSQ/link-requests/" + selfReq + "/approve").header("Authorization", token(am)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/squads/LRSQ/link-requests/" + selfReq + "/approve").header("Authorization", token(admin)))
                .andExpect(status().isOk());

        // Developer comum não aprova
        mockMvc.perform(post("/api/squads/LRSQ/link-requests/" + devReq + "/approve").header("Authorization", token(dev)))
                .andExpect(status().isForbidden());

        // Liderança: ana pede a linha de PO; o AM não aprova nem recusa; o PL (da tribo) aprova
        String leadReq = createRequest(ana, "projectKey", "LRSQ", "memberId", poRow.getId(), "emailInJira", "po@jira.com");
        mockMvc.perform(get("/api/squads/LRSQ/link-requests").header("Authorization", token(am)))
                .andExpect(jsonPath(byId(leadReq) + ".kind").value("LEADERSHIP"))
                .andExpect(jsonPath(byId(leadReq) + ".canDecide").value(false));
        assertTrue(notified("lr2-pl", leadReq, squadLink));
        assertFalse(notified("lr2-am", leadReq, squadLink));      // AM não é aprovador de liderança
        mockMvc.perform(post("/api/squads/LRSQ/link-requests/" + leadReq + "/approve").header("Authorization", token(am)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/squads/LRSQ/link-requests/" + leadReq + "/reject").header("Authorization", token(am)))
                .andExpect(status().isForbidden());
        assertNull(projectMemberRoleRepository.findById(poRow.getId()).orElseThrow().getUserId());
        mockMvc.perform(post("/api/squads/LRSQ/link-requests/" + leadReq + "/approve").header("Authorization", token(pl)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.kind").value("LEADERSHIP"));
        assertEquals("lr2-ana", projectMemberRoleRepository.findById(poRow.getId()).orElseThrow().getUserId());

        // Escalada por memberId no approve: o AM não liga um contribuidor a uma linha de liderança
        User bia = newUser("lr2-bia", "MEMBER");
        ProjectMemberRole plRow = row("LRSQ", "People Lead", "PEOPLE_LEAD", true, null);
        String biaReq = createRequest(bia, "projectKey", "LRSQ", "emailInJira", "bia@jira.com", "requestedRoleName", "UX");
        mockMvc.perform(post("/api/squads/LRSQ/link-requests/" + biaReq + "/approve").header("Authorization", token(am))
                        .contentType(MediaType.APPLICATION_JSON).content(json("memberId", plRow.getId())))
                .andExpect(status().isForbidden());
        assertNull(projectMemberRoleRepository.findById(plRow.getId()).orElseThrow().getUserId());

        // O Agile Master aprova o contribuidor SEM linha: nasce a pmr e o solicitante passa a ver a squad
        mockMvc.perform(post("/api/squads/LRSQ/link-requests/" + devReq + "/approve").header("Authorization", token(am)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value("lr2-am@totvs.com.br"))
                .andExpect(jsonPath("$.requestedRoleName").value("Developer"));
        ProjectMemberRole created = projectMemberRoleRepository.findByUserId("lr2-dev").stream()
                .filter(r -> "LRSQ".equals(r.getProjectId())).findFirst().orElseThrow();
        assertEquals("APPROVED", created.getSource());
        assertFalse(created.isLeadership());
        assertEquals("Developer", created.getRoleName());
        assertEquals("DEVELOPER", created.getRoleKey());
        User devAfter = userRepository.findById("lr2-dev").orElseThrow();
        assertNull(jdbc.queryForObject("SELECT squad_id FROM users WHERE id = 'lr2-dev'", String.class), "users.squad_id aposentada: a aprovação não grava");
        assertEquals("LRSQ", devAfter.getDefaultProjectId());
        // O cache de vínculos foi invalidado (o vazio de antes não sobrevive)
        mockMvc.perform(get("/api/me/memberships").header("Authorization", token(dev)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberships.length()").value(1))
                .andExpect(jsonPath("$.memberships[0].projectId").value("LRSQ"))
                .andExpect(jsonPath("$.memberships[0].roleName").value("Developer"))
                .andExpect(jsonPath("$.memberships[0].leadership").value(false))
                .andExpect(jsonPath("$.memberships[0].source").value("APPROVED"));
        // Auditoria só com ids e notificação ao solicitante
        assertTrue(auditLogRepository.findAll().stream().anyMatch(a -> "LINK_REQUEST_APPROVED_BY".equals(a.getAction())
                && "lr2-am".equals(a.getPerformedBy()) && a.getDetails().contains("pedido=" + devReq) && a.getDetails().contains("via=SQUAD")
                && !a.getDetails().contains("@")));
        assertTrue(notificationRepository.findAll().stream().anyMatch(n -> "lr2-dev".equals(n.getUserId())
                && "/painel".equals(n.getLink()) && ("LINK_REQUEST_DECIDED:" + devReq).equals(n.getDedupeKey())));

        // Decidir de novo: 409. Recusar pela squad grava o motivo.
        mockMvc.perform(post("/api/squads/LRSQ/link-requests/" + devReq + "/reject").header("Authorization", token(am)))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/squads/LRSQ/link-requests/" + biaReq + "/reject").header("Authorization", token(am))
                        .contentType(MediaType.APPLICATION_JSON).content(json("reason", "Você não é do time")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decisionNote").value("Você não é do time"));
        mockMvc.perform(get("/api/squads/LRSQ/link-requests").param("status", "ALL").header("Authorization", token(am)))
                .andExpect(status().isOk());

        // Squad sem nenhum aprovador: a notificação cai nos ADMINs (link antigo) e o AM de outra squad não enxerga a fila
        User caio = newUser("lr2-caio", "MEMBER");
        String orphanReq = createRequest(caio, "projectKey", "LRSQ4", "emailInJira", "caio@jira.com", "requestedRoleName", "Designer");
        assertTrue(notified("lr2-admin", orphanReq, "/admin?tab=aprovacoes"));
        assertFalse(notified("lr2-am", orphanReq, squadLink));
        mockMvc.perform(get("/api/squads/LRSQ4/link-requests").header("Authorization", token(am))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/squads/LRSQ4/link-requests/" + orphanReq + "/approve").header("Authorization", token(am)))
                .andExpect(status().isForbidden());
        // ADMIN aprova pela rota de admin (mesmo serviço): pmr criada
        mockMvc.perform(post("/api/admin/link-requests/" + orphanReq + "/approve").header("Authorization", token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertTrue(projectMemberRoleRepository.findByUserId("lr2-caio").stream()
                .anyMatch(r -> "LRSQ4".equals(r.getProjectId()) && "Designer".equals(r.getRoleName()) && !r.isLeadership()));
        mockMvc.perform(get("/api/admin/link-requests").param("status", "APPROVED").header("Authorization", token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath(byId(orphanReq) + ".kind").value("CONTRIBUTOR"));
    }
}
