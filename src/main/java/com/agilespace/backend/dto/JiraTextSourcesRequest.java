package com.agilespace.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Corpo de {@code POST /api/jira/text-sources}: o texto cru de issues que o navegador já buscou no Jira. O servidor não
 * chama o Jira; só lê o texto e diz de onde pode vir cada campo (docs/jira-import-origem-api.md).
 */
public record JiraTextSourcesRequest(
        @NotEmpty @Size(max = 200) List<@Valid Issue> issues
) {

    public record Comment(@Size(max = 100_000) String body, @Size(max = 200) String author, @Size(max = 40) String created) {
    }

    /**
     * @param description texto puro da descrição (o navegador já achata o ADF)
     * @param criteriaField valor do campo "Critérios de aceite" do Jira, quando existir
     * @param subtasks subtarefas da issue, com o mesmo formato (um nível)
     * @param parent issue pai, com o mesmo formato (um nível)
     */
    public record Issue(
            @Size(max = 60) String key,
            @Size(max = 120) String type,
            @Size(max = 500) String title,
            @Size(max = 200_000) String description,
            @Size(max = 100_000) String criteriaField,
            @Size(max = 200) List<@Valid Comment> comments,
            @Size(max = 50) List<@Valid Issue> subtasks,
            @Valid Issue parent
    ) {
    }
}
