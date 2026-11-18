package com.agilespace.backend.scheduler;

import com.agilespace.backend.domain.PokerRoom;
import com.agilespace.backend.domain.ShowcaseSession;
import com.agilespace.backend.repository.PokerRoomRepository;
import com.agilespace.backend.repository.PokerRoundRepository;
import com.agilespace.backend.repository.PokerVoteRepository;
import com.agilespace.backend.repository.ShowcaseSessionRepository;
import com.agilespace.backend.service.SessionStatusResolver;
import com.agilespace.backend.service.ShowcaseSessionService;
import com.agilespace.backend.websocket.PokerWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Encerra sozinha a sessão ao vivo que ficou aberta de um dia para o outro: Scrum Poker (sala síncrona iniciada) e
 * Review (ativa). Regra: sem nenhuma atividade desde o começo do dia de hoje (no fuso configurado), a sessão é de ontem
 * ou mais antiga e vira encerrada. Quem está trabalhando na virada do dia (voto ou escrita depois da meia-noite) não é
 * cortado, e o facilitador ainda pode reabrir pela própria tela.
 *
 * <p>No Poker o encerramento é gravado com o instante da última atividade (não "agora"), para a duração do relatório
 * não ganhar as horas em que a sala ficou esquecida. Poker assíncrono tem prazo próprio e fica de fora.
 */
@Component
@Slf4j
public class StaleSessionCloser {

    static final int BATCH = 500;
    private static final DateTimeFormatter ISO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final PokerRoomRepository roomRepository;
    private final PokerVoteRepository voteRepository;
    private final PokerRoundRepository roundRepository;
    private final PokerWebSocketHandler pokerSocket;
    private final ShowcaseSessionRepository showcaseRepository;
    private final ShowcaseSessionService showcaseService;
    private final TransactionTemplate tx;

    @Value("${app.sessions.auto-close.enabled:true}")
    private boolean enabled;

    @Value("${app.sessions.auto-close.zone:America/Sao_Paulo}")
    private String zone;

    public StaleSessionCloser(PokerRoomRepository roomRepository, PokerVoteRepository voteRepository,
                              PokerRoundRepository roundRepository, PokerWebSocketHandler pokerSocket,
                              ShowcaseSessionRepository showcaseRepository, ShowcaseSessionService showcaseService,
                              TransactionTemplate tx) {
        this.roomRepository = roomRepository;
        this.voteRepository = voteRepository;
        this.roundRepository = roundRepository;
        this.pokerSocket = pokerSocket;
        this.showcaseRepository = showcaseRepository;
        this.showcaseService = showcaseService;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${app.sessions.auto-close.interval-ms:600000}",
            initialDelayString = "${app.sessions.auto-close.initial-delay-ms:120000}")
    public void run() {
        if (!enabled) return;
        try {
            closeStale(Instant.now());
        } catch (RuntimeException e) {
            log.warn("[session-autoclose] rodada falhou: {}", e.toString());
        }
    }

    /** Início do dia de {@code now} no fuso configurado. */
    Instant startOfDay(Instant now) {
        ZoneId z = ZoneId.of(zone);
        return now.atZone(z).toLocalDate().atStartOfDay(z).toInstant();
    }

    /** Devolve quantas sessões foram encerradas (Poker + Review). */
    public int closeStale(Instant now) {
        Instant cutoff = startOfDay(now);
        return closePoker(cutoff) + closeReviews(cutoff);
    }

    private int closePoker(Instant cutoff) {
        int closed = 0;
        for (PokerRoom candidate : roomRepository.findStartedAndOpen(PageRequest.of(0, BATCH))) {
            try {
                Instant started = SessionStatusResolver.parseTime(candidate.getSessionStartedAt());
                Instant activity = SessionStatusResolver.latest(started,
                        SessionStatusResolver.parseTime(voteRepository.latestTimestamp(candidate.getId())),
                        SessionStatusResolver.parseTime(roundRepository.latestTimestamp(candidate.getId())));
                if (activity == null || !activity.isBefore(cutoff)) continue;
                PokerRoom ended = tx.execute(status -> endIfStillOpen(candidate.getId(), activity));
                if (ended != null) {
                    pokerSocket.broadcastEvent(ended.getId(), "ROOM_UPDATED", ended);
                    closed++;
                }
            } catch (RuntimeException e) {
                log.warn("[session-autoclose] poker {}: {}", candidate.getId(), e.toString());
            }
        }
        if (closed > 0) log.info("[session-autoclose] {} sala(s) de Poker encerrada(s) na virada do dia", closed);
        return closed;
    }

    private PokerRoom endIfStillOpen(String roomId, Instant activity) {
        Optional<PokerRoom> locked = roomRepository.findByIdForUpdate(roomId);
        if (locked.isEmpty()) return null;
        PokerRoom room = locked.get();
        if (room.getSessionEndedAt() != null && !room.getSessionEndedAt().isBlank()) return null;
        room.setSessionEndedAt(ISO_MILLIS.format(activity));
        return roomRepository.saveAndFlush(room);
    }

    private int closeReviews(Instant cutoff) {
        int closed = 0;
        LocalDateTime before = LocalDateTime.ofInstant(cutoff, ZoneId.systemDefault());
        List<ShowcaseSession> candidates = showcaseRepository.findActiveIdleBefore(before, PageRequest.of(0, BATCH));
        for (ShowcaseSession s : candidates) {
            try {
                if (showcaseService.closeAbandoned(s.getId(), before).isPresent()) closed++;
            } catch (RuntimeException e) {
                log.warn("[session-autoclose] review {}: {}", s.getId(), e.toString());
            }
        }
        if (closed > 0) log.info("[session-autoclose] {} Review(s) encerrada(s) na virada do dia", closed);
        return closed;
    }
}
