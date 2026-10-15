package com.agilespace.backend.service;

import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Fonte única do status "ao vivo" das cerimônias (docs/home-api.md §5 e §8). Funções puras: recebem os campos já
 * lidos e o instante atual, para que as regras possam ser testadas em tabela.
 *
 * <ul>
 *   <li>POKER: sessionEndedAt → FINISHED; modo assíncrono → ASYNC_OPEN; sessionStartedAt → LIVE; senão PLANNING.</li>
 *   <li>SHOWCASE: planning (ou outro status desconhecido) → PLANNING; active → LIVE; finished → FINISHED.</li>
 *   <li>HEALTH_CHECK: collecting → ASYNC_OPEN; finished → FINISHED.</li>
 *   <li>RETRO / BRAINSTORMING: não têm estado de encerramento no modelo. LIVE se houve atividade nas últimas
 *       {@link #BOARD_ACTIVITY_WINDOW} (3 h), senão FINISHED.</li>
 *   <li>Sessão abandonada: LIVE sem atividade há mais de {@link #STALE_LIVE} (12 h) vira FINISHED.</li>
 *   <li>Sala aguardando (PLANNING) sem nenhum sinal há mais de {@link #PLANNING_WINDOW} (24 h) não entra em "ao vivo"
 *       ({@link #planningExpired}); o status dela continua PLANNING no histórico.</li>
 * </ul>
 */
@Component
public class SessionStatusResolver {

    public static final Duration STALE_LIVE = Duration.ofHours(12);
    public static final Duration BOARD_ACTIVITY_WINDOW = Duration.ofHours(3);
    /** Quanto tempo uma sala criada e ainda não iniciada (PLANNING) continua aparecendo em "ao vivo agora". */
    public static final Duration PLANNING_WINDOW = Duration.ofHours(24);

    public enum Tool {
        POKER("poker", "/room/"),
        RETRO("retro", "/retro/"),
        SHOWCASE("showcase", "/showcase/"),
        HEALTH_CHECK("health-check", "/health-check/"),
        BRAINSTORMING("brainstorming", "/brainstorming/");

        private final String pathSegment;
        private final String linkPrefix;

        Tool(String pathSegment, String linkPrefix) {
            this.pathSegment = pathSegment;
            this.linkPrefix = linkPrefix;
        }

        public String pathSegment() {
            return pathSegment;
        }

        public String link(String id) {
            return linkPrefix + id;
        }

        /** {@code poker}, {@code retro}, {@code showcase}, {@code health-check}, {@code brainstorming} (ou o nome do enum). */
        public static Optional<Tool> fromPath(String value) {
            if (value == null) return Optional.empty();
            String v = value.trim().toLowerCase(Locale.ROOT);
            return Arrays.stream(values())
                    .filter(t -> t.pathSegment.equals(v) || t.name().equalsIgnoreCase(v.replace('-', '_')))
                    .findFirst();
        }
    }

    public enum SessionStatus { LIVE, ASYNC_OPEN, PLANNING, FINISHED }

    public SessionStatus poker(String mode, Instant startedAt, Instant endedAt, Instant lastActivity, Instant now) {
        if (endedAt != null) return SessionStatus.FINISHED;
        if ("async".equalsIgnoreCase(mode)) return SessionStatus.ASYNC_OPEN;
        if (startedAt != null) return guardStale(latest(startedAt, lastActivity), now);
        return SessionStatus.PLANNING;
    }

    public SessionStatus showcase(String status, Instant lastActivity, Instant now) {
        String s = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "finished" -> SessionStatus.FINISHED;
            case "active" -> guardStale(lastActivity, now);
            default -> SessionStatus.PLANNING;
        };
    }

    public SessionStatus healthCheck(String status) {
        return "finished".equalsIgnoreCase(status == null ? "" : status.trim()) ? SessionStatus.FINISHED : SessionStatus.ASYNC_OPEN;
    }

    public SessionStatus retro(Instant lastActivity, Instant now) {
        return recentBoard(lastActivity, now);
    }

    public SessionStatus brainstorming(Instant lastActivity, Instant now) {
        return recentBoard(lastActivity, now);
    }

    private static SessionStatus recentBoard(Instant lastActivity, Instant now) {
        if (lastActivity == null) return SessionStatus.FINISHED;
        return lastActivity.isAfter(now.minus(BOARD_ACTIVITY_WINDOW)) ? SessionStatus.LIVE : SessionStatus.FINISHED;
    }

    /**
     * Sala aguardando que ninguém abriu nem tocou há mais de {@link #PLANNING_WINDOW}: esquecida, não é "ao vivo".
     * Sem nenhuma data conhecida não dá para afirmar que é velha, então continua listada.
     */
    public boolean planningExpired(SessionStatus status, Instant lastActivity, Instant now) {
        return status == SessionStatus.PLANNING && lastActivity != null && lastActivity.isBefore(now.minus(PLANNING_WINDOW));
    }

    /** LIVE só enquanto houve atividade nas últimas 12 h; sem nenhuma data conhecida, não dá para afirmar que está ao vivo. */
    private static SessionStatus guardStale(Instant lastActivity, Instant now) {
        if (lastActivity == null || lastActivity.isBefore(now.minus(STALE_LIVE))) return SessionStatus.FINISHED;
        return SessionStatus.LIVE;
    }

    public static Instant latest(Instant... values) {
        Instant best = null;
        for (Instant v : values) {
            if (v != null && (best == null || v.isAfter(best))) best = v;
        }
        return best;
    }

    /**
     * Datas gravadas como texto pelas cerimônias (createdAt, sessionStartedAt, lastSeen...): ISO com Z/offset, ISO sem
     * fuso (lido como UTC) ou epoch em milissegundos. Vazio/inválido = null.
     */
    public static Instant parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        try {
            if (v.chars().allMatch(Character::isDigit)) {
                long n = Long.parseLong(v);
                return n > 100_000_000_000L ? Instant.ofEpochMilli(n) : Instant.ofEpochSecond(n);
            }
            return OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException | NumberFormatException e) {
            try {
                return LocalDateTime.parse(v).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException e2) {
                try {
                    return LocalDate.parse(v).atStartOfDay().toInstant(ZoneOffset.UTC);
                } catch (DateTimeParseException e3) {
                    return null;
                }
            }
        }
    }
}
