package com.agilespace.backend.dto;

import java.util.List;

/** Resposta de {@code POST /api/jira/text-sources}: uma entrada por issue, na ordem do pedido. */
public record JiraTextSourcesResponse(List<IssueSources> issues) {

    /** Uma fonte possível para um campo. {@code explicit}: veio de título conhecido ou de campo do Jira; false = palpite. */
    public record Candidate(String id, String kind, String label, String text, boolean explicit) {
    }

    /**
     * @param confidence {@code certa} (uma fonte explícita, entra sozinha), {@code revisar} (palpite ou mais de uma fonte
     *                   explícita: a pessoa escolhe) ou {@code vazio}
     * @param sourceId fonte aceita; ausente quando o campo fica vazio até a pessoa escolher
     */
    public record Field(String confidence, String sourceId, List<Candidate> candidates) {
    }

    public record IssueSources(String key, Field problem, Field solution, Field acceptanceCriteria) {
    }
}
