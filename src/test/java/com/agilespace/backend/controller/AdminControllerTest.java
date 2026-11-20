package com.agilespace.backend.controller;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.dto.AnnouncementDto;
import com.agilespace.backend.dto.AnnouncementRequest;
import com.agilespace.backend.service.AdminService;
import com.agilespace.backend.service.AnnouncementService;
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

    @Mock
    private AnnouncementService announcementService;

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
    public void testCreateAnnouncementUsesAuthorFromToken() {
        AnnouncementRequest body = new AnnouncementRequest("t", "c", "INFO", "ALL", List.of(), List.of(), null, null, null);
        AnnouncementDto dto = new AnnouncementDto("a1", "t", "c", "INFO", "ALL", List.of(), List.of(),
                java.time.Instant.now(), null, null, "adm@x.com", "Adm", java.time.Instant.now(), false);
        jakarta.servlet.http.HttpServletRequest req = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(req.getAttribute(com.agilespace.backend.security.JwtAuthenticationFilter.ATTR_USER_EMAIL)).thenReturn("adm@x.com");
        when(req.getAttribute(com.agilespace.backend.security.JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("adm");
        when(announcementService.create(body, "adm@x.com", "adm")).thenReturn(dto);

        ResponseEntity<AnnouncementDto> response = controller.createAnnouncement(body, req);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(dto, response.getBody());
    }

    @Test
    public void testUpdateAndDeleteAnnouncement() {
        AnnouncementRequest body = new AnnouncementRequest("t", "c", "IMPORTANT", "ROLES", List.of(), List.of("AGILE_MASTER"), null, null, null);
        jakarta.servlet.http.HttpServletRequest req = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(req.getAttribute(com.agilespace.backend.security.JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("adm");

        assertEquals(HttpStatus.OK, controller.updateAnnouncement("a1", body, req).getStatusCode());
        verify(announcementService).update("a1", body, "adm");
        assertEquals(HttpStatus.NO_CONTENT, controller.deleteAnnouncement("a1").getStatusCode());
        verify(announcementService).delete("a1");
        controller.getAnnouncements();
        verify(announcementService).adminList();
    }
}
