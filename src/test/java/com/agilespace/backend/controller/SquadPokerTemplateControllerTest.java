package com.agilespace.backend.controller;

import com.agilespace.backend.domain.SquadPokerTemplate;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.SquadAccessService;
import com.agilespace.backend.service.SquadPokerTemplateService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SquadPokerTemplateControllerTest {

    @Mock SquadPokerTemplateService service;
    @Mock SquadAccessService squadAccessService;
    @InjectMocks SquadPokerTemplateController controller;

    @Test
    void listarExigeAcessoNaSquad() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(service.list("DDW")).thenReturn(List.of());

        assertEquals(HttpStatus.OK, controller.list("DDW", request).getStatusCode());
        verify(squadAccessService).requireSquadReadAccess("DDW", request);
    }

    @Test
    void semAcessoNaSquadNaoSalva() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(squadAccessService).requireSquadReadAccess("DDW", request);

        assertThrows(ResponseStatusException.class, () -> controller.create("DDW", new SquadPokerTemplate(), request));
        verify(service, never()).create(any(), any(), any());
    }

    @Test
    void salvarUsaQuemEstaLogado() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("u1");
        SquadPokerTemplate body = SquadPokerTemplate.builder().name("Refino").deckType("hours").build();
        when(service.create("DDW", body, "u1")).thenReturn(body);

        assertEquals(HttpStatus.CREATED, controller.create("DDW", body, request).getStatusCode());
    }

    @Test
    void excluirPassaSeQuemPedeLideraASquad() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("u2");
        when(squadAccessService.canManageSquad("DDW", request)).thenReturn(true);

        assertEquals(HttpStatus.NO_CONTENT, controller.delete("DDW", "t1", request).getStatusCode());
        verify(service).delete("DDW", "t1", "u2", true);
    }
}
