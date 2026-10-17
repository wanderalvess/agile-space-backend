package com.agilespace.backend.controller;

import com.agilespace.backend.dto.AnnouncementDto;
import com.agilespace.backend.service.AdminService;
import com.agilespace.backend.service.AnnouncementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class PublicControllerTest {

    @Mock
    private AdminService adminService;

    @Mock
    private AnnouncementService announcementService;

    @InjectMocks
    private PublicController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testSystemConfigExposesOnlyWhitelistedKeys() {
        when(adminService.getConfig(anyString())).thenReturn("valor");

        ResponseEntity<Map<String, String>> response = controller.getSystemConfig();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(
                java.util.Set.of("companyName", "primaryColor"),
                response.getBody().keySet());
        // Chaves aposentadas não vazam mesmo que ainda existam gravadas no banco.
        verify(adminService, never()).getConfig("logoUrl");
        verify(adminService, never()).getConfig("allowAnonymous");
        verify(adminService, never()).getConfig("maintenanceMode");
        // Nenhuma chave sensivel pode ser lida pelo endpoint pre-login.
        verify(adminService, never()).getConfig("jiraApiToken");
        verify(adminService, never()).getConfig("openAiApiKey");
    }

    @Test
    public void testSystemConfigOmitsUnsetKeys() {
        when(adminService.getConfig(anyString())).thenReturn(null);
        when(adminService.getConfig("companyName")).thenReturn("Empresa X");

        Map<String, String> config = controller.getSystemConfig().getBody();

        assertEquals(1, config.size());
        assertEquals("Empresa X", config.get("companyName"));
    }

    @Test
    public void testSystemConfigIsReadOnlyForAdminService() {
        when(adminService.getConfig(anyString())).thenReturn("valor");

        controller.getSystemConfig();

        verify(adminService, times(2)).getConfig(anyString());
        verifyNoMoreInteractions(adminService);
    }

    @Test
    public void testGetAnnouncementsUsesPublicFilter() {
        AnnouncementDto dto = new AnnouncementDto("a1", "t", "c", "INFO", "ALL", List.of(), List.of(),
                java.time.Instant.now(), null, null, "adm@x.com", "Adm", java.time.Instant.now(), false);
        when(announcementService.publicActive()).thenReturn(List.of(dto));

        ResponseEntity<List<AnnouncementDto>> response = controller.getAnnouncements();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        verifyNoInteractions(adminService);
    }
}
