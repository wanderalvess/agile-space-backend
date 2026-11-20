package com.agilespace.backend.service.sprintplan;

import com.agilespace.backend.domain.SprintPlan;
import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.domain.UserJiraConfig;
import com.agilespace.backend.dto.SprintPlanDtos.NextSprintView;
import com.agilespace.backend.repository.SprintPlanRepository;
import com.agilespace.backend.repository.UserJiraConfigRepository;
import com.agilespace.backend.service.JiraService;
import com.agilespace.backend.service.SquadService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Leitura (somente leitura) do Jira para o Sprint Planner: próxima sprint do quadro da squad e meta atual das sprints
 * dos planos. Usa o token do usuário (UserJiraConfig), o mesmo do sync. Nada é gravado no Jira.
 */
@Service
public class SprintPlanJiraService {

    private static final Logger log = LoggerFactory.getLogger(SprintPlanJiraService.class);

    private final SquadService squadService;
    private final JiraService jiraService;
    private final UserJiraConfigRepository userJiraConfigRepository;
    private final SprintPlanRepository planRepository;
    private final ObjectMapper objectMapper;
    private final Duration minInterval;
    private final Map<String, Instant> lastCallByKey = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    @Autowired
    public SprintPlanJiraService(SquadService squadService, JiraService jiraService,
                                 UserJiraConfigRepository userJiraConfigRepository, SprintPlanRepository planRepository,
                                 ObjectMapper objectMapper,
                                 @Value("${app.planner.next-sprint.min-interval-sec:10}") long minIntervalSec) {
        this.squadService = squadService;
        this.jiraService = jiraService;
        this.userJiraConfigRepository = userJiraConfigRepository;
        this.planRepository = planRepository;
        this.objectMapper = objectMapper;
        this.minInterval = Duration.ofSeconds(Math.max(0, minIntervalSec));
    }

    void setClock(Clock clock) { this.clock = clock; }

    // ---------------- Próxima sprint ----------------

    public NextSprintView nextSprint(String squadId, String userId) {
        Squad squad = squadService.getSquad(squadId).orElse(null);
        String board = squad == null || squad.getRapidViewId() == null ? "" : squad.getRapidViewId().trim();
        if (board.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A squad não tem o número do quadro (board) do Jira configurado. Informe-o nas configurações da squad.");
        }
        UserJiraConfig cfg = userId == null ? null : userJiraConfigRepository.findById(userId).orElse(null);
        if (cfg == null || cfg.getToken() == null || cfg.getToken().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Configure seu Token de Acesso do Jira em Conexão Jira antes de buscar a próxima sprint.");
        }
        String domain = cfg.getDomain() != null && !cfg.getDomain().isBlank() ? cfg.getDomain() : squad.getJiraDomain();
        if (domain == null || domain.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Domínio do Jira não configurado.");
        }
        acquire(squadId + "|" + userId);

        ResponseEntity<String> resp;
        try {
            resp = jiraService.listBoardSprints(domain, cfg.getToken(), board);
        } catch (RuntimeException e) {
            throw badGateway();
        }
        if (resp == null || !resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) throw badGateway();
        JsonNode values;
        try {
            values = objectMapper.readTree(resp.getBody()).path("values");
        } catch (Exception e) {
            throw badGateway();
        }
        JsonNode best = null;
        for (JsonNode s : values) {
            if (!"future".equalsIgnoreCase(s.path("state").asText(""))) continue;
            if (best == null || startOf(s).compareTo(startOf(best)) < 0) best = s;
        }
        if (best == null) return new NextSprintView(false, null, null, null, null, null, null);
        return new NextSprintView(true, best.path("id").asText(null), nullIfBlank(best.path("name").asText(null)),
                day10(best.path("startDate").asText(null)), day10(best.path("endDate").asText(null)),
                nullIfBlank(best.path("goal").asText(null)), "future");
    }

    /** Sprint sem data de início vai para o fim; as demais por data (ISO ordena como texto). */
    private static String startOf(JsonNode s) {
        String d = s.path("startDate").asText("");
        return d.isBlank() ? "9999" : d;
    }

    void acquire(String key) {
        Instant now = clock.instant();
        Instant[] blocked = new Instant[1];
        lastCallByKey.compute(key, (k, last) -> {
            if (last != null && last.plus(minInterval).isAfter(now)) {
                blocked[0] = last.plus(minInterval);
                return last;
            }
            return now;
        });
        if (blocked[0] != null) {
            long secs = Math.max(1, Duration.between(now, blocked[0]).toSeconds());
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Tente de novo em " + secs + " s.");
        }
    }

    // ---------------- Meta atual no Jira (chamado pelo sync do backlog) ----------------

    /**
     * Atualiza jira_goal dos planos da squad que têm sprint do Jira e ainda não foram enviados. Uma leitura por plano,
     * só dentro do sync do backlog; falha de uma sprint não derruba o sync.
     */
    public void refreshGoals(String squadId, String domain, String token) {
        List<SprintPlan> plans = planRepository.findBySquadIdOrderByStartDateDesc(squadId);
        if (plans == null) return;
        for (SprintPlan p : plans) {
            if (p.getJiraSprintId() == null || p.getJiraSprintId().isBlank()
                    || SprintPlan.STATUS_ENVIADO.equals(p.getStatus())) continue;
            try {
                ResponseEntity<String> resp = jiraService.getSprint(domain, token, p.getJiraSprintId());
                if (resp == null || !resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) continue;
                JsonNode data = objectMapper.readTree(resp.getBody());
                if (data.path("id").isMissingNode()) continue;
                String goal = nullIfBlank(data.path("goal").asText(null));
                if (!Objects.equals(goal, p.getJiraGoal())) {
                    p.setJiraGoal(goal);
                    planRepository.save(p);
                }
            } catch (Exception e) {
                log.warn("[squad:{}] não foi possível ler a meta da sprint {} no Jira.", squadId, p.getJiraSprintId());
            }
        }
    }

    private static ResponseStatusException badGateway() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Não foi possível consultar o Jira. Tente de novo em instantes.");
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() || "null".equals(s) ? null : s.trim();
    }

    private static String day10(String s) {
        String v = nullIfBlank(s);
        return v == null ? null : (v.length() >= 10 ? v.substring(0, 10) : v);
    }
}
