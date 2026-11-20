package com.agilespace.backend.service;

import com.agilespace.backend.controller.AdminController;
import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.domain.PasswordResetRequest;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Gestão de usuários pelo admin: papel, ativação, guardas do último admin e auditoria")
class UserAdminManagementTest {

    private UserRepository repo;
    private AuditLogRepository audit;
    private UserService service;
    private User admin;
    private User otherAdmin;
    private User member;

    @BeforeEach
    void setUp() {
        repo = mock(UserRepository.class);
        audit = mock(AuditLogRepository.class);
        service = new UserService();
        ReflectionTestUtils.setField(service, "userRepository", repo);
        ReflectionTestUtils.setField(service, "auditLogRepository", audit);
        admin = User.builder().id("a1").email("adm@totvs.com.br").name("Adm").role("ADMIN").active(true).build();
        otherAdmin = User.builder().id("a2").email("adm2@totvs.com.br").name("Adm2").role("ADMIN").active(true).build();
        member = User.builder().id("m1").email("m@totvs.com.br").name("Maria").role("MEMBER").active(true).build();
        for (User u : new User[] {admin, otherAdmin, member}) when(repo.findById(u.getId())).thenReturn(Optional.of(u));
        when(repo.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        when(repo.countByRoleIgnoreCaseAndActiveTrue("ADMIN")).thenReturn(2L);
    }

    private User body(String json) throws Exception {
        return new ObjectMapper().readValue(json, User.class);
    }

    @Test
    @DisplayName("Admin que salva só o nome não rebaixa nem reativa a conta (corpo sem role/active)")
    void partialBodyDoesNotTouchRoleOrActive() throws Exception {
        otherAdmin.setActive(false);
        User saved = service.saveUser(body("{\"id\":\"a2\",\"name\":\"Novo Nome\"}"), true, "a1");
        assertEquals("ADMIN", saved.getRole());
        assertFalse(saved.isActive());
        assertEquals("Novo Nome", saved.getName());
    }

    @Test
    @DisplayName("Corpo com role e active explícitos altera e audita com o e-mail do admin")
    void explicitBodyChangesAndAudits() throws Exception {
        User saved = service.saveUser(body("{\"id\":\"m1\",\"role\":\"lead\",\"active\":false}"), true, "a1");
        assertEquals("LEAD", saved.getRole());
        assertFalse(saved.isActive());

        ArgumentCaptor<AuditLog> logs = ArgumentCaptor.forClass(AuditLog.class);
        verify(audit, times(2)).save(logs.capture());
        assertTrue(logs.getAllValues().stream().anyMatch(l -> l.getAction().equals("USER_ROLE_CHANGED")
                && l.getPerformedBy().equals("adm@totvs.com.br") && l.getDetails().contains("MEMBER -> LEAD")));
        assertTrue(logs.getAllValues().stream().anyMatch(l -> l.getAction().equals("USER_DEACTIVATED")));
    }

    @Test
    @DisplayName("Admin não rebaixa nem desativa a si mesmo")
    void adminCannotDemoteOrDeactivateSelf() throws Exception {
        ResponseStatusException demote = assertThrows(ResponseStatusException.class,
                () -> service.saveUser(body("{\"id\":\"a1\",\"role\":\"MEMBER\"}"), true, "a1"));
        assertEquals(409, demote.getStatusCode().value());
        assertThrows(ResponseStatusException.class,
                () -> service.saveUser(body("{\"id\":\"a1\",\"active\":false}"), true, "a1"));
        assertEquals("ADMIN", admin.getRole());
        assertTrue(admin.isActive());
    }

    @Test
    @DisplayName("O último admin ativo não é rebaixado nem desativado por outro admin")
    void lastAdminIsProtected() throws Exception {
        when(repo.countByRoleIgnoreCaseAndActiveTrue("ADMIN")).thenReturn(1L);
        assertThrows(ResponseStatusException.class,
                () -> service.saveUser(body("{\"id\":\"a2\",\"role\":\"MEMBER\"}"), true, "a1"));
        assertThrows(ResponseStatusException.class,
                () -> service.saveUser(body("{\"id\":\"a2\",\"active\":false}"), true, "a1"));
        assertEquals("ADMIN", otherAdmin.getRole());
    }

    @Test
    @DisplayName("Com outro admin ativo, rebaixar é permitido")
    void demoteAllowedWhenAnotherAdminExists() throws Exception {
        User saved = service.saveUser(body("{\"id\":\"a2\",\"role\":\"MEMBER\"}"), true, "a1");
        assertEquals("MEMBER", saved.getRole());
    }

    @Test
    @DisplayName("Não-admin nunca muda papel nem ativo, mesmo mandando no corpo")
    void nonAdminCannotChangeRoleOrActive() throws Exception {
        User saved = service.saveUser(body("{\"id\":\"m1\",\"role\":\"ADMIN\",\"active\":false,\"name\":\"Maria S\"}"), false, "m1");
        assertEquals("MEMBER", saved.getRole());
        assertTrue(saved.isActive());
        assertEquals("Maria S", saved.getName());
    }

    @Test
    @DisplayName("Reset de senha pelo admin gera e aprova o pedido de uma vez")
    void adminResetsPassword() {
        AdminController controller = new AdminController();
        PasswordResetService resets = mock(PasswordResetService.class);
        ReflectionTestUtils.setField(controller, "passwordResetService", resets);
        ReflectionTestUtils.setField(controller, "userRepository", repo);
        PasswordResetRequest pending = PasswordResetRequest.builder().id("p1").userEmail("m@totvs.com.br").build();
        PasswordResetRequest approved = PasswordResetRequest.builder().id("p1").status("APPROVED").tempPassword("Tmp123").build();
        when(resets.requestReset("m@totvs.com.br")).thenReturn(pending);
        when(resets.approveReset("p1", "adm@totvs.com.br")).thenReturn(approved);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtAuthenticationFilter.ATTR_USER_EMAIL, "adm@totvs.com.br");

        assertEquals("Tmp123", controller.resetUserPassword("m1", request).getBody().getTempPassword());
        assertThrows(ResponseStatusException.class, () -> controller.resetUserPassword("nao-existe", request));
    }
}
