package com.agilespace.backend.service;

import com.agilespace.backend.domain.SquadBoardConfig;
import com.agilespace.backend.repository.SquadBoardConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Iterator;
import java.util.Set;

/**
 * Personalização do quadro Scrum da squad (filtros rápidos, raias, limites de WIP). Qualquer membro lê; quem grava
 * é decidido no controller (liderança). A gravação é parcial: só as partes presentes no corpo mudam, e
 * {@code null} apaga a personalização daquela parte (volta a valer o que o Jira traz).
 */
@Service
@RequiredArgsConstructor
public class SquadBoardConfigService {

    static final Set<String> STRATEGIES = Set.of("queries", "parents", "assignees", "epics", "none");
    static final Set<String> KNOWN_KEYS = Set.of("quickFilters", "swimlanes", "swimlaneStrategy", "columns");
    /** Teto por parte: a tela guarda dezenas de itens; isto só impede lixo enorme na tabela. */
    static final int MAX_PART_CHARS = 64_000;
    static final int MAX_ITEMS = 200;

    private final SquadBoardConfigRepository repository;

    @Transactional(readOnly = true)
    public SquadBoardConfig get(String squadId) {
        return repository.findById(key(squadId)).orElseGet(() -> SquadBoardConfig.builder().squadId(key(squadId)).build());
    }

    @Transactional
    public SquadBoardConfig patch(String squadId, JsonNode body, String userId) {
        if (body == null || !body.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Corpo inválido: envie um objeto com as partes do quadro.");
        }
        for (Iterator<String> it = body.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            if (!KNOWN_KEYS.contains(name)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Campo desconhecido: " + name + ".");
            }
        }
        SquadBoardConfig cfg = repository.findById(key(squadId)).orElseGet(() -> SquadBoardConfig.builder().squadId(key(squadId)).build());
        if (body.has("quickFilters")) cfg.setQuickFilters(list(body.get("quickFilters"), "quickFilters"));
        if (body.has("swimlanes")) cfg.setSwimlanes(list(body.get("swimlanes"), "swimlanes"));
        if (body.has("columns")) cfg.setColumns(list(body.get("columns"), "columns"));
        if (body.has("swimlaneStrategy")) {
            JsonNode s = body.get("swimlaneStrategy");
            if (s.isNull()) {
                cfg.setSwimlaneStrategy(null);
            } else if (s.isTextual() && STRATEGIES.contains(s.asText())) {
                cfg.setSwimlaneStrategy(s.asText());
            } else {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estratégia de raias inválida.");
            }
        }
        cfg.setUpdatedBy(userId);
        return repository.save(cfg);
    }

    /** Lista JSON dentro do teto, ou null (apagar). */
    private static JsonNode list(JsonNode node, String name) {
        if (node == null || node.isNull()) return null;
        if (!node.isArray()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " deve ser uma lista.");
        }
        if (node.size() > MAX_ITEMS || node.toString().length() > MAX_PART_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " é grande demais.");
        }
        return node;
    }

    private static String key(String squadId) {
        return squadId == null ? "" : squadId.trim();
    }
}
