package com.agilespace.backend.controller;

import com.agilespace.backend.domain.SquadSubtaskTemplate;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.SquadAccessService;
import com.agilespace.backend.service.SquadSubtaskTemplateService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SquadSubtaskTemplateControllerTest {

    @Mock SquadSubtaskTemplateService service;
    @Mock SquadAccessService squadAccessService;
    @InjectMocks SquadSubtaskTemplateController controller;

    @Test
    void leituraExigeAcessoDeLeituraNaSquad() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(service.list("DDW")).thenReturn(List.of());

        assertEquals(HttpStatus.OK, controller.list("DDW", request).getStatusCode());
        verify(squadAccessService).requireSquadReadAccess("DDW", request);
    }

    @Test
    void escritaSoParaQuemGereASquad() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(squadAccessService.canManageSquad("DDW", request)).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.replaceAll("DDW", List.of(), request));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(service, never()).replaceAll(any(), any());
    }

    @Test
    void escritaLiberadaParaLideranca() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(squadAccessService.canManageSquad("DDW", request)).thenReturn(true);
        List<SquadSubtaskTemplate> body = List.of(SquadSubtaskTemplate.builder().jiraIssueTypeName("Codificação").build());
        when(service.replaceAll("DDW", body)).thenReturn(body);

        assertEquals(HttpStatus.OK, controller.replaceAll("DDW", body, request).getStatusCode());
    }

    @Test
    void tiposDoJiraUsamOUsuarioDoToken() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("u1");
        when(service.listJiraIssueTypes("DDW", "u1")).thenReturn(List.of());

        assertEquals(HttpStatus.OK, controller.listJiraIssueTypes("DDW", request).getStatusCode());
        verify(squadAccessService).requireSquadReadAccess("DDW", request);
    }
}
