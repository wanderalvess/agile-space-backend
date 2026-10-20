package com.agilespace.backend.controller;

import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.SquadAccessService;
import com.agilespace.backend.service.SquadJiraDateFieldsService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SquadJiraDateFieldsControllerTest {

    private SquadJiraDateFieldsService service;
    private SquadAccessService access;
    private SquadJiraDateFieldsController controller;
    private HttpServletRequest request;

    private static SquadJiraDateFieldsService.Response resp() {
        return new SquadJiraDateFieldsService.Response("customfield_1", "customfield_2", "Início", "Fim", false,
                new SquadJiraDateFieldsService.Candidates(List.of(), List.of()), new SquadJiraDateFieldsService.Sample(0, 0, 0), null);
    }

    @BeforeEach
    void setUp() {
        service = mock(SquadJiraDateFieldsService.class);
        access = mock(SquadAccessService.class);
        controller = new SquadJiraDateFieldsController(service, access);
        request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("u1");
    }

    @Test
    void getUsaAcessoDeLeituraEDevolve200() {
        when(service.get("sq", "u1")).thenReturn(resp());

        var r = controller.get("sq", request);

        assertEquals(HttpStatus.OK, r.getStatusCode());
        verify(access).requireSquadReadAccess("sq", request);
    }

    @Test
    void getNegadoPropagaOErroEnaoConsultaOServico() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(access).requireSquadReadAccess("sq", request);

        assertThrows(ResponseStatusException.class, () -> controller.get("sq", request));
        verifyNoInteractions(service);
    }

    @Test
    void putDaLiderancaGrava() {
        when(access.canManageSquad("sq", request)).thenReturn(true);
        when(service.update("sq", "u1", "customfield_1", null)).thenReturn(resp());

        var r = controller.put("sq", new SquadJiraDateFieldsController.UpdateRequest("customfield_1", null), request);

        assertEquals(HttpStatus.OK, r.getStatusCode());
    }

    @Test
    void putSemLiderancaDa403ENaoGrava() {
        when(access.canManageSquad("sq", request)).thenReturn(false);

        var ex = assertThrows(ResponseStatusException.class,
                () -> controller.put("sq", new SquadJiraDateFieldsController.UpdateRequest("customfield_1", "customfield_2"), request));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(service, never()).update(any(), any(), any(), any());
    }

    @Test
    void putComValorInvalidoPropagaA400DoServico() {
        when(access.canManageSquad("sq", request)).thenReturn(true);
        when(service.update(any(), any(), any(), any())).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "inválido"));

        var ex = assertThrows(ResponseStatusException.class,
                () -> controller.put("sq", new SquadJiraDateFieldsController.UpdateRequest("x", null), request));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }
}
