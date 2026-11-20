package com.agilespace.backend.controller;

import com.agilespace.backend.dto.JiraPublishDtos;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.JiraPublishService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JiraPublishQueueControllerTest {

    @Mock JiraPublishService service;
    @InjectMocks JiraPublishQueueController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setup() {
        request = mock(HttpServletRequest.class);
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("user-1");
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE)).thenReturn("USER");
    }

    @Test
    void filaLogRetryEConferenciaRepassamQuemPede() {
        JiraPublishDtos.SquadQueue q = JiraPublishDtos.SquadQueue.builder().squadId("S").jobs(List.of()).build();
        when(service.listForSquad("S", "user-1", "USER")).thenReturn(q);
        when(service.listEvents("j1", "user-1", "USER")).thenReturn(List.of());
        when(service.retryJob("j1", "user-1", "USER")).thenReturn(JiraPublishDtos.Job.builder().id("j1").build());
        when(service.verify("j1", "user-1", "USER")).thenReturn(JiraPublishDtos.VerifyResult.builder().jobId("j1").allOk(true).build());

        assertEquals("S", controller.queue("S", request).getBody().getSquadId());
        assertEquals(0, controller.events("j1", request).getBody().size());
        assertEquals("j1", controller.retry("j1", request).getBody().getId());
        assertEquals(true, controller.verify("j1", request).getBody().isAllOk());
    }

    @Test
    void descartarDevolve204() {
        ResponseEntity<Void> r = controller.discard("j1", request);
        verify(service).discard("j1", "user-1", "USER");
        assertEquals(HttpStatus.NO_CONTENT, r.getStatusCode());
    }

    @Test
    void erroDeNegocioVaiComMensagemNoCorpo() {
        ResponseEntity<Map<String, String>> r = controller.handleStatus(new ResponseStatusException(HttpStatus.CONFLICT, "Este envio está em andamento."));
        assertEquals(HttpStatus.CONFLICT, r.getStatusCode());
        assertEquals("Este envio está em andamento.", r.getBody().get("message"));
    }
}
