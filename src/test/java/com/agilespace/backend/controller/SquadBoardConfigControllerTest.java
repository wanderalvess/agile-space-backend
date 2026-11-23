package com.agilespace.backend.controller;

import com.agilespace.backend.domain.SquadBoardConfig;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.SquadAccessService;
import com.agilespace.backend.service.SquadBoardConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SquadBoardConfigControllerTest {

    @Mock SquadBoardConfigService service;
    @Mock SquadAccessService squadAccessService;
    @InjectMocks SquadBoardConfigController controller;

    private final JsonNode body = new ObjectMapper().createObjectNode();

    @Test
    void lerExigeAcessoNaSquad() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(service.get("DDW")).thenReturn(SquadBoardConfig.builder().squadId("DDW").build());

        assertEquals(HttpStatus.OK, controller.get("DDW", request).getStatusCode());
        verify(squadAccessService).requireSquadReadAccess("DDW", request);
    }

    @Test
    void membroSemLiderancaNaoGravaEOMotivoVaiNaResposta() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(squadAccessService.canManageSquad("DDW", request)).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.patch("DDW", body, request));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(service, never()).patch(any(), any(), any());
    }

    @Test
    void semAcessoNaSquadNaoGrava() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(squadAccessService).requireSquadReadAccess("DDW", request);

        assertThrows(ResponseStatusException.class, () -> controller.patch("DDW", body, request));
        verify(service, never()).patch(any(), any(), any());
    }

    @Test
    void liderancaGravaComOIdDeQuemEstaLogado() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("lider-1");
        when(squadAccessService.canManageSquad("DDW", request)).thenReturn(true);
        when(service.patch("DDW", body, "lider-1")).thenReturn(SquadBoardConfig.builder().squadId("DDW").build());

        assertEquals(HttpStatus.OK, controller.patch("DDW", body, request).getStatusCode());
    }
}
