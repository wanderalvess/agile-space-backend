package com.agilespace.backend.security;

import com.agilespace.backend.controller.UserController;
import com.agilespace.backend.domain.ApiKey;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.domain.UserJiraConfig;
import com.agilespace.backend.domain.UserTdnConfig;
import com.agilespace.backend.repository.ApiKeyRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Validade das chaves de API e token de Jira/TDN escondido do admin")
class ApiKeyExpiryTest {

    private ApiKeyRepository keys;
    private UserRepository users;
    private ApiKeyLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        keys = mock(ApiKeyRepository.class);
        users = mock(UserRepository.class);
        when(keys.save(any(ApiKey.class))).thenAnswer(i -> i.getArgument(0));
        lifecycle = new ApiKeyLifecycle(keys, users);
    }

    private ApiKey key(String owner, String ownerRole, Set<String> scopes, String squad) {
        return ApiKey.builder().id(UUID.randomUUID()).name("k").keyHash("h").ownerUserId(owner).ownerRole(ownerRole)
                .scopes(new HashSet<>(scopes)).squadId(squad).build();
    }

    private User user(String id, String role, boolean active, String squad) {
        return User.builder().id(id).email(id + "@totvs.com.br").name(id).role(role).active(active).defaultProjectId(squad).build();
    }

    @Test
    @DisplayName("Validade: padrão 90 dias; 30/90/180 aceitos; 'sem expiração' só para ADMIN; lixo é recusado")
    void resolveExpiry() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 13, 12, 0);
        assertEquals(now.plusDays(90), ApiKeyLifecycle.resolveExpiry(null, false, now));
        assertEquals(now.plusDays(30), ApiKeyLifecycle.resolveExpiry(30, false, now));
        assertEquals(now.plusDays(180), ApiKeyLifecycle.resolveExpiry("180", false, now));
        assertNull(ApiKeyLifecycle.resolveExpiry("never", true, now));
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> ApiKeyLifecycle.resolveExpiry("never", false, now)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> ApiKeyLifecycle.resolveExpiry(365, true, now)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> ApiKeyLifecycle.resolveExpiry("abc", true, now)).getStatusCode().value());
    }

    @Test
    @DisplayName("Filtro recusa chave vencida com 401 e mensagem clara; chave sem validade ou no prazo passa")
    void filterRejectsExpired() throws Exception {
        String raw = "ask_x";
        ApiKey expired = key("u1", "ADMIN", Set.of("KNOWLEDGE_READ"), null);
        expired.setExpiresAt(LocalDateTime.now().minusDays(1));
        when(keys.findByKeyHashAndRevokedAtIsNull(ApiKeyHashing.sha256Hex(raw))).thenReturn(Optional.of(expired));
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(keys);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/knowledge/search");
        request.addHeader("X-Api-Key", raw);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("expirada"));

        expired.setExpiresAt(LocalDateTime.now().plusDays(5));
        MockHttpServletResponse ok = new MockHttpServletResponse();
        filter.doFilter(request, ok, new MockFilterChain());
        assertEquals(200, ok.getStatus());

        expired.setExpiresAt(null);
        MockHttpServletResponse never = new MockHttpServletResponse();
        filter.doFilter(request, never, new MockFilterChain());
        assertEquals(200, never.getStatus());
    }

    @Test
    @DisplayName("Renovar estende 90 dias e reavalia: chave legada de MEMBER perde escopos de LEAD e ganha a squad")
    void renewReevaluatesLegacyKey() {
        when(users.findById("m1")).thenReturn(Optional.of(user("m1", "MEMBER", true, "DDW")));
        ApiKey legacy = key("m1", null, Set.of(), null);

        ApiKey renewed = lifecycle.renew(legacy, null, false);

        assertTrue(renewed.getExpiresAt().isAfter(LocalDateTime.now().plusDays(89)));
        assertEquals("MEMBER", renewed.getOwnerRole());
        assertEquals(Set.of("KNOWLEDGE_READ", "SQUAD_READ"), renewed.getScopes());
        assertEquals("DDW", renewed.getSquadId());
    }

    @Test
    @DisplayName("Renovar mantém o acesso de quem ainda é ADMIN e corta o de quem foi rebaixado")
    void renewFollowsCurrentRole() {
        when(users.findById("a1")).thenReturn(Optional.of(user("a1", "ADMIN", true, null)));
        ApiKey adminKey = key("a1", "ADMIN", Set.of("POKER_WRITE", "KNOWLEDGE_READ"), null);
        assertEquals(Set.of("POKER_WRITE", "KNOWLEDGE_READ"), lifecycle.renew(adminKey, 30, true).getScopes());

        when(users.findById("d1")).thenReturn(Optional.of(user("d1", "MEMBER", true, "DDW")));
        ApiKey demoted = key("d1", "ADMIN", Set.of("POKER_WRITE", "KNOWLEDGE_READ"), null);
        ApiKey out = lifecycle.renew(demoted, 30, false);
        assertEquals(Set.of("KNOWLEDGE_READ"), out.getScopes());
        assertEquals("MEMBER", out.getOwnerRole());
    }

    @Test
    @DisplayName("Não renova chave revogada nem de dono inativo; 'sem expiração' na renovação só ADMIN")
    void renewGuards() {
        ApiKey revoked = key("m1", "MEMBER", Set.of("KNOWLEDGE_READ"), null);
        revoked.setRevokedAt(LocalDateTime.now());
        assertThrows(ResponseStatusException.class, () -> lifecycle.renew(revoked, null, false));

        when(users.findById("x")).thenReturn(Optional.of(user("x", "MEMBER", false, null)));
        assertThrows(ResponseStatusException.class, () -> lifecycle.renew(key("x", "MEMBER", Set.of("KNOWLEDGE_READ"), null), null, false));

        when(users.findById("m1")).thenReturn(Optional.of(user("m1", "MEMBER", true, "DDW")));
        assertThrows(ResponseStatusException.class, () -> lifecycle.renew(key("m1", "MEMBER", Set.of("KNOWLEDGE_READ"), null), "never", false));
    }

    @Test
    @DisplayName("Rotação cria chave nova com os mesmos escopos e revoga a antiga")
    void rotate() {
        when(users.findById("a1")).thenReturn(Optional.of(user("a1", "ADMIN", true, null)));
        ApiKey old = key("a1", "ADMIN", Set.of("POKER_WRITE"), null);

        ApiKeyLifecycle.Rotated rotated = lifecycle.rotate(old, null, true);

        assertTrue(rotated.rawKey().startsWith("ask_"));
        assertEquals(ApiKeyHashing.sha256Hex(rotated.rawKey()), rotated.key().getKeyHash());
        assertEquals(Set.of("POKER_WRITE"), rotated.key().getScopes());
        assertNotNull(old.getRevokedAt());
        assertNotNull(rotated.key().getExpiresAt());
    }

    // ---------- tokens de Jira / TDN ----------

    private UserController controllerWith(UserService service) {
        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "service", service);
        return controller;
    }

    private MockHttpServletRequest as(String id, String role) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setAttribute(JwtAuthenticationFilter.ATTR_USER_ID, id);
        r.setAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE, role);
        return r;
    }

    @Test
    @DisplayName("Dono recebe o token do Jira; ADMIN lendo outra pessoa recebe só configured + domain")
    void adminDoesNotReadJiraToken() {
        UserService service = mock(UserService.class);
        UserJiraConfig cfg = new UserJiraConfig();
        cfg.setUserId("u2");
        cfg.setDomain("jira.totvs.com.br");
        cfg.setToken("SEGREDO-PAT");
        when(service.getJiraConfig("u2")).thenReturn(cfg);
        UserController controller = controllerWith(service);

        assertSame(cfg, controller.getJiraConfig("u2", as("u2", "MEMBER")).getBody());

        ResponseEntity<?> adminView = controller.getJiraConfig("u2", as("adm", "ADMIN"));
        assertEquals(200, adminView.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) adminView.getBody();
        assertEquals(true, body.get("configured"));
        assertEquals("jira.totvs.com.br", body.get("domain"));
        assertFalse(body.containsKey("token"));
        assertFalse(adminView.getBody().toString().contains("SEGREDO-PAT"));

        assertEquals(403, controller.getJiraConfig("u2", as("u3", "MEMBER")).getStatusCode().value());
    }

    @Test
    @DisplayName("Mesmo para o TDN; e o admin não grava token em nome de outra pessoa, mas pode remover")
    void tdnMaskedAndAdminCannotWrite() {
        UserService service = mock(UserService.class);
        UserTdnConfig cfg = new UserTdnConfig();
        cfg.setUserId("u2");
        cfg.setBaseUrl("https://tdn.totvs.com");
        cfg.setToken("SEGREDO-TDN");
        when(service.getTdnConfig("u2")).thenReturn(cfg);
        UserController controller = controllerWith(service);

        assertSame(cfg, controller.getTdnConfig("u2", as("u2", "MEMBER")).getBody());
        Map<?, ?> body = (Map<?, ?>) controller.getTdnConfig("u2", as("adm", "ADMIN")).getBody();
        assertEquals(true, body.get("configured"));
        assertFalse(body.toString().contains("SEGREDO-TDN"));

        assertEquals(403, controller.saveJiraConfig("u2", new UserJiraConfig(), as("adm", "ADMIN")).getStatusCode().value());
        assertEquals(403, controller.saveTdnConfig("u2", new UserTdnConfig(), as("adm", "ADMIN")).getStatusCode().value());
        assertEquals(204, controller.deleteJiraConfig("u2", as("adm", "ADMIN")).getStatusCode().value());
    }

    @Test
    @DisplayName("Sem configuração ainda: 204 (estado normal), não 404; outra pessoa continua 403")
    void notConfiguredIsNoContent() {
        UserService service = mock(UserService.class);
        UserController controller = controllerWith(service);

        assertEquals(204, controller.getJiraConfig("u2", as("u2", "MEMBER")).getStatusCode().value());
        assertEquals(204, controller.getTdnConfig("u2", as("u2", "MEMBER")).getStatusCode().value());
        assertEquals(403, controller.getJiraConfig("u2", as("u3", "MEMBER")).getStatusCode().value());
        assertEquals(403, controller.getTdnConfig("u2", as("u3", "MEMBER")).getStatusCode().value());
    }
}
