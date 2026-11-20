package com.agilespace.backend.domain;

import com.agilespace.backend.service.SubtaskTypeMappingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("SprintPlan.sprintKey - chave aceita pelo backlog")
class SprintPlanSprintKeyTest {

    @Test
    @DisplayName("plano sem sprint do Jira: a chave passa na validação do backlog (antes 'plan:' dava 422)")
    void manualPlanKeyIsValid() {
        SprintPlan plan = SprintPlan.builder().id(UUID.randomUUID().toString()).build();
        assertEquals(plan.sprintKey(), SubtaskTypeMappingService.normalizeSprintKey(plan.sprintKey()));
    }

    @Test
    @DisplayName("plano com sprint do Jira: a chave é o id da sprint")
    void jiraPlanKeyIsSprintId() {
        SprintPlan plan = SprintPlan.builder().id("P1").jiraSprintId("S3").build();
        assertEquals("S3", plan.sprintKey());
    }
}
