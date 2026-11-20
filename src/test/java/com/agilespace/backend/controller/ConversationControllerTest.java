package com.agilespace.backend.controller;

import com.agilespace.backend.dto.ConversationDto;
import com.agilespace.backend.dto.MessageDto;
import com.agilespace.backend.dto.UserRefDto;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.ConversationService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ConversationControllerTest {

    @Mock private ConversationService service;
    @InjectMocks private ConversationController controller;
    private HttpServletRequest request;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("ana");
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE)).thenReturn("MEMBER");
    }

    @Test
    public void openUsesCallerFromToken() {
        ConversationDto dto = new ConversationDto("c1", new UserRefDto("bob", "Bob", null), null, 0, Instant.now());
        when(service.open("ana", "MEMBER", "bob")).thenReturn(dto);
        ResponseEntity<ConversationDto> res = controller.open(new ConversationController.OpenConversationRequest("bob"), request);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertSame(dto, res.getBody());
    }

    @Test
    public void forbiddenPropagates() {
        when(service.open("ana", "MEMBER", "eve")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThrows(ResponseStatusException.class,
                () -> controller.open(new ConversationController.OpenConversationRequest("eve"), request));
    }

    @Test
    public void listContactsMessagesSendRead() {
        when(service.list("ana")).thenReturn(List.of());
        when(service.contacts("ana", "MEMBER")).thenReturn(List.of(new UserRefDto("bob", "Bob", null)));
        when(service.messages("ana", "c1", 20, null)).thenReturn(List.of());
        MessageDto msg = new MessageDto("m1", "c1", "ana", "oi", Instant.now());
        when(service.send("ana", "c1", "oi")).thenReturn(msg);

        assertEquals(HttpStatus.OK, controller.list(request).getStatusCode());
        assertEquals(1, controller.contacts(request).getBody().size());
        assertEquals(HttpStatus.OK, controller.messages("c1", 20, null, request).getStatusCode());
        assertSame(msg, controller.send("c1", new ConversationController.SendMessageRequest("oi"), request).getBody());
        assertEquals(HttpStatus.NO_CONTENT, controller.read("c1", request).getStatusCode());
        verify(service).markRead("ana", "c1");
    }
}
