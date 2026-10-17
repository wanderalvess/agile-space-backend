package com.agilespace.backend.service;

import com.agilespace.backend.domain.*;
import com.agilespace.backend.dto.LiveSessionDto;
import com.agilespace.backend.dto.RitualSessionDto;
import com.agilespace.backend.repository.*;
import com.agilespace.backend.service.SessionStatusResolver.SessionStatus;
import com.agilespace.backend.service.SessionStatusResolver.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Sessões das cerimônias de uma ou mais squads: "ao vivo" ({@link #live}) e histórico por ritual ({@link #history}).
 * O status vem sempre do {@link SessionStatusResolver}. Cada ferramenta usa um número fixo de consultas (sessões +
 * contagens em lote), qualquer que seja a quantidade de squads, e cada lista é limitada.
 * Quem chama já conferiu o acesso às squads.
 */
@Service
@RequiredArgsConstructor
public class RitualSessionService {

    static final int LIVE_LIMIT_PER_TOOL = 50;
    static final int MAX_HISTORY = 50;
    /** Janela de busca de candidatas a "ao vivo": o resolver ainda aplica a regra de 12 h / 3 h por cima. */
    static final Duration LIVE_LOOKBACK = Duration.ofDays(30);
    static final Duration BOARD_LOOKBACK = Duration.ofHours(24);

    private static final DateTimeFormatter ISO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final SessionStatusResolver resolver;
    private final SquadAudienceService audienceService;
    private final PokerRoomRepository pokerRoomRepository;
    private final PokerParticipantRepository pokerParticipantRepository;
    private final PokerRoundRepository pokerRoundRepository;
    private final RetroBoardRepository retroBoardRepository;
    private final RetroCardRepository retroCardRepository;
    private final RetroParticipantRepository retroParticipantRepository;
    private final ShowcaseSessionRepository showcaseSessionRepository;
    private final ShowcaseTaskRepository showcaseTaskRepository;
    private final ShowcaseMemberRepository showcaseMemberRepository;
    private final HealthCheckBoardRepository healthCheckBoardRepository;
    private final HealthCheckParticipantRepository healthCheckParticipantRepository;
    private final HealthCheckVoteRepository healthCheckVoteRepository;
    private final BrainstormingBoardRepository brainstormingBoardRepository;
    private final BrainstormingIdeaRepository brainstormingIdeaRepository;
    private final BrainstormingParticipantRepository brainstormingParticipantRepository;

    // ---------------------------------------------------------------- ao vivo

    /**
     * Sessões das squads com status diferente de FINISHED, inclusive a sala recém-criada que ainda aguarda o início
     * (PLANNING, até 24 h sem sinal). Ordem: LIVE, ASYNC_OPEN, PLANNING; dentro, a mais recente.
     */
    @Transactional(readOnly = true)
    public List<LiveSessionDto> live(Collection<String> squadIds, Instant now) {
        Map<String, String> canon = canonical(squadIds);
        if (canon.isEmpty()) return List.of();
        List<LiveSessionDto> out = new ArrayList<>();
        for (Tool tool : Tool.values()) {
            for (Row r : load(tool, canon, null, LIVE_LIMIT_PER_TOOL, now, true)) {
                if (r.status == SessionStatus.FINISHED || resolver.planningExpired(r.status, r.updatedAt, now)) continue;
                out.add(r.toLive());
            }
        }
        out.sort(Comparator.comparingInt((LiveSessionDto d) -> SessionStatus.valueOf(d.status()).ordinal())
                .thenComparing(LiveSessionDto::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return withSquadNames(out);
    }

    // --------------------------------------------------------------- histórico

    /** Últimas sessões do ritual na squad (qualquer status), mais recentes primeiro. */
    @Transactional(readOnly = true)
    public List<RitualSessionDto> history(Tool tool, String squadId, Integer limit, Instant now) {
        Map<String, String> canon = canonical(List.of(squadId));
        int size = limit == null || limit <= 0 ? 10 : Math.min(limit, MAX_HISTORY);
        List<Row> rows = load(tool, canon, squadId, size, now, false);
        rows.sort(Comparator.comparing((Row r) -> r.date, Comparator.nullsLast(Comparator.reverseOrder())));
        return rows.stream().limit(size).map(Row::toHistory).toList();
    }

    // ------------------------------------------------------------------ carga

    private record Counts(int participants, Instant lastActivity) { }

    /** Linha interna comum às cinco ferramentas. */
    private static final class Row {
        Tool tool; String id; String title; String squadId; int participants; SessionStatus status;
        Instant startedAt; Instant updatedAt; Instant date; String summary; Long durationMinutes;

        LiveSessionDto toLive() {
            return new LiveSessionDto(tool.name(), id, title, squadId, null, participants, status.name(), tool.link(id), startedAt, updatedAt);
        }

        RitualSessionDto toHistory() {
            return new RitualSessionDto(tool.name(), id, title, squadId, date, participants, status.name(), summary, durationMinutes, tool.link(id));
        }
    }

    private List<Row> load(Tool tool, Map<String, String> canon, String historySquad, int limit, Instant now, boolean liveMode) {
        Pageable page = PageRequest.of(0, limit);
        return switch (tool) {
            case POKER -> poker(canon, historySquad, page, now, liveMode);
            case RETRO -> retro(canon, historySquad, page, now, liveMode);
            case SHOWCASE -> showcase(canon, historySquad, page, now, liveMode);
            case HEALTH_CHECK -> health(canon, historySquad, page, now, liveMode);
            case BRAINSTORMING -> brainstorming(canon, historySquad, page, now, liveMode);
        };
    }

    private List<Row> poker(Map<String, String> canon, String historySquad, Pageable page, Instant now, boolean liveMode) {
        List<PokerRoom> rooms = liveMode
                ? pokerRoomRepository.findOpenByTeams(canon.keySet(), ISO_MILLIS.format(now.minus(LIVE_LOOKBACK)), page)
                : pokerRoomRepository.findByTeamIgnoreCaseOrderByCreatedAtDesc(historySquad, page).getContent();
        if (rooms.isEmpty()) return new ArrayList<>();
        List<String> ids = rooms.stream().map(PokerRoom::getId).toList();
        Map<String, Counts> counts = new HashMap<>();
        for (Object[] r : pokerParticipantRepository.summarizeByRoom(ids)) {
            counts.put((String) r[0], new Counts(((Number) r[1]).intValue(), SessionStatusResolver.parseTime((String) r[2])));
        }
        Map<String, List<PokerRound>> rounds = new HashMap<>();
        if (!liveMode) {
            for (PokerRound pr : pokerRoundRepository.findByRoomIdIn(ids)) {
                rounds.computeIfAbsent(pr.getRoomId(), k -> new ArrayList<>()).add(pr);
            }
        }
        List<Row> out = new ArrayList<>();
        for (PokerRoom room : rooms) {
            Counts c = counts.getOrDefault(room.getId(), new Counts(0, null));
            Instant started = SessionStatusResolver.parseTime(room.getSessionStartedAt());
            Instant ended = SessionStatusResolver.parseTime(room.getSessionEndedAt());
            Instant created = SessionStatusResolver.parseTime(room.getCreatedAt());
            Row r = new Row();
            r.tool = Tool.POKER; r.id = room.getId();
            r.title = firstText(room.getTitle(), room.getCurrentTopic(), "Poker");
            r.squadId = canonOf(canon, room.getTeam());
            r.participants = c.participants();
            r.startedAt = started;
            r.updatedAt = SessionStatusResolver.latest(c.lastActivity(), started, created);
            r.status = resolver.poker(room.getMode(), started, ended, c.lastActivity(), now);
            r.date = started != null ? started : created;
            r.durationMinutes = started != null && ended != null && ended.isAfter(started) ? Duration.between(started, ended).toMinutes() : null;
            if (!liveMode) r.summary = pokerSummary(room, rounds.getOrDefault(room.getId(), List.of()));
            out.add(r);
        }
        return out;
    }

    /** "60h em 6 tarefas" (baralho de horas), "21 pontos em 4 tarefas" (pontos) ou só "3 tarefas". */
    static String pokerSummary(PokerRoom room, List<PokerRound> rounds) {
        int tasks = 0;
        double total = 0;
        for (PokerRound round : rounds) {
            if (Boolean.TRUE.equals(round.getSkipped()) || Boolean.TRUE.equals(round.getCancelled())) continue;
            tasks++;
            total += number(round.getDevPoints()) + number(round.getQaPoints());
        }
        if (tasks == 0) return null;
        String taskText = tasks + (tasks == 1 ? " tarefa" : " tarefas");
        if (total <= 0) return taskText;
        String num = total == Math.rint(total) ? String.valueOf((long) total) : String.format(Locale.ROOT, "%.1f", total);
        String deck = room.getDeckType() == null ? "" : room.getDeckType().toLowerCase(Locale.ROOT);
        if ("hours".equals(deck)) return num + "h em " + taskText;
        if ("tshirt".equals(deck)) return taskText;
        return num + (total == 1 ? " ponto" : " pontos") + " em " + taskText;
    }

    private static double number(String value) {
        if (value == null) return 0;
        try {
            double d = Double.parseDouble(value.trim().replace(',', '.'));
            return d > 0 && d < 100_000 ? d : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private List<Row> retro(Map<String, String> canon, String historySquad, Pageable page, Instant now, boolean liveMode) {
        List<RetroBoard> boards = liveMode
                ? retroBoardRepository.findActiveBySquads(canon.keySet(), ServerTime.toLocal(now.minus(SessionStatusResolver.BOARD_ACTIVITY_WINDOW)), page)
                : retroBoardRepository.findBySquadIdIgnoreCaseOrderByCreatedAtDesc(historySquad, page);
        if (boards.isEmpty()) return new ArrayList<>();
        List<String> ids = boards.stream().map(RetroBoard::getId).toList();
        Map<String, Integer> participants = countMap(retroParticipantRepository.countByBoard(ids));
        Map<String, int[]> cards = new HashMap<>(); // boardId -> {cards (fora da coluna de ação), ações abertas}
        if (!liveMode) {
            Map<String, RetroBoard> byId = new HashMap<>();
            boards.forEach(b -> byId.put(b.getId(), b));
            for (Object[] row : retroCardRepository.countByBoardColumnAndDone(ids)) {
                String boardId = (String) row[0];
                int n = ((Number) row[3]).intValue();
                boolean action = isActionColumn(byId.get(boardId), (String) row[1]);
                int[] acc = cards.computeIfAbsent(boardId, k -> new int[2]);
                if (action) {
                    if (!Boolean.TRUE.equals(row[2])) acc[1] += n;
                } else {
                    acc[0] += n;
                }
            }
        }
        List<Row> out = new ArrayList<>();
        for (RetroBoard b : boards) {
            Instant created = ServerTime.toInstant(b.getCreatedAt());
            Instant updated = ServerTime.toInstant(b.getUpdatedAt());
            Row r = new Row();
            r.tool = Tool.RETRO; r.id = b.getId();
            r.title = firstText(b.getTitle(), "Retrospectiva");
            r.squadId = canonOf(canon, b.getSquadId());
            r.participants = participants.getOrDefault(b.getId(), 0);
            r.startedAt = created;
            r.updatedAt = SessionStatusResolver.latest(updated, created);
            r.status = resolver.retro(r.updatedAt, now);
            r.date = created;
            int[] c = cards.get(b.getId());
            if (c != null) r.summary = retroSummary(c[0], c[1]);
            out.add(r);
        }
        return out;
    }

    /** "18 cards · 3 ações abertas". */
    static String retroSummary(int cards, int openActions) {
        List<String> parts = new ArrayList<>();
        if (cards > 0) parts.add(cards + (cards == 1 ? " card" : " cards"));
        if (openActions > 0) parts.add(openActions + (openActions == 1 ? " ação aberta" : " ações abertas"));
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    private static boolean isActionColumn(RetroBoard board, String columnKey) {
        return board != null && board.getColumns() != null && board.getColumns().stream()
                .anyMatch(c -> columnKey != null && columnKey.equals(c.getId()) && "action".equals(c.getTheme()));
    }

    private List<Row> showcase(Map<String, String> canon, String historySquad, Pageable page, Instant now, boolean liveMode) {
        List<ShowcaseSession> sessions = liveMode
                ? showcaseSessionRepository.findOpenBySquads(canon.keySet(), ServerTime.toLocal(now.minus(LIVE_LOOKBACK)), page)
                : showcaseSessionRepository.findLatestSessionsBySquad(historySquad, page);
        if (sessions.isEmpty()) return new ArrayList<>();
        List<String> ids = sessions.stream().map(ShowcaseSession::getId).toList();
        Map<String, Integer> members = countMap(showcaseMemberRepository.countBySession(ids));
        Map<String, Map<String, Integer>> decisions = new HashMap<>();
        if (!liveMode) {
            for (Object[] row : showcaseTaskRepository.countDecisionsBySession(ids)) {
                String decision = row[1] == null || ((String) row[1]).isBlank() ? "open" : ((String) row[1]).toLowerCase(Locale.ROOT);
                decisions.computeIfAbsent((String) row[0], k -> new HashMap<>()).merge(decision, ((Number) row[2]).intValue(), Integer::sum);
            }
        }
        List<Row> out = new ArrayList<>();
        for (ShowcaseSession s : sessions) {
            Instant created = ServerTime.toInstant(s.getCreatedAt());
            Instant updated = ServerTime.toInstant(s.getUpdatedAt());
            Row r = new Row();
            r.tool = Tool.SHOWCASE; r.id = s.getId();
            r.title = firstText(s.getName(), s.getSprintName(), "Review");
            r.squadId = canonOf(canon, s.getSquadName());
            r.participants = members.getOrDefault(s.getId(), 0);
            r.startedAt = created;
            r.updatedAt = SessionStatusResolver.latest(updated, created);
            r.status = resolver.showcase(s.getStatus(), r.updatedAt, now);
            r.date = created;
            Map<String, Integer> d = decisions.get(s.getId());
            if (d != null) r.summary = showcaseSummary(d);
            out.add(r);
        }
        return out;
    }

    /** "7 aprovadas, 1 ajuste"; sem nenhuma decisão, "N itens". */
    static String showcaseSummary(Map<String, Integer> decisions) {
        int approved = decisions.getOrDefault("approved", 0);
        int adjust = decisions.getOrDefault("needs_adjustment", 0);
        int rejected = decisions.getOrDefault("rejected", 0);
        int open = decisions.getOrDefault("open", 0);
        List<String> parts = new ArrayList<>();
        if (approved > 0) parts.add(approved + (approved == 1 ? " aprovada" : " aprovadas"));
        if (adjust > 0) parts.add(adjust + (adjust == 1 ? " ajuste" : " ajustes"));
        if (rejected > 0) parts.add(rejected + (rejected == 1 ? " rejeitada" : " rejeitadas"));
        if (!parts.isEmpty()) return String.join(", ", parts);
        int total = open + approved + adjust + rejected;
        return total == 0 ? null : total + (total == 1 ? " item" : " itens");
    }

    private List<Row> health(Map<String, String> canon, String historySquad, Pageable page, Instant now, boolean liveMode) {
        List<HealthCheckBoard> boards = liveMode
                ? healthCheckBoardRepository.findCollectingByTeams(canon.keySet(), ISO_MILLIS.format(now.minus(LIVE_LOOKBACK)), page)
                : healthCheckBoardRepository.findByTeamIgnoreCaseOrderByCreatedAtDesc(historySquad, page);
        if (boards.isEmpty()) return new ArrayList<>();
        List<String> ids = boards.stream().map(HealthCheckBoard::getId).toList();
        Map<String, Integer> respondents = countMap(healthCheckVoteRepository.countRespondentsByBoard(ids));
        Map<String, Integer> participants = countMap(healthCheckParticipantRepository.countByBoard(ids));
        List<Row> out = new ArrayList<>();
        for (HealthCheckBoard b : boards) {
            Instant created = SessionStatusResolver.parseTime(b.getCreatedAt());
            int answered = respondents.getOrDefault(b.getId(), 0);
            Row r = new Row();
            r.tool = Tool.HEALTH_CHECK; r.id = b.getId();
            r.title = firstText(b.getSprintName(), "Health Check");
            r.squadId = canonOf(canon, b.getTeam());
            r.participants = Math.max(answered, participants.getOrDefault(b.getId(), 0));
            r.startedAt = created;
            r.updatedAt = created;
            r.status = resolver.healthCheck(b.getStatus());
            r.date = created;
            r.summary = answered == 0 ? null : answered + (answered == 1 ? " resposta" : " respostas");
            out.add(r);
        }
        return out;
    }

    private List<Row> brainstorming(Map<String, String> canon, String historySquad, Pageable page, Instant now, boolean liveMode) {
        List<BrainstormingBoard> boards = liveMode
                ? brainstormingBoardRepository.findRecentByTeams(canon.keySet(), ISO_MILLIS.format(now.minus(BOARD_LOOKBACK)), page)
                : brainstormingBoardRepository.findByTeamIgnoreCaseOrderByCreatedAtDesc(historySquad, page);
        if (boards.isEmpty()) return new ArrayList<>();
        List<String> ids = boards.stream().map(BrainstormingBoard::getId).toList();
        Map<String, Counts> ideas = new HashMap<>();
        for (Object[] r : brainstormingIdeaRepository.summarizeByBoard(ids)) {
            ideas.put((String) r[0], new Counts(((Number) r[1]).intValue(), SessionStatusResolver.parseTime((String) r[2])));
        }
        Map<String, Counts> people = new HashMap<>();
        for (Object[] r : brainstormingParticipantRepository.summarizeByBoard(ids)) {
            people.put((String) r[0], new Counts(((Number) r[1]).intValue(), SessionStatusResolver.parseTime((String) r[2])));
        }
        List<Row> out = new ArrayList<>();
        for (BrainstormingBoard b : boards) {
            Instant created = SessionStatusResolver.parseTime(b.getCreatedAt());
            Counts i = ideas.getOrDefault(b.getId(), new Counts(0, null));
            Counts p = people.getOrDefault(b.getId(), new Counts(0, null));
            Row r = new Row();
            r.tool = Tool.BRAINSTORMING; r.id = b.getId();
            r.title = firstText(b.getTitle(), "Brainstorming");
            r.squadId = canonOf(canon, b.getTeam());
            r.participants = p.participants();
            r.startedAt = created;
            r.updatedAt = SessionStatusResolver.latest(created, i.lastActivity(), p.lastActivity());
            r.status = resolver.brainstorming(r.updatedAt, now, b.isFinished());
            r.date = created;
            r.summary = i.participants() == 0 ? null : i.participants() + (i.participants() == 1 ? " ideia" : " ideias");
            out.add(r);
        }
        return out;
    }

    // --------------------------------------------------------------- helpers

    /** MAIÚSCULAS → id como o usuário o conhece (para devolver squadId no formato certo). */
    private static Map<String, String> canonical(Collection<String> squadIds) {
        Map<String, String> out = new LinkedHashMap<>();
        if (squadIds == null) return out;
        for (String id : squadIds) {
            if (id != null && !id.isBlank()) out.putIfAbsent(id.trim().toUpperCase(Locale.ROOT), id.trim());
        }
        return out;
    }

    private static String canonOf(Map<String, String> canon, String raw) {
        if (raw == null) return null;
        String c = canon.get(raw.trim().toUpperCase(Locale.ROOT));
        return c != null ? c : raw.trim();
    }

    private static Map<String, Integer> countMap(List<Object[]> rows) {
        Map<String, Integer> out = new HashMap<>();
        for (Object[] r : rows) out.put((String) r[0], ((Number) r[1]).intValue());
        return out;
    }

    private static String firstText(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }

    /** Preenche squadName nas sessões ao vivo (uma consulta para todas as squads). */
    List<LiveSessionDto> withSquadNames(List<LiveSessionDto> sessions) {
        if (sessions.isEmpty()) return sessions;
        Set<String> ids = new LinkedHashSet<>();
        sessions.forEach(s -> ids.add(s.squadId()));
        Map<String, String> names = audienceService.squadNames(ids);
        return sessions.stream().map(s -> new LiveSessionDto(s.tool(), s.id(), s.title(), s.squadId(), names.get(s.squadId()),
                s.participantsCount(), s.status(), s.link(), s.startedAt(), s.updatedAt())).toList();
    }
}
