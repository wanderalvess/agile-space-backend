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

import com.agilespace.backend.dto.UnifiedSessionDto;

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
        long releasesCount = countTableRows("app_releases");
        long pokerRoomsCount = countTableRows("poker_rooms");
        long retroBoardsCount = countTableRows("retro_boards");
        long sprintPlanningsCount = countTableRows("sprint_plannings");
        long healthCheckBoardsCount = countTableRows("health_check_boards");
        long brainstormingBoardsCount = countTableRows("brainstorming_boards");
        long dailyCheckinsCount = countTableRows("daily_checkins");

        stats.put("totalUsers", usersCount);
        stats.put("totalShowcaseSessions", showcaseCount);
        stats.put("totalFeedbacks", feedbackCount);
        stats.put("totalVaultSecrets", secretsCount);
        stats.put("totalFocusSessions", focusCount);
        stats.put("totalKanbanCards", kanbanCount);
        stats.put("totalReleases", releasesCount);
        stats.put("totalPokerRooms", pokerRoomsCount);
        stats.put("totalRetroBoards", retroBoardsCount);
        stats.put("totalSprintPlannings", sprintPlanningsCount);
        stats.put("totalHealthCheckBoards", healthCheckBoardsCount);
        stats.put("totalBrainstormingBoards", brainstormingBoardsCount);
        stats.put("totalDailyCheckins", dailyCheckinsCount);

        // Total de cerimônias em grupo (exclui foco/daily/kanban, que são individuais,
        // não "sessões" com participantes) — número que o admin usa pra "uso do sistema".
        stats.put("totalSessions", pokerRoomsCount + retroBoardsCount + sprintPlanningsCount
                + healthCheckBoardsCount + brainstormingBoardsCount + showcaseCount);
        stats.put("totalParticipations", countTotalParticipations());

        Map<String, Object> duration = computeAverageSessionDuration();
        stats.put("avgSessionDurationMinutes", duration.get("avgMinutes"));
        stats.put("sessionDurationSampleSize", duration.get("sampleSize"));

        return stats;
    }

    /**
     * Tempo médio de uso (minutos) = média de (updated_at - created_at) das cerimônias
     * que têm timestamp de atividade real: retro_boards e showcase_sessions (as únicas
     * com @UpdateTimestamp hoje — poker/health/brainstorm ainda guardam created_at como
     * String solta, sem coluna de última atividade, ver histórico de auditoria). Filtra
     * duração entre 1 min e 7 dias pra cortar ruído (sessão criada e nunca mais tocada
     * cai fora, não deveria contar como "1 semana de uso"). updated_at só populado a
     * partir de agora (coluna nova) — enquanto não houver atividade real pós-deploy,
     * sampleSize vem 0 e o frontend mostra "Em breve" em vez de inventar minuto zero.
     */
    private Map<String, Object> computeAverageSessionDuration() {
        String sql =
                "SELECT AVG(duration_minutes) AS avg_minutes, COUNT(*) AS sample_size FROM (" +
                "  SELECT EXTRACT(EPOCH FROM (updated_at - created_at)) / 60 AS duration_minutes " +
                "  FROM retro_boards WHERE updated_at IS NOT NULL AND updated_at > created_at " +
                "  UNION ALL " +
                "  SELECT EXTRACT(EPOCH FROM (updated_at - created_at)) / 60 " +
                "  FROM showcase_sessions WHERE updated_at IS NOT NULL AND updated_at > created_at " +
                ") d WHERE duration_minutes BETWEEN 1 AND 10080";

        Map<String, Object> result = new HashMap<>();
        try {
            jdbcTemplate.queryForMap(sql).forEach((k, v) -> {
                if ("avg_minutes".equals(k)) result.put("avgMinutes", v == null ? null : ((Number) v).doubleValue());
                if ("sample_size".equals(k)) result.put("sampleSize", ((Number) v).longValue());
            });
        } catch (Exception e) {
            result.put("avgMinutes", null);
            result.put("sampleSize", 0L);
        }
        return result;
    }

    /**
     * Soma o tamanho do array de participantes/membros em cada tipo de cerimônia que
     * de fato rastreia isso. retro_boards fica de fora: a entidade RetroBoard não tem
     * nenhuma coluna de participantes hoje (getSessions() já hardcoda 0 pra esse tipo),
     * então incluir daria uma soma artificialmente baixa sem deixar isso óbvio — melhor
     * omitir do que fingir que é zero de verdade.
     */
    private long countTotalParticipations() {
        String sql =
                "SELECT " +
                "  COALESCE((SELECT SUM(jsonb_array_length(participant_ids)) FROM poker_rooms WHERE jsonb_typeof(participant_ids) = 'array'), 0) + " +
                "  COALESCE((SELECT SUM(jsonb_array_length(participant_ids)) FROM health_check_boards WHERE jsonb_typeof(participant_ids) = 'array'), 0) + " +
                "  COALESCE((SELECT SUM(jsonb_array_length(participant_ids)) FROM brainstorming_boards WHERE jsonb_typeof(participant_ids) = 'array'), 0) + " +
                "  COALESCE((SELECT SUM(jsonb_array_length(members)) FROM sprint_plannings WHERE jsonb_typeof(members) = 'array'), 0) + " +
                "  COALESCE((SELECT SUM(jsonb_array_length(members)) FROM showcase_sessions WHERE jsonb_typeof(members) = 'array'), 0) " +
                "AS total";
        try {
            Long total = jdbcTemplate.queryForObject(sql, Long.class);
            return total != null ? total : 0L;
        } catch (Exception e) {
            return 0L;
        }
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

    @Transactional(readOnly = true)
    public List<UnifiedSessionDto> getSessions() {
        String sql = "SELECT id, 'poker' as type, title, creator_id as creator_id, created_at as created_at, " +
                     "jsonb_array_length(CASE WHEN jsonb_typeof(participant_ids) = 'array' THEN participant_ids ELSE '[]'::jsonb END) as participant_count " +
                     "FROM poker_rooms " +
                     "UNION ALL " +
                     "SELECT id, 'retro' as type, title, creator_id as creator_id, CAST(created_at AS VARCHAR) as created_at, 0 as participant_count " +
                     "FROM retro_boards " +
                     "UNION ALL " +
                     "SELECT id, 'health' as type, sprint_name as title, creator_id as creator_id, created_at as created_at, " +
                     "jsonb_array_length(CASE WHEN jsonb_typeof(participant_ids) = 'array' THEN participant_ids ELSE '[]'::jsonb END) as participant_count " +
                     "FROM health_check_boards " +
                     "UNION ALL " +
                     "SELECT id, 'brainstorm' as type, title, creator_id as creator_id, created_at as created_at, " +
                     "jsonb_array_length(CASE WHEN jsonb_typeof(participant_ids) = 'array' THEN participant_ids ELSE '[]'::jsonb END) as participant_count " +
                     "FROM brainstorming_boards " +
                     "UNION ALL " +
                     "SELECT id, 'sprint_planning' as type, title, created_by as creator_id, created_at as created_at, " +
                     "jsonb_array_length(CASE WHEN jsonb_typeof(members) = 'array' THEN members ELSE '[]'::jsonb END) as participant_count " +
                     "FROM sprint_plannings " +
                     "ORDER BY created_at DESC " +
                     "LIMIT 100";

        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> UnifiedSessionDto.builder()
                    .id(rs.getString("id"))
                    .type(rs.getString("type"))
                    .title(rs.getString("title"))
                    .creatorId(rs.getString("creator_id"))
                    .createdAt(rs.getString("created_at"))
                    .participantCount(rs.getInt("participant_count"))
                    .build());
        } catch (Exception e) {
            // Se as tabelas não existirem, retorna lista vazia
            return new ArrayList<>();
        }
    }

    @Transactional
    public void deleteSession(String id, String type) {
        if (id == null || type == null) return;
        String tableName;
        switch (type.toLowerCase()) {
            case "poker":
                tableName = "poker_rooms";
                break;
            case "retro":
                tableName = "retro_boards";
                break;
            case "health":
                tableName = "health_check_boards";
                break;
            case "brainstorm":
                tableName = "brainstorming_boards";
                break;
            case "sprint_planning":
                tableName = "sprint_plannings";
                break;
            default:
                throw new IllegalArgumentException("Tipo de sessão inválido: " + type);
        }
        jdbcTemplate.update("DELETE FROM " + tableName + " WHERE id = ?", id);
    }
}
