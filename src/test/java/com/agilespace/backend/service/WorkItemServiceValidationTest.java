package com.agilespace.backend.service;

import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.domain.SquadIssueSnapshot;
import com.agilespace.backend.domain.WorkItem;
import com.agilespace.backend.repository.SquadIssueSnapshotRepository;
import com.agilespace.backend.repository.SquadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("WorkItemService - estimativa válida, sprint obrigatória, título e stats sem misturar sprints")
public class WorkItemServiceValidationTest {

    @Mock private SquadIssueSnapshotRepository issueSnapshotRepository;
    @Mock private SquadRepository squadRepository;
    @InjectMocks private WorkItemService service;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    @DisplayName("Estimativa negativa, NaN, infinita ou absurda é recusada com 400 e nada é gravado")
    void invalidPointsAreRejected() {
        for (double bad : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, 1001}) {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.estimateWorkItem("SQ1", "A-1", bad));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }
        verifyNoInteractions(issueSnapshotRepository);
    }

    @Test
    @DisplayName("Estimativa 0 e vazio (limpar) continuam valendo")
    void zeroAndNullAreAccepted() {
        when(issueSnapshotRepository.findBySquadIdAndJiraKey("SQ1", "A-1")).thenReturn(Optional.empty());

        service.estimateWorkItem("SQ1", "A-1", 0.0);
        service.estimateWorkItem("SQ1", "A-1", null);

        verify(issueSnapshotRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("Compromisso sem sprint é recusado")
    void commitNeedsSprint() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.commitWorkItem("SQ1", "A-1", " ")).getStatusCode());
        verifyNoInteractions(issueSnapshotRepository);
    }

    @Test
    @DisplayName("Sprint ativa sem itens devolve zero — não soma os itens de todas as outras sprints")
    void statsWithoutItemsInActiveSprintAreZero() {
        when(squadRepository.findById("SQ1")).thenReturn(Optional.of(Squad.builder().id("SQ1").name("SQ1").activeSprintId("7").build()));
        when(issueSnapshotRepository.findBySquadIdAndSprintId("SQ1", "7")).thenReturn(List.of());

        Map<String, Object> stats = service.getSprintStats("SQ1", "active");

        assertEquals(0.0, stats.get("previsto"));
        assertEquals(0.0, stats.get("entregue"));
        assertEquals(0, stats.get("carryOvers"));
        verify(issueSnapshotRepository, never()).findBySquadId(anyString());
    }

    @Test
    @DisplayName("Item mostra o título do Jira; só cai para a chave quando não há título")
    void workItemViewUsesTitle() {
        SquadIssueSnapshot withTitle = SquadIssueSnapshot.builder().dbId("SQ1_A-1").squadId("SQ1").jiraKey("A-1").title("Login com SSO").build();
        SquadIssueSnapshot noTitle = SquadIssueSnapshot.builder().dbId("SQ1_A-2").squadId("SQ1").jiraKey("A-2").build();
        when(issueSnapshotRepository.findBySquadId("SQ1")).thenReturn(List.of(withTitle, noTitle));

        List<WorkItem> items = service.getWorkItemsBySquadId("SQ1");

        assertEquals("Login com SSO", items.get(0).getTitle());
        assertEquals("A-2", items.get(1).getTitle());
    }
}
