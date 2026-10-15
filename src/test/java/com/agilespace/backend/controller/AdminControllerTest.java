package com.agilespace.backend.controller;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.domain.GlobalAnnouncement;
import com.agilespace.backend.service.AdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class AdminControllerTest {

    @Mock
    private AdminService service;

    @InjectMocks
    private AdminController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testGetStats() {
        when(service.getSystemStats()).thenReturn(new HashMap<>());
        ResponseEntity<Map<String, Object>> response = controller.getStats();
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testGetConfigFound() {
        when(service.getConfig("theme")).thenReturn("dark");
        ResponseEntity<String> response = controller.getConfig("theme");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("dark", response.getBody());
    }

    @Test
    public void testGetConfigNotFound() {
        when(service.getConfig("unknown")).thenReturn(null);
        ResponseEntity<String> response = controller.getConfig("unknown");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    public void testCreateAnnouncement() {
        GlobalAnnouncement ann = new GlobalAnnouncement();
        ann.setCreatedBy("forjado");
        when(service.createAnnouncement(ann)).thenReturn(ann);
        jakarta.servlet.http.HttpServletRequest req = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(req.getAttribute(com.agilespace.backend.security.JwtAuthenticationFilter.ATTR_USER_EMAIL)).thenReturn("adm@x.com");

        ResponseEntity<GlobalAnnouncement> response = controller.createAnnouncement(ann, req);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("adm@x.com", ann.getCreatedBy());
    }
}
