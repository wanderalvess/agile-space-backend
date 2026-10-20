package com.agilespace.backend.service;

import com.agilespace.backend.domain.Squad;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Descobre, pelo NOME, os campos de data/hora planejadas do Jira ("Data e Hora de Início" e
 * "Data e Hora de Fim"). O id muda entre instâncias, por isso a busca é por nome em /rest/api/2/field,
 * com cache curto por domínio para não chamar /field a cada sync.
 */
@Component
@Slf4j
public class JiraDateFieldResolver {

    public static final String START_NAME = "Data e Hora de Início";
    public static final String END_NAME = "Data e Hora de Fim";
    static final long TTL_MS = 10 * 60 * 1000L;

    public record FieldRef(String id, String name) {}

    /** Candidatos por nome e o nome de todos os campos (para rotular um id configurado). */
    public record Resolution(List<FieldRef> start, List<FieldRef> end, Map<String, String> namesById) {}

    /** Ids efetivos para o sync; nulo = sem campo (cai na precedência antiga). */
    public record IdPair(String start, String end) {
        public static final IdPair NONE = new IdPair(null, null);
    }

    /** Falha ao listar os campos do Jira; a mensagem já é em PT-BR. */
    public static class LookupException extends RuntimeException {
        public LookupException(String message) { super(message); }
    }

    private record Cached(long expiresAt, Resolution resolution) {}

    private final JiraService jiraService;
    private final ObjectMapper objectMapper;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private LongSupplier clock = System::currentTimeMillis;

    public JiraDateFieldResolver(JiraService jiraService, ObjectMapper objectMapper) {
        this.jiraService = jiraService;
        this.objectMapper = objectMapper;
    }

    void setClock(LongSupplier clock) { this.clock = clock; }

    public void clearCache() { cache.clear(); }

    /** Minúsculas, sem acento, sem espaços nas pontas e com espaços internos colapsados. */
    static String normalizeName(String name) {
        if (name == null) return "";
        String s = Normalizer.normalize(name.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return s.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public Resolution resolve(String domain, String token) {
        String key = domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);
        long now = clock.getAsLong();
        Cached hit = cache.get(key);
        if (hit != null && hit.expiresAt() > now) return hit.resolution();

        ResponseEntity<String> resp;
        try {
            resp = jiraService.getFields(domain, token);
        } catch (RuntimeException e) {
            throw new LookupException("Não foi possível consultar os campos do Jira: " + e.getMessage());
        }
        if (resp == null || !resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
            throw new LookupException("O Jira não listou os campos (status "
                    + (resp == null ? "?" : resp.getStatusCode().value()) + "). Confira a conexão e o token.");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new LookupException("Resposta inválida do Jira ao listar os campos.");
        }
        if (!root.isArray()) throw new LookupException("Resposta inválida do Jira ao listar os campos.");

        String wantStart = normalizeName(START_NAME);
        String wantEnd = normalizeName(END_NAME);
        List<FieldRef> start = new ArrayList<>();
        List<FieldRef> end = new ArrayList<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (JsonNode f : root) {
            String id = f.path("id").asText("");
            String name = f.path("name").asText("");
            if (id.isBlank() || name.isBlank()) continue;
            names.put(id, name);
            String n = normalizeName(name);
            if (n.equals(wantStart)) start.add(new FieldRef(id, name));
            else if (n.equals(wantEnd)) end.add(new FieldRef(id, name));
        }
        Resolution r = new Resolution(List.copyOf(start), List.copyOf(end), Map.copyOf(names));
        cache.put(key, new Cached(now + TTL_MS, r));
        return r;
    }

    /** O configurado, ou o único candidato; com 0 ou 2+ candidatos e nada configurado, nenhum (nunca escolhe em silêncio). */
    public static String pick(String configured, List<FieldRef> candidates) {
        if (configured != null && !configured.isBlank()) return configured.trim();
        return candidates.size() == 1 ? candidates.get(0).id() : null;
    }

    /** Ids para o sync. Nunca lança: falha do Jira vira "sem campo" e o sync segue com a precedência antiga. */
    public IdPair effectiveIds(Squad squad, String domain, String token) {
        String cfgStart = squad == null ? null : squad.getJiraStartField();
        String cfgEnd = squad == null ? null : squad.getJiraEndField();
        boolean needStart = cfgStart == null || cfgStart.isBlank();
        boolean needEnd = cfgEnd == null || cfgEnd.isBlank();
        if (!needStart && !needEnd) return new IdPair(cfgStart.trim(), cfgEnd.trim());
        try {
            Resolution r = resolve(domain, token);
            return new IdPair(pick(cfgStart, r.start()), pick(cfgEnd, r.end()));
        } catch (RuntimeException e) {
            log.warn("Não foi possível autodetectar os campos de data do Jira: {}", e.getMessage());
            return new IdPair(needStart ? null : cfgStart.trim(), needEnd ? null : cfgEnd.trim());
        }
    }
}
