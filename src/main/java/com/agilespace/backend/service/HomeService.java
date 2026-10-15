package com.agilespace.backend.service;

import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.domain.SquadMetricsRollup;
import com.agilespace.backend.dto.*;
import com.agilespace.backend.repository.SquadMetricsRollupRepository;
import com.agilespace.backend.repository.SquadRepository;
import com.agilespace.backend.service.SessionStatusResolver.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;

/**
 * Agregado da home (docs/home-api.md §7): avisos, não lidas, squads, sessões ao vivo e fatos por ritual.
 * Só squads acessíveis ao usuário. O número de consultas independe da quantidade de squads (sessões e métricas em
 * lote); os fatos por ritual são da squad atual (uma busca limitada por ferramenta).
 */
@Service
@RequiredArgsConstructor
public class HomeService {

    static final int MAX_SQUADS = 30;
    static final int FACTS_HISTORY = 5;
    static final ZoneId CEREMONY_ZONE = ZoneId.of("America/Sao_Paulo");

    private final SquadAudienceService audienceService;
    private final SquadAccessService squadAccessService;
    private final AnnouncementService announcementService;
    private final NotificationService notificationService;
    private final RitualSessionService ritualSessionService;
    private final SquadRepository squadRepository;
    private final SquadMetricsRollupRepository rollupRepository;

    @Transactional(readOnly = true)
    public HomeDto home(String userId, String role, String requestedSquadId) {
        return home(userId, role, requestedSquadId, Instant.now());
    }

    HomeDto home(String userId, String role, String requestedSquadId, Instant now) {
        SquadAudienceService.UserContext ctx = audienceService.contextFor(userId, role)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida."));

        boolean explicit = requestedSquadId != null && !requestedSquadId.isBlank();
        String current = ctx.currentSquadId();
        if (explicit) {
            if (!ctx.hasSquad(requestedSquadId) && !squadAccessService.matchesSquad(requestedSquadId, userId, role)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a membros desta squad ou administradores.");
            }
            current = requestedSquadId.trim();
        }

        List<AnnouncementDto> announcements = announcementService.active(userId, role);
        UnreadCountDto unread = notificationService.unreadCount(userId);

        // Liderança que escolhe uma das próprias squads continua vendo todas (o filtro da home só muda os fatos dos
        // rituais); squad alcançada por outra via (ex.: tribo) volta ao modo de uma squad só.
        boolean multi = ctx.multiSquad() && (!explicit || ctx.hasSquad(current));
        List<String> scope = new ArrayList<>();
        if (multi) {
            scope.addAll(ctx.squadIds().stream().limit(MAX_SQUADS).toList());
        } else if (current != null) {
            scope.add(current);
        }

        List<LiveSessionDto> live = ritualSessionService.live(scope, now);
        List<SquadSummaryDto> squads = summaries(scope, live, now);
        List<RitualFactsDto> rituals = current == null ? List.of() : rituals(current, live, now);

        return new HomeDto(announcements, unread, multi, squads, live, rituals);
    }

    private List<RitualFactsDto> rituals(String squadId, List<LiveSessionDto> live, Instant now) {
        List<RitualFactsDto> out = new ArrayList<>();
        for (Tool tool : Tool.values()) {
            int open = (int) live.stream().filter(s -> s.tool().equals(tool.name()) && s.squadId().equalsIgnoreCase(squadId)).count();
            List<RitualSessionDto> history = ritualSessionService.history(tool, squadId, FACTS_HISTORY, now);
            RitualSessionDto last = history.isEmpty() ? null : history.get(0);
            List<String> facts = new ArrayList<>();
            if (last != null && last.summary() != null) facts.add("Última: " + last.summary());
            if (open > 0) facts.add(open == 1 ? "1 aberta agora" : open + " abertas agora");
            out.add(new RitualFactsDto(tool.name(), facts, open, last));
        }
        return out;
    }

    private List<SquadSummaryDto> summaries(List<String> squadIds, List<LiveSessionDto> live, Instant now) {
        if (squadIds.isEmpty()) return List.of();
        Map<String, String> names = audienceService.squadNames(squadIds);
        Map<String, Squad> squads = new HashMap<>();
        squadRepository.findAllById(squadIds).forEach(s -> squads.put(s.getId(), s));
        Map<String, SquadMetricsRollup> rollups = new HashMap<>();
        for (SquadMetricsRollup r : rollupRepository.findBySquadIdIn(squadIds)) {
            Squad s = squads.get(r.getSquadId());
            if (s != null && s.getActiveSprintId() != null && s.getActiveSprintId().equals(r.getSprintId())) {
                rollups.put(r.getSquadId(), r);
            }
        }
        List<SquadSummaryDto> out = new ArrayList<>();
        for (String id : squadIds) {
            SquadMetricsRollup r = rollups.get(id);
            int liveCount = (int) live.stream().filter(s -> s.squadId().equalsIgnoreCase(id)).count();
            out.add(new SquadSummaryDto(id, names.getOrDefault(id, id),
                    r == null ? null : r.getSprintName(),
                    r == null ? null : r.getWorkdaysRemaining(),
                    r == null ? null : progress(r),
                    liveCount, nextCeremony(squads.get(id), now)));
        }
        return out;
    }

    /** Concluídas / total da sprint ativa (rollup do último sync); null se a sprint não tem itens. */
    static Integer progress(SquadMetricsRollup r) {
        if (r.getTotalIssues() == null || r.getTotalIssues() <= 0 || r.getDoneIssues() == null) return null;
        return (int) Math.round(100.0 * r.getDoneIssues() / r.getTotalIssues());
    }

    /**
     * Só no modo "manual" (squads.ceremonies: [{title, daysOfWeek:["MON"..], startTime:"09:15"}]): a próxima ocorrência
     * no fuso de Brasília. No modo Google Calendar a agenda só existe no cliente (useNextCeremony) e volta null.
     */
    static SquadSummaryDto.NextCeremony nextCeremony(Squad squad, Instant now) {
        if (squad == null || !"manual".equalsIgnoreCase(squad.getCeremonyMode())) return null;
        JsonNode list = squad.getCeremonies();
        if (list == null || !list.isArray()) return null;
        LocalDate today = now.atZone(CEREMONY_ZONE).toLocalDate();
        SquadSummaryDto.NextCeremony best = null;
        for (JsonNode c : list) {
            LocalTime time;
            try {
                time = LocalTime.parse(c.path("startTime").asText(""));
            } catch (Exception e) {
                continue;
            }
            Set<DayOfWeek> days = new HashSet<>();
            for (JsonNode d : c.path("daysOfWeek")) {
                String code = d.asText("").trim().toUpperCase(Locale.ROOT);
                if (code.length() < 3) continue;
                for (DayOfWeek dow : DayOfWeek.values()) {
                    if (dow.name().startsWith(code.substring(0, 3))) days.add(dow);
                }
            }
            for (int offset = 0; offset <= 7; offset++) {
                LocalDate date = today.plusDays(offset);
                if (!days.contains(date.getDayOfWeek())) continue;
                Instant startsAt = date.atTime(time).atZone(CEREMONY_ZONE).toInstant();
                if (startsAt.isAfter(now)) {
                    if (best == null || startsAt.isBefore(best.startsAt())) {
                        String title = c.path("title").asText("");
                        best = new SquadSummaryDto.NextCeremony(title.isBlank() ? "Cerimônia" : title, startsAt);
                    }
                    break;
                }
            }
        }
        return best;
    }
}
