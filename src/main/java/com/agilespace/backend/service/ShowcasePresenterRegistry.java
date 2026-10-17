package com.agilespace.backend.service;

import com.agilespace.backend.dto.ShowcasePresenterDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Apresentador de cada Review, em memória e por processo (docs/ceremony-review-api.md): é um estado efêmero, sem histórico.
 * Uma publicação sem renovação por {@code app.showcase.presenter-ttl-minutes} (padrão 240) deixa de valer, para um apresentador
 * que fechou a aba sem encerrar não manter a sala "seguindo" para sempre.
 */
@Component
public class ShowcasePresenterRegistry {

    private final Map<String, ShowcasePresenterDto> presenters = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public ShowcasePresenterRegistry(@Value("${app.showcase.presenter-ttl-minutes:240}") long ttlMinutes) {
        this(ttlMinutes, Clock.systemDefaultZone());
    }

    ShowcasePresenterRegistry(long ttlMinutes, Clock clock) {
        this.ttl = Duration.ofMinutes(ttlMinutes > 0 ? ttlMinutes : 240);
        this.clock = clock;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** Quem apresenta agora, ou vazio (ninguém ou publicação vencida, que é descartada). */
    public Optional<ShowcasePresenterDto> current(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        ShowcasePresenterDto p = presenters.get(sessionId);
        if (p == null) {
            return Optional.empty();
        }
        if (expired(p)) {
            presenters.remove(sessionId, p);
            return Optional.empty();
        }
        return Optional.of(p);
    }

    public ShowcasePresenterDto publish(String sessionId, String userId, String displayName, String cardId, String step) {
        purgeExpired();
        ShowcasePresenterDto p = new ShowcasePresenterDto(userId, displayName, cardId, step, now());
        presenters.put(sessionId, p);
        return p;
    }

    /** Encerra a apresentação. {@code true} se havia (válida) alguém apresentando. */
    public boolean clear(String sessionId) {
        if (sessionId == null) {
            return false;
        }
        ShowcasePresenterDto removed = presenters.remove(sessionId);
        return removed != null && !expired(removed);
    }

    public int size() {
        return presenters.size();
    }

    private boolean expired(ShowcasePresenterDto p) {
        return p.presentingAt() == null || p.presentingAt().plus(ttl).isBefore(now());
    }

    private void purgeExpired() {
        presenters.entrySet().removeIf(e -> expired(e.getValue()));
    }
}
