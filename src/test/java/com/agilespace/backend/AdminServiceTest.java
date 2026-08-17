package com.agilespace.backend;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.domain.GlobalAnnouncement;
import com.agilespace.backend.domain.SystemConfig;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.repository.GlobalAnnouncementRepository;
import com.agilespace.backend.repository.SystemConfigRepository;
import com.agilespace.backend.service.AdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class AdminServiceTest {

    @Mock
    private SystemConfigRepository configRepository;

    @Mock
    private GlobalAnnouncementRepository announcementRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private AdminService service;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testGetConfigValue() {
        SystemConfig config = SystemConfig.builder().key("health_check_ping").value("active").build();
        when(configRepository.findById("health_check_ping")).thenReturn(Optional.of(config));

        String value = service.getConfig("health_check_ping");

        assertEquals("active", value);
        verify(configRepository, times(1)).findById("health_check_ping");
    }

    @Test
    public void testGetConfigNotFound() {
        when(configRepository.findById("unknown")).thenReturn(Optional.empty());
        assertNull(service.getConfig("unknown"));
    }

    @Test
    public void testSetConfig() {
        SystemConfig config = SystemConfig.builder().key("maintenance_mode").value("true").build();
        when(configRepository.save(any(SystemConfig.class))).thenReturn(config);

        service.setConfig("maintenance_mode", "true");

        verify(configRepository, times(1)).save(any(SystemConfig.class));
    }

    @Test
    public void testGetAnnouncements() {
        when(announcementRepository.findByOrderByCreatedAtDesc()).thenReturn(Arrays.asList(new GlobalAnnouncement()));
        List<GlobalAnnouncement> list = service.getAnnouncements();
        assertEquals(1, list.size());
    }

    @Test
    public void testCreateAnnouncementGeneratesId() {
        GlobalAnnouncement announcement = GlobalAnnouncement.builder()
                .title("Aviso Geral")
                .content("Backup agendado")
                .createdBy("admin")
                .build();

        when(announcementRepository.save(any(GlobalAnnouncement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GlobalAnnouncement saved = service.createAnnouncement(announcement);

        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        assertEquals("Aviso Geral", saved.getTitle());
        verify(announcementRepository, times(1)).save(announcement);
    }

    @Test
    public void testDeleteAnnouncement() {
        service.deleteAnnouncement("123");
        verify(announcementRepository, times(1)).deleteById("123");
    }

    @Test
    public void testGetAuditLogs() {
        when(auditLogRepository.findByOrderByCreatedAtDesc()).thenReturn(Arrays.asList(new AuditLog()));
        List<AuditLog> list = service.getAuditLogs();
        assertEquals(1, list.size());
    }

    @Test
    public void testLogActionGeneratesLog() {
        AuditLog log = AuditLog.builder()
                .action("update_role")
                .performedBy("admin-email")
                .details("role updated to admin")
                .build();

        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuditLog saved = service.logAction("update_role", "admin-email", "role updated to admin");

        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        assertEquals("update_role", saved.getAction());
        verify(auditLogRepository, times(1)).save(any(AuditLog.class));
    }

    @Test
    public void testGetSystemStatsSuccess() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(10L);

        Map<String, Object> stats = service.getSystemStats();

        assertEquals(10L, stats.get("totalUsers"));
        assertEquals(10L, stats.get("totalShowcaseSessions"));
        assertEquals(10L, stats.get("totalFeedbacks"));
        assertEquals(10L, stats.get("totalVaultSecrets"));
        assertEquals(10L, stats.get("totalFocusSessions"));
        assertEquals(10L, stats.get("totalKanbanCards"));
    }

    @Test
    public void testGetSystemStatsExceptionHandled() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenThrow(new RuntimeException("Table not found"));

        Map<String, Object> stats = service.getSystemStats();

        assertEquals(0L, stats.get("totalUsers"));
    }
}
