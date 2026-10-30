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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JiraPublishControllerTest {

    @Mock JiraPublishService service;
    @InjectMocks JiraPublishController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setup() {
        request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("user-1");
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE)).thenReturn("USER");
    }

    @Test
    void previewRepassaSalaTarefaEQuemPede() {
        JiraPublishDtos.Preview preview = JiraPublishDtos.Preview.builder().parentKey("DDW-1").build();
        when(service.preview("room-1", "iss-1", "user-1", "USER")).thenReturn(preview);

        ResponseEntity<JiraPublishDtos.Preview> r = controller.preview("room-1", "iss-1", request);

        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals("DDW-1", r.getBody().getParentKey());
    }

    @Test
    void submitDevolve201ComOJob() {
        JiraPublishDtos.SubmitRequest body = new JiraPublishDtos.SubmitRequest("iss-1", List.of());
        JiraPublishDtos.Job job = JiraPublishDtos.Job.builder().id("j1").status("DONE").build();
        when(service.submit("room-1", body, "user-1", "USER")).thenReturn(job);

        ResponseEntity<JiraPublishDtos.Job> r = controller.submit("room-1", body, request);

        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        assertEquals("j1", r.getBody().getId());
    }

    @Test
    void listaEhRetentativa() {
        when(service.listForRoom("room-1", "user-1", "USER")).thenReturn(List.of());
        assertEquals(HttpStatus.OK, controller.list("room-1", request).getStatusCode());

        when(service.retry("room-1", "j1", "user-1", "USER")).thenReturn(JiraPublishDtos.Job.builder().id("j1").build());
        assertEquals("j1", controller.retry("room-1", "j1", request).getBody().getId());
    }
}
