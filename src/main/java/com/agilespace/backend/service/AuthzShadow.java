package com.agilespace.backend.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Chave e modo sombra da autorização por squad (Etapa 2 do vínculo; {@link AuthzMode}).
 *
 * <p>Cada ponto de autorização migrado entrega a decisão LEGADA e a NOVA como funções e pergunta aqui o que vale:
 * <ul>
 *   <li>LEGACY: só o legado é executado (a nova regra nem é calculada: custo zero e comportamento idêntico);</li>
 *   <li>SHADOW: o legado decide; a nova é calculada em paralelo e cada divergência vira uma linha
 *       {@code authz_divergence user=<id> check=<ponto> squad=<chave> legacy=<bool> new=<bool> reason=<rótulo>}
 *       (WARN quando a nova regra NEGA o que o legado permite = perda de acesso; INFO no sentido contrário) e soma no
 *       contador {@code authz.divergence{check,direction}}. Erro no cálculo sombra vira {@code authz_shadow_error} e
 *       NUNCA muda a decisão;</li>
 *   <li>NEW: a nova decide (erro propaga: falhar aberto num controle de acesso seria pior que um 500 visível).</li>
 * </ul>
 *
 * <p>Sem e-mail, nome ou qualquer PII no log: só o id da conta, a chave da squad e rótulos categóricos. Valores vindos
 * da requisição (chave da squad) são saneados antes de ir ao log. O contador conta toda divergência; o log de uma mesma
 * combinação (conta, ponto, squad, sentido) repete no máximo a cada {@code app.membership.authz-log-dedupe-seconds}
 * (padrão 60; 0 loga todas), porque um ponto como {@code matchesSquad} roda a cada requisição.
 */
@Component
@Slf4j
public class AuthzShadow {

    public static final String METRIC = "authz.divergence";
    static final int MAX_DEDUPE_ENTRIES = 10_000;

    public static final String DIRECTION_LOSS = "LOSS";
    public static final String DIRECTION_GAIN = "GAIN";

    private final AuthzMode mode;
    private final ObjectProvider<MeterRegistry> meterRegistry;
    private final long dedupeMillis;
    private final ConcurrentMap<String, Long> lastLogged = new ConcurrentHashMap<>();
    /** Relógio injetável para testar a janela de repetição sem dormir. */
    volatile LongSupplier clock = System::currentTimeMillis;

    public AuthzShadow(@Value("${app.membership.authz:legacy}") String modeRaw,
                       @Value("${app.membership.authz-log-dedupe-seconds:60}") long dedupeSeconds,
                       ObjectProvider<MeterRegistry> meterRegistry) {
        this.mode = AuthzMode.parse(modeRaw);
        this.meterRegistry = meterRegistry;
        this.dedupeMillis = Math.max(0L, dedupeSeconds) * 1000L;
        if (AuthzMode.isUnknown(modeRaw)) {
            log.warn("app.membership.authz inválido ({}): usando legacy (comportamento anterior)", sanitize(modeRaw));
        }
        log.info("Vínculo: app.membership.authz={}", this.mode.name().toLowerCase(Locale.ROOT));
    }

    public AuthzMode mode() {
        return mode;
    }

    public boolean isLegacy() {
        return mode == AuthzMode.LEGACY;
    }

    /**
     * Decide um ponto de autorização booleano. O motivo categórico só é pedido quando há divergência (recebe o que o
     * legado decidiu).
     */
    public boolean decide(String check, String userId, String squad, BooleanSupplier legacy, BooleanSupplier neu,
                          Function<Boolean, String> reason) {
        switch (mode) {
            case LEGACY:
                return legacy.getAsBoolean();
            case NEW:
                return neu.getAsBoolean();
            default:
                boolean old = legacy.getAsBoolean();
                try {
                    boolean fresh = neu.getAsBoolean();
                    if (fresh != old) {
                        record(check, userId, squad, old, fresh, safeReason(reason, old));
                    }
                } catch (RuntimeException e) {
                    shadowError(check, userId, squad, e);
                }
                return old;
        }
    }

    /**
     * Versão para valores que não são boolean (conjunto de squads, squad ativa, lista de usuários). {@code compare}
     * recebe (legado, novo) e chama {@link #record} para cada diferença; só roda em SHADOW.
     */
    public <T> T choose(String check, String userId, Supplier<T> legacy, Supplier<T> neu, BiConsumer<T, T> compare) {
        switch (mode) {
            case LEGACY:
                return legacy.get();
            case NEW:
                return neu.get();
            default:
                T old = legacy.get();
                try {
                    compare.accept(old, neu.get());
                } catch (RuntimeException e) {
                    shadowError(check, userId, null, e);
                }
                return old;
        }
    }

    /** Registra uma divergência: log estruturado (com janela de repetição) e contador (sempre). */
    public void record(String check, String userId, String squad, boolean legacyAllowed, boolean newAllowed, String reason) {
        String direction = legacyAllowed && !newAllowed ? DIRECTION_LOSS : DIRECTION_GAIN;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            registry.counter(METRIC, "check", sanitize(check), "direction", direction).increment();
        }
        if (!shouldLog(userId, check, squad, direction)) {
            return;
        }
        String line = "authz_divergence user={} check={} squad={} legacy={} new={} reason={}";
        if (DIRECTION_LOSS.equals(direction)) {
            log.warn(line, sanitize(userId), sanitize(check), sanitize(squad), legacyAllowed, newAllowed, sanitize(reason));
        } else {
            log.info(line, sanitize(userId), sanitize(check), sanitize(squad), legacyAllowed, newAllowed, sanitize(reason));
        }
    }

    private void shadowError(String check, String userId, String squad, RuntimeException e) {
        log.warn("authz_shadow_error user={} check={} squad={} erro={}", sanitize(userId), sanitize(check), sanitize(squad),
                e.getClass().getSimpleName());
    }

    private static String safeReason(Function<Boolean, String> reason, boolean legacyAllowed) {
        try {
            String r = reason == null ? null : reason.apply(legacyAllowed);
            return r == null || r.isBlank() ? "UNKNOWN" : r;
        } catch (RuntimeException e) {
            return "UNKNOWN";
        }
    }

    private boolean shouldLog(String userId, String check, String squad, String direction) {
        if (dedupeMillis == 0) return true;
        long now = clock.getAsLong();
        if (lastLogged.size() >= MAX_DEDUPE_ENTRIES) {
            lastLogged.entrySet().removeIf(e -> now - e.getValue() >= dedupeMillis);
            if (lastLogged.size() >= MAX_DEDUPE_ENTRIES) lastLogged.clear();
        }
        String key = sanitize(userId) + '|' + sanitize(check) + '|' + sanitize(squad) + '|' + direction;
        Long previous = lastLogged.get(key);
        if (previous != null && now - previous < dedupeMillis) return false;
        lastLogged.put(key, now);
        return true;
    }

    /** Deixa só [A-Za-z0-9._:-] (sem '@', sem espaço, sem quebra de linha) e limita a 64: nada de PII nem injeção de log. */
    static String sanitize(String value) {
        if (value == null || value.isBlank()) return "-";
        String v = value.trim();
        if (v.length() > 64) v = v.substring(0, 64);
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == ':' || c == '-';
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }
}
