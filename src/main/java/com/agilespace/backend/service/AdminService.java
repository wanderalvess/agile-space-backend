package com.agilespace.backend.service;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.domain.GlobalAnnouncement;
import com.agilespace.backend.domain.SystemConfig;
import com.agilespace.backend.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class AdminService {

    @Autowired
    private SystemConfigRepository configRepository;

    @Autowired
    private GlobalAnnouncementRepository announcementRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // --- System Configs ---
    @Transactional(readOnly = true)
    public String getConfig(String key) {
        return configRepository.findById(key)
                .map(SystemConfig::getValue)
                .orElse(null);
    }

    @Transactional
    public void setConfig(String key, String value) {
        SystemConfig config = SystemConfig.builder()
                .key(key)
                .value(value)
                .build();
        configRepository.save(config);
    }

    // --- Announcements ---
    @Transactional(readOnly = true)
    public List<GlobalAnnouncement> getAnnouncements() {
        return announcementRepository.findByOrderByCreatedAtDesc();
    }

    @Transactional
    public GlobalAnnouncement createAnnouncement(GlobalAnnouncement announcement) {
        if (announcement.getId() == null || announcement.getId().isEmpty()) {
            announcement.setId(UUID.randomUUID().toString());
        }
        announcement.setCreatedAt(LocalDateTime.now());
        return announcementRepository.save(announcement);
    }

    @Transactional
    public void deleteAnnouncement(String id) {
        announcementRepository.deleteById(id);
    }

    // --- Audit Logs ---
    @Transactional(readOnly = true)
    public List<AuditLog> getAuditLogs() {
        return auditLogRepository.findByOrderByCreatedAtDesc();
    }

    @Transactional
    public AuditLog logAction(String action, String performedBy, String details) {
        AuditLog log = AuditLog.builder()
                .id(UUID.randomUUID().toString())
                .action(action)
                .performedBy(performedBy)
                .details(details)
                .createdAt(LocalDateTime.now())
                .build();
        return auditLogRepository.save(log);
    }

    // --- Aggregated Stats ---
    @Transactional(readOnly = true)
    public Map<String, Object> getSystemStats() {
        Map<String, Object> stats = new HashMap<>();

        // Executando contagens dinâmicas nas tabelas do Postgres
        long usersCount = countTableRows("users");
        long showcaseCount = countTableRows("showcase_sessions");
        long feedbackCount = countTableRows("feedbacks");
        long secretsCount = countTableRows("vault_secrets");
        long focusCount = countTableRows("user_focus_sessions");
        long kanbanCount = countTableRows("user_kanban_cards");

        stats.put("totalUsers", usersCount);
        stats.put("totalShowcaseSessions", showcaseCount);
        stats.put("totalFeedbacks", feedbackCount);
        stats.put("totalVaultSecrets", secretsCount);
        stats.put("totalFocusSessions", focusCount);
        stats.put("totalKanbanCards", kanbanCount);

        return stats;
    }

    private long countTableRows(String tableName) {
        try {
            Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
            return count != null ? count : 0L;
        } catch (Exception e) {
            // Tabela pode não existir ainda se a inicialização for preguiçosa
            return 0L;
        }
    }
}
