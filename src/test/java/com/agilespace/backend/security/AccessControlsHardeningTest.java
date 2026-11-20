package com.agilespace.backend.security;

import com.agilespace.backend.controller.AdminController;
import com.agilespace.backend.controller.ChangelogController;
import com.agilespace.backend.controller.UserController;
import com.agilespace.backend.domain.ApiKey;
import com.agilespace.backend.domain.AppRelease;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.LoginRequestDto;
import com.agilespace.backend.dto.RegisterRequestDto;
import com.agilespace.backend.repository.ApiKeyRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.service.AdminService;
import com.agilespace.backend.service.AppReleaseService;
import com.agilespace.backend.service.AuthService;
import com.agilespace.backend.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("Controles de acesso: token sem validade, chave de conta inativa, cadastro fechado, rascunho, auditoria e vínculos")
class AccessControlsHardeningTest {

    private static final String SECRET = "test-secret-with-at-least-32-bytes-000000";

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String signedToken(String payloadJson) throws Exception {
        String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64(payloadJson.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return header + "." + payload + "." + b64(mac.doFinal((header + "." + payload).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("Token assinado mas sem exp não vale (sessão eterna)")
    void tokenWithoutExpIsRejected() throws Exception {
        JwtTokenUtil util = new JwtTokenUtil(SECRET, 3600);
        assertNull(util.validateAndExtractClaims(signedToken("{\"sub\":\"u1\",\"role\":\"ADMIN\"}")));
        long future = Instant.now().getEpochSecond() + 600;
        JsonNode ok = util.validateAndExtractClaims(signedToken("{\"sub\":\"u1\",\"exp\":" + future + "}"));
        assertNotNull(ok);
        assertNull(util.validateAndExtractClaims(signedToken("{\"sub\":\"u1\",\"exp\":" + (future - 1200) + "}")));
    }

    @Test
    @DisplayName("Chave de API de dono desativado deixa de valer")
    void apiKeyOfInactiveOwnerIsRejected() throws Exception {
        ApiKeyRepository keys = mock(ApiKeyRepository.class);
        UserRepository users = mock(UserRepository.class);
        String raw = "ask_abc";
        ApiKey key = ApiKey.builder().id(UUID.randomUUID()).name("k").keyHash(ApiKeyHashing.sha256Hex(raw))
                .ownerUserId("u1").ownerRole("MEMBER").scopes(Set.of("KNOWLEDGE_READ")).build();
        when(keys.findByKeyHashAndRevokedAtIsNull(ApiKeyHashing.sha256Hex(raw))).thenReturn(Optional.of(key));
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(keys, users);

        when(users.findById("u1")).thenReturn(Optional.of(User.builder().id("u1").email("a@b.c").name("A").active(false).build()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/knowledge/search");
        request.addHeader("X-Api-Key", raw);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(401, response.getStatus());

        when(users.findById("u1")).thenReturn(Optional.of(User.builder().id("u1").email("a@b.c").name("A").active(true).build()));
        MockHttpServletResponse ok = new MockHttpServletResponse();
        filter.doFilter(request, ok, new MockFilterChain());
        assertEquals(200, ok.getStatus());
    }

    @Test
    @DisplayName("/api/v1 sem barra final também passa pelo filtro de chave")
    void apiV1WithoutSlashIsFiltered() throws Exception {
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(mock(ApiKeyRepository.class));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1"), response, new MockFilterChain());
        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("Cadastro fechado por configuração recusa com 403; padrão continua aberto")
    void registrationCanBeClosedByConfig() {
        AuthService service = new AuthService(mock(UserRepository.class), null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "registrationEnabled", false);
        RegisterRequestDto dto = RegisterRequestDto.builder().email("a@totvs.com.br").name("A").password("12345678").build();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.register(dto));
        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    @DisplayName("Login de e-mail inexistente e de senha errada dão a mesma resposta; conta inativa só aparece com a senha certa")
    void loginDoesNotRevealAccountState() {
        UserRepository users = mock(UserRepository.class);
        AuthService service = new AuthService(users, null, null, null, null, null, null);
        User inactive = User.builder().id("u1").email("a@totvs.com.br").name("A").active(false)
                .passwordHash(PasswordUtil.hashPassword("SenhaCerta123")).build();
        when(users.findByEmail("a@totvs.com.br")).thenReturn(Optional.of(inactive));
        when(users.findByEmail("b@totvs.com.br")).thenReturn(Optional.empty());

        ResponseStatusException unknown = assertThrows(ResponseStatusException.class,
                () -> service.login(LoginRequestDto.builder().email("b@totvs.com.br").password("qualquer").build()));
        ResponseStatusException wrongPassword = assertThrows(ResponseStatusException.class,
                () -> service.login(LoginRequestDto.builder().email("a@totvs.com.br").password("errada").build()));
        assertEquals(401, unknown.getStatusCode().value());
        assertEquals(401, wrongPassword.getStatusCode().value());
        assertEquals(unknown.getReason(), wrongPassword.getReason());

        ResponseStatusException inactiveRight = assertThrows(ResponseStatusException.class,
                () -> service.login(LoginRequestDto.builder().email("a@totvs.com.br").password("SenhaCerta123").build()));
        assertEquals(403, inactiveRight.getStatusCode().value());
    }

    @Test
    @DisplayName("Release não publicada não é lida publicamente por id")
    void draftReleaseIsNotPublic() {
        AppReleaseService service = mock(AppReleaseService.class);
        ChangelogController controller = new ChangelogController();
        ReflectionTestUtils.setField(controller, "service", service);
        when(service.getReleaseById("draft")).thenReturn(Optional.of(AppRelease.builder().id("draft").tag("v9").isPublished(false).build()));
        when(service.getReleaseById("pub")).thenReturn(Optional.of(AppRelease.builder().id("pub").tag("v1").isPublished(true).build()));

        assertEquals(404, controller.getById("draft").getStatusCode().value());
        assertEquals(200, controller.getById("pub").getStatusCode().value());
    }

    @Test
    @DisplayName("Registro de auditoria manual usa o autor do token, não o informado")
    void auditAuthorComesFromToken() {
        AdminService service = mock(AdminService.class);
        AdminController controller = new AdminController();
        ReflectionTestUtils.setField(controller, "service", service);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtAuthenticationFilter.ATTR_USER_EMAIL, "adm@totvs.com.br");

        controller.logAction("ACAO", "outra.pessoa@totvs.com.br", "detalhe", request);

        verify(service).logAction(eq("ACAO"), eq("adm@totvs.com.br"), eq("detalhe"));
    }

    @Test
    @DisplayName("Vínculos de squad de uma conta: só o dono ou ADMIN")
    void squadLinksAreOwnerOrAdmin() {
        UserService service = mock(UserService.class);
        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "service", service);
        when(service.getSquadsForUser("u2")).thenReturn(List.of());

        MockHttpServletRequest other = new MockHttpServletRequest();
        other.setAttribute(JwtAuthenticationFilter.ATTR_USER_ID, "u1");
        other.setAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE, "MEMBER");
        assertEquals(403, controller.getSquadsForUser("u2", other).getStatusCode().value());

        MockHttpServletRequest owner = new MockHttpServletRequest();
        owner.setAttribute(JwtAuthenticationFilter.ATTR_USER_ID, "u2");
        owner.setAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE, "MEMBER");
        assertEquals(200, controller.getSquadsForUser("u2", owner).getStatusCode().value());

        MockHttpServletRequest admin = new MockHttpServletRequest();
        admin.setAttribute(JwtAuthenticationFilter.ATTR_USER_ID, "adm");
        admin.setAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE, "ADMIN");
        ResponseEntity<?> r = controller.getSquadsForUser("u2", admin);
        assertEquals(200, r.getStatusCode().value());
    }
}
