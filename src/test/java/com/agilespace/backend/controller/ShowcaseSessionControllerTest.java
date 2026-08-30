package com.agilespace.backend.controller;

import com.agilespace.backend.domain.ShowcaseSession;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.ShowcaseSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class ShowcaseSessionControllerTest {

    @Mock
    private ShowcaseSessionService service;

    @InjectMocks
    private ShowcaseSessionController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testGetSessions() {
        when(service.getLatestSessions(50)).thenReturn(Arrays.asList(new ShowcaseSession()));
        
        ResponseEntity<List<ShowcaseSession>> response = controller.getSessions(50);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
    }

    @Test
    public void testGetSessionFound() {
        when(service.getSession("123")).thenReturn(new ShowcaseSession());
        
        ResponseEntity<ShowcaseSession> response = controller.getSession("123");
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testGetSessionNotFound() {
        when(service.getSession("123")).thenReturn(null);
        
        ResponseEntity<ShowcaseSession> response = controller.getSession("123");
        
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    public void testSaveSession() {
        ShowcaseSession session = new ShowcaseSession();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("user-1");
        when(service.saveSession(session, "user-1")).thenReturn(session);

        ResponseEntity<ShowcaseSession> response = controller.saveSession(session, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }
}
