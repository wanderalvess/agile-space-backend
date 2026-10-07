package com.agilespace.backend.dto;

import lombok.Data;
import java.util.List;

@Data
public class JiraSearchRequest {
    private String domain;
    private String token;
    private String jql;
    private Integer maxResults;
    private Integer startAt;
    private List<String> fields;
    // Opt-in: changelog é um payload pesado (histórico inteiro de transições por issue) —
    // só o Squad, calculando cycle time/scope churn, precisa disso hoje (ver
    // SquadSyncService). Default false preserva o comportamento de todo outro chamador.
    private Boolean includeChangelog;
}
