package com.agilespace.backend.service;

import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.domain.SquadPersonConfig;
import com.agilespace.backend.repository.SquadPersonConfigRepository;
import com.agilespace.backend.repository.SquadRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static com.agilespace.backend.domain.SquadPersonConfig.GLOBAL_SPRINT_ID;

/**
 * Papel/capacidade por pessoa com herança campo a campo: sprint atual → sprint anterior
 * mais recente do squad com valor pro campo → default global do squad → default fixo
 * (8h/dia, sem papel). Transliterado de person-config.js (jiradash), sem a maquinaria de
 * escopo/redação de identidade daquele arquivo — aqui a pessoa já é identificada por
 * jiraAccountId estável, não por displayName. Ver plano de unificação Squad Pulse +
 * jiradash, Fase 6.
 */
@Service
@RequiredArgsConstructor
public class SquadCapacityService {

    private static final double DEFAULT_HORAS_PRODUTIVAS = 8.0;
    private static final List<String> VALID_PAPEIS = List.of("", "DEV", "QA");

    private final SquadPersonConfigRepository repository;
    private final SquadRepository squadRepository;

    public record ResolvedPersonConfig(
            String jiraAccountId,
            String papel, int diasCodificacaoTeste, int diasRegressivo, double horasProdutivas,
            boolean papelInherited, boolean diasCodificacaoTesteInherited, boolean diasRegressivoInherited, boolean horasProdutivasInherited,
            String papelSource, String diasCodificacaoTesteSource, String diasRegressivoSource, String horasProdutivasSource
    ) {
        // Capacity por período: dias × horas produtivas/dia. Nunca soma dias com horas.
        public double capacityCodificacaoTesteHours() { return horasProdutivas * diasCodificacaoTeste; }
        public double capacityRegressivoHours() { return diasRegressivo * horasProdutivas; }
        // Total das duas fases — o que qualquer consumidor de "capacidade da pessoa" deve usar.
        public double capacityHours() { return capacityCodificacaoTesteHours() + capacityRegressivoHours(); }
    }

    private record FieldResolution<T>(T value, boolean inherited, String source) {
        static <T> FieldResolution<T> empty() { return new FieldResolution<>(null, false, null); }
    }

    @Transactional
    public SquadPersonConfig save(String squadId, String jiraAccountId, String sprintId, SquadPersonConfig updates) {
        validate(updates);
        String effectiveSprintId = (sprintId == null || sprintId.isBlank()) ? GLOBAL_SPRINT_ID : sprintId;
        String dbId = squadId + "_" + effectiveSprintId + "_" + jiraAccountId;

        SquadPersonConfig target = repository.findById(dbId).orElseGet(SquadPersonConfig::new);
        target.setDbId(dbId);
        target.setSquadId(squadId);
        target.setSprintId(effectiveSprintId);
        target.setJiraAccountId(jiraAccountId);
        // Papel aceita string vazia como valor explícito ("removido do time") — só null
        // (campo ausente no payload) preserva o que já existia.
        if (updates.getPapel() != null) target.setPapel(updates.getPapel());
        if (updates.getDiasCodificacaoTeste() != null) target.setDiasCodificacaoTeste(updates.getDiasCodificacaoTeste());
        if (updates.getDiasRegressivo() != null) target.setDiasRegressivo(updates.getDiasRegressivo());
        if (updates.getHorasProdutivas() != null) target.setHorasProdutivas(updates.getHorasProdutivas());
        target.setUpdatedAt(Instant.now().toString());
        return repository.save(target);
    }

    private void validate(SquadPersonConfig c) {
        if (c.getPapel() != null && !VALID_PAPEIS.contains(c.getPapel())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Papel inválido: use DEV, QA ou vazio.");
        }
        validateDias(c.getDiasCodificacaoTeste(), "Dias de Codificação/Teste");
        validateDias(c.getDiasRegressivo(), "Dias de Regressivo");
        if (c.getHorasProdutivas() != null) {
            double horas = c.getHorasProdutivas();
            if (horas < 0 || horas > 24) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Horas produtivas/dia: use um valor entre 0 e 24.");
            }
            double scaled = horas * 100;
            if (Math.abs(scaled - Math.round(scaled)) >= 1e-9) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Horas produtivas/dia: use no máximo 2 casas decimais (ex.: 5,12 ou 5.12).");
            }
        }
    }

    private void validateDias(Integer dias, String label) {
        if (dias != null && (dias < 0 || dias > 31)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + ": use um valor entre 0 e 31.");
        }
    }

    @Transactional(readOnly = true)
    public ResolvedPersonConfig resolve(String squadId, String currentSprintId, String jiraAccountId) {
        List<String> priorSprintIds = priorSprintIdsMostRecentFirst(squadId, currentSprintId);

        FieldResolution<String> papel = resolveField(squadId, currentSprintId, priorSprintIds, jiraAccountId, SquadPersonConfig::getPapel);
        FieldResolution<Integer> diasCodTeste = resolveField(squadId, currentSprintId, priorSprintIds, jiraAccountId, SquadPersonConfig::getDiasCodificacaoTeste);
        FieldResolution<Integer> diasRegressivo = resolveField(squadId, currentSprintId, priorSprintIds, jiraAccountId, SquadPersonConfig::getDiasRegressivo);
        FieldResolution<Double> horas = resolveField(squadId, currentSprintId, priorSprintIds, jiraAccountId, SquadPersonConfig::getHorasProdutivas);

        return new ResolvedPersonConfig(
                jiraAccountId,
                papel.value() != null ? papel.value() : "",
                diasCodTeste.value() != null ? diasCodTeste.value() : 0,
                diasRegressivo.value() != null ? diasRegressivo.value() : 0,
                horas.value() != null ? horas.value() : DEFAULT_HORAS_PRODUTIVAS,
                papel.inherited(), diasCodTeste.inherited(), diasRegressivo.inherited(), horas.inherited(),
                papel.source(), diasCodTeste.source(), diasRegressivo.source(), horas.source()
        );
    }

    private <T> FieldResolution<T> resolveField(String squadId, String currentSprintId, List<String> priorSprintIds,
                                                 String jiraAccountId, Function<SquadPersonConfig, T> extractor) {
        if (currentSprintId != null && !currentSprintId.isBlank()) {
            T value = extractor.apply(loadOrEmpty(squadId, currentSprintId, jiraAccountId));
            if (value != null) return new FieldResolution<>(value, false, null);
        }
        for (String priorSprintId : priorSprintIds) {
            T value = extractor.apply(loadOrEmpty(squadId, priorSprintId, jiraAccountId));
            if (value != null) return new FieldResolution<>(value, true, priorSprintId);
        }
        T globalValue = extractor.apply(loadOrEmpty(squadId, GLOBAL_SPRINT_ID, jiraAccountId));
        if (globalValue != null) {
            return new FieldResolution<>(globalValue, currentSprintId != null && !currentSprintId.isBlank(), GLOBAL_SPRINT_ID);
        }
        return FieldResolution.empty();
    }

    private SquadPersonConfig loadOrEmpty(String squadId, String sprintId, String jiraAccountId) {
        return repository.findBySquadIdAndSprintIdAndJiraAccountId(squadId, sprintId, jiraAccountId).orElseGet(SquadPersonConfig::new);
    }

    // Lista de sprints anteriores do squad, mais recente primeiro — equivalente ao
    // "priorSprintIdsSameBoard" do jiradash: aqui "mesmo board" já é implícito (um squad =
    // um board/projeto Jira), então basta olhar o próprio sprintHistory que o sync já mantém.
    private List<String> priorSprintIdsMostRecentFirst(String squadId, String currentSprintId) {
        Squad squad = squadRepository.findById(squadId).orElse(null);
        if (squad == null || squad.getSprintHistory() == null || !squad.getSprintHistory().isArray()) {
            return List.of();
        }
        List<JsonNode> entries = new ArrayList<>();
        squad.getSprintHistory().forEach(entries::add);
        return entries.stream()
                .filter(e -> {
                    String id = e.path("sprintId").asText("");
                    return !id.isBlank() && !id.equals(currentSprintId);
                })
                .sorted((a, b) -> b.path("sprintStart").asText("").compareTo(a.path("sprintStart").asText("")))
                .map(e -> e.path("sprintId").asText(""))
                .toList();
    }
}
