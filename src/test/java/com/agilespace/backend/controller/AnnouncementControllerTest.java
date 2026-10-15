package com.agilespace.backend.controller;

import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.AnnouncementService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class AnnouncementControllerTest {

    @Mock private AnnouncementService service;
    @InjectMocks private AnnouncementController controller;
    private HttpServletRequest request;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("ana");
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE)).thenReturn("MEMBER");
    }

    @Test
    public void activeHistoryDismissUseTokenIdentity() {
        when(service.active("ana", "MEMBER")).thenReturn(List.of());
        when(service.history("ana", "MEMBER", 20)).thenReturn(List.of());

        assertEquals(HttpStatus.OK, controller.active(request).getStatusCode());
        assertEquals(HttpStatus.OK, controller.history(20, request).getStatusCode());
        assertEquals(HttpStatus.NO_CONTENT, controller.dismiss("a1", request).getStatusCode());
        verify(service).dismiss("ana", "MEMBER", "a1");
    }
}
