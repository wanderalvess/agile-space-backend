package com.agilespace.backend.controller;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.SaveJiraDashSnapshotRequest;
import com.agilespace.backend.dto.UserProjectAccessDto;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.AuthService;
import com.agilespace.backend.service.JiraProfieldsService;
import com.agilespace.backend.service.UserProjectResolverService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProjectAndDashAccessTest {

    @Mock private JiraProfieldsService jiraProfieldsService;
    @Mock private UserProjectResolverService resolver;
    @Mock private UserRepository userRepository;
    @Mock private AuthService authService;
    @Mock private HttpServletRequest http;

    private ProjectController controller;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        controller = new ProjectController(jiraProfieldsService, resolver, userRepository, authService);
    }

    private User caller(String role) {
        User u = User.builder().id("u1").email("Ana@x.com").jiraAccountId("ana.j").name("Ana").role(role).build();
        when(http.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("u1");
        when(userRepository.findById("u1")).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    void usuarioComumConsultaOsProprioProjetosPorIdEmailOuContaJira() {
        User u = caller("MEMBER");
        when(resolver.resolveUserAccess(u)).thenReturn(UserProjectAccessDto.builder().userId("u1").build());
        when(userRepository.findById("u1")).thenReturn(Optional.of(u));

        assertEquals(200, controller.getUserProjects("u1", http).getStatusCode().value());
        assertEquals(200, controller.getUserProjects("ana@x.com", http).getStatusCode().value());
        assertEquals(200, controller.getUserProjects("ana.j", http).getStatusCode().value());
    }

    @Test
    void usuarioComumNaoConsultaProjetosDeOutraPessoa() {
        caller("MEMBER");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getUserProjects("outra@x.com", http));
        assertEquals(403, ex.getStatusCode().value());
        verifyNoInteractions(resolver);
    }

    @Test
    void adminConsultaQualquerPessoa() {
        caller("ADMIN");
        when(userRepository.findById("outra@x.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("outra@x.com")).thenReturn(Optional.empty());
        when(userRepository.findByJiraAccountId("outra@x.com")).thenReturn(Optional.empty());
        when(resolver.resolveUserAccess(any())).thenReturn(UserProjectAccessDto.builder().build());

        assertEquals(200, controller.getUserProjects("outra@x.com", http).getStatusCode().value());
    }

    // ---- snapshot do JiraDash ----

    private SaveJiraDashSnapshotRequest snap(String jql, String json) throws Exception {
        return new SaveJiraDashSnapshotRequest(jql, json == null ? null : new ObjectMapper().readTree(json));
    }

    @Test
    void snapshotValidoPassa() throws Exception {
        assertNull(JiraDashController.SnapshotValidation.check(snap("project = X", "{\"allIssues\":[],\"sprintDates\":null}")));
    }

    @Test
    void snapshotSemJqlSemIssuesOuComFormatoErradoERecusado() throws Exception {
        assertNotNull(JiraDashController.SnapshotValidation.check(snap(" ", "{\"allIssues\":[]}")));
        assertNotNull(JiraDashController.SnapshotValidation.check(snap("project = X", null)));
        assertNotNull(JiraDashController.SnapshotValidation.check(snap("project = X", "[]")));
        assertNotNull(JiraDashController.SnapshotValidation.check(snap("project = X", "{\"allIssues\":\"x\"}")));
        assertNotNull(JiraDashController.SnapshotValidation.check(snap("x".repeat(4001), "{\"allIssues\":[]}")));
        assertNotNull(JiraDashController.SnapshotValidation.check(null));
    }
}
