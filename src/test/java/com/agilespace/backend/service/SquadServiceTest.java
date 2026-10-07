package com.agilespace.backend.service;

import com.agilespace.backend.domain.*;
import com.agilespace.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SquadService - Gestão de Squads, Métricas, Membros e Snapshots do Jira")
class SquadServiceTest {

    @Mock private SquadRepository squadRepository;
    @Mock private SquadMetricsRollupRepository rollupRepository;
    @Mock private SquadIssueSnapshotRepository issueSnapshotRepository;
    @Mock private SquadMemberRepository memberRepository;
    @Mock private SquadMemberMetricRepository memberMetricRepository;
    @Mock private SquadDailySnapshotRepository dailySnapshotRepository;
    @Mock private SquadIssueWorklogCacheRepository worklogCacheRepository;

    @InjectMocks
    private SquadService service;

    private Squad sampleSquad;

    @BeforeEach
    void setUp() {
        sampleSquad = Squad.builder()
                .id("squad-alpha")
                .name("Squad Alpha")
                .jiraProjectKey("ALPHA")
                .build();
    }

    @Nested
    @DisplayName("Gestão Básica de Squad")
    class BasicSquadTests {

        @Test
        @DisplayName("Deve buscar squad por ID retornando dados completos")
        void shouldReturnSquadWhenExists() {
            when(squadRepository.findById("squad-alpha")).thenReturn(Optional.of(sampleSquad));

            Optional<Squad> result = service.getSquad("squad-alpha");

            assertTrue(result.isPresent());
            assertEquals("squad-alpha", result.get().getId());
            assertEquals("ALPHA", result.get().getJiraProjectKey());
        }

        @Test
        @DisplayName("Deve salvar novo squad com sucesso")
        void shouldSaveSquadSuccessfully() {
            when(squadRepository.findById("squad-alpha")).thenReturn(Optional.empty());
            when(squadRepository.save(any(Squad.class))).thenAnswer(i -> i.getArgument(0));

            Squad saved = service.saveSquad(sampleSquad);

            assertNotNull(saved);
            assertEquals("squad-alpha", saved.getId());
            assertEquals("ALPHA", saved.getJiraProjectKey());
            // saveSquad faz merge num target novo/existente, nunca salva a instância recebida
            // por referência (mesmo padrão de SquadService.saveMember) — verificar por
            // conteúdo, não por identidade de objeto.
            verify(squadRepository).save(argThat(s -> "squad-alpha".equals(s.getId()) && "ALPHA".equals(s.getJiraProjectKey())));
        }
    }

    @Nested
    @DisplayName("Snapshots de Issues e Datas do Cronograma")
    class IssueSnapshotTests {

        @Test
        @DisplayName("Deve salvar lote de issues gerando chave composta squadId_jiraKey")
        void shouldBatchUpsertIssuesWithCompositeKey() {
            SquadIssueSnapshot snap1 = SquadIssueSnapshot.builder().jiraKey("ALPHA-1").status("To Do").build();

            when(issueSnapshotRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

            List<SquadIssueSnapshot> result = service.batchUpsertIssues("squad-alpha", Collections.singletonList(snap1));

            assertEquals(1, result.size());
            assertEquals("squad-alpha_ALPHA-1", result.get(0).getDbId());
            assertEquals("squad-alpha", result.get(0).getSquadId());
        }

        @Test
        @DisplayName("Deve persistir datas de cronograma (targetStart e targetEnd) no snapshot")
        void shouldPersistScheduleDatesInSnapshot() {
            SquadIssueSnapshot snap = SquadIssueSnapshot.builder()
                    .jiraKey("ALPHA-2")
                    .status("In Progress")
                    .targetStart("2026-08-18")
                    .targetEnd("2026-08-25")
                    .build();

            when(issueSnapshotRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

            List<SquadIssueSnapshot> result = service.batchUpsertIssues("squad-alpha", Collections.singletonList(snap));

            assertEquals("2026-08-18", result.get(0).getTargetStart());
            assertEquals("2026-08-25", result.get(0).getTargetEnd());
        }

        @Test
        @DisplayName("Deve buscar issues filtradas por sprintId quando fornecido")
        void shouldGetIssuesFilteredBySprint() {
            when(issueSnapshotRepository.findBySquadIdAndSprintId("squad-alpha", "sprint-10"))
                    .thenReturn(Collections.singletonList(new SquadIssueSnapshot()));

            List<SquadIssueSnapshot> result = service.getIssues("squad-alpha", "sprint-10");

            assertEquals(1, result.size());
            verify(issueSnapshotRepository).findBySquadIdAndSprintId("squad-alpha", "sprint-10");
        }

        @Test
        @DisplayName("Deve buscar todas as issues da squad quando sprintId for nulo")
        void shouldGetAllIssuesWhenSprintIdIsNull() {
            when(issueSnapshotRepository.findBySquadId("squad-alpha"))
                    .thenReturn(Collections.singletonList(new SquadIssueSnapshot()));

            List<SquadIssueSnapshot> result = service.getIssues("squad-alpha", null);

            assertEquals(1, result.size());
            verify(issueSnapshotRepository).findBySquadId("squad-alpha");
        }

        @Test
        @DisplayName("Deve excluir issues em lote por chaves do Jira")
        void shouldDeleteIssuesInBatch() {
            List<String> keys = Arrays.asList("ALPHA-1", "ALPHA-2");

            service.batchDeleteIssues("squad-alpha", keys);

            verify(issueSnapshotRepository).deleteBySquadIdAndJiraKeyIn("squad-alpha", keys);
        }
    }

    @Nested
    @DisplayName("Gestão de Membros da Squad")
    class MemberTests {

        @Test
        @DisplayName("Deve salvar membro gerando chave squadId_jiraAccountId e mesclando dados")
        void shouldSaveMemberWithCompositeKey() {
            SquadMember member = SquadMember.builder().displayName("Wanderson").capacityHoursPerDay(8.0).build();
            when(memberRepository.findBySquadIdAndJiraAccountId("squad-alpha", "acc-123")).thenReturn(Optional.empty());
            when(memberRepository.save(any(SquadMember.class))).thenAnswer(i -> i.getArgument(0));

            SquadMember saved = service.saveMember("squad-alpha", "acc-123", member);

            assertEquals("squad-alpha_acc-123", saved.getDbId());
            assertEquals("squad-alpha", saved.getSquadId());
            assertEquals("acc-123", saved.getJiraAccountId());
            assertEquals(8.0, saved.getCapacityHoursPerDay());
        }

        @Test
        @DisplayName("Deve preservar campos existentes ao atualizar membro com payload parcial")
        void shouldMergeExistingMemberDataWithoutOverwritingWithNulls() {
            SquadMember existing = SquadMember.builder()
                    .displayName("Nome Antigo")
                    .capacityHoursPerDay(8.0)
                    .build();
            SquadMember update = SquadMember.builder().displayName("Nome Novo").build();

            when(memberRepository.findBySquadIdAndJiraAccountId("squad-alpha", "acc-123")).thenReturn(Optional.of(existing));
            when(memberRepository.save(any(SquadMember.class))).thenAnswer(i -> i.getArgument(0));

            SquadMember saved = service.saveMember("squad-alpha", "acc-123", update);

            assertEquals("Nome Novo", saved.getDisplayName());
            assertEquals(8.0, saved.getCapacityHoursPerDay(), "Capacidade existente deve ser mantida");
        }
    }

    @Nested
    @DisplayName("Cache de Worklogs e Snapshots Diários")
    class WorklogAndDailySnapshotTests {

        @Test
        @DisplayName("Deve salvar snapshot diário com chave squadId_data")
        void shouldSaveDailySnapshot() {
            SquadDailySnapshot snap = SquadDailySnapshot.builder().snapshotDate("2026-09-01").build();
            when(dailySnapshotRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

            List<SquadDailySnapshot> result = service.batchUpsertDailySnapshots("squad-alpha", Collections.singletonList(snap));

            assertEquals("squad-alpha_2026-09-01", result.get(0).getDbId());
        }

        @Test
        @DisplayName("Deve gerenciar cache de worklogs gerando ID composto")
        void shouldManageWorklogCache() {
            SquadIssueWorklogCache w1 = SquadIssueWorklogCache.builder().jiraKey("ALPHA-10").build();
            when(worklogCacheRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

            List<SquadIssueWorklogCache> result = service.batchUpsertWorklogCache("squad-alpha", Collections.singletonList(w1));

            assertEquals("squad-alpha_ALPHA-10", result.get(0).getDbId());
        }

        @Test
        @DisplayName("Deve excluir entrada específica do cache de worklogs")
        void shouldDeleteWorklogCacheEntry() {
            service.deleteWorklogCacheEntry("squad-alpha", "ALPHA-10");

            verify(worklogCacheRepository).deleteById("squad-alpha_ALPHA-10");
        }
    }

    @org.junit.jupiter.api.Nested
    @org.junit.jupiter.api.DisplayName("Leitura do rollup quando a sprint ativa não tem rollup")
    class RollupFallbackTests {

        private com.agilespace.backend.domain.SquadMetricsRollup rollup(String sprint, String computedAt) {
            com.agilespace.backend.domain.SquadMetricsRollup r = new com.agilespace.backend.domain.SquadMetricsRollup();
            r.setSquadId("DDW");
            r.setSprintId(sprint);
            r.setComputedAt(computedAt);
            return r;
        }

        @org.junit.jupiter.api.Test
        @org.junit.jupiter.api.DisplayName("active_sprint_id = UNMAPPED sem rollup: devolve o rollup mais recente em vez de 404")
        void fallsBackToLatestRollup() {
            com.agilespace.backend.domain.Squad squad = com.agilespace.backend.domain.Squad.builder().id("DDW").activeSprintId("UNMAPPED").build();
            org.mockito.Mockito.when(squadRepository.findById("DDW")).thenReturn(java.util.Optional.of(squad));
            org.mockito.Mockito.when(rollupRepository.findBySquadIdAndSprintId("DDW", "UNMAPPED")).thenReturn(java.util.Optional.empty());
            org.mockito.Mockito.when(rollupRepository.findBySquadId("DDW")).thenReturn(java.util.List.of(
                    rollup("1", "2026-10-01T10:00:00Z"), rollup("52883", "2026-10-06T17:26:04Z")));

            java.util.Optional<com.agilespace.backend.domain.SquadMetricsRollup> got = service.getRollup("DDW");

            org.junit.jupiter.api.Assertions.assertTrue(got.isPresent());
            org.junit.jupiter.api.Assertions.assertEquals("52883", got.get().getSprintId());
        }

        @org.junit.jupiter.api.Test
        @org.junit.jupiter.api.DisplayName("Com rollup na sprint ativa, ele tem prioridade")
        void activeSprintWins() {
            com.agilespace.backend.domain.Squad squad = com.agilespace.backend.domain.Squad.builder().id("DDW").activeSprintId("7").build();
            org.mockito.Mockito.when(squadRepository.findById("DDW")).thenReturn(java.util.Optional.of(squad));
            org.mockito.Mockito.when(rollupRepository.findBySquadIdAndSprintId("DDW", "7")).thenReturn(java.util.Optional.of(rollup("7", "2026-09-01T00:00:00Z")));

            org.junit.jupiter.api.Assertions.assertEquals("7", service.getRollup("DDW").orElseThrow().getSprintId());
        }

        @org.junit.jupiter.api.Test
        @org.junit.jupiter.api.DisplayName("Sem nenhum rollup continua vazio")
        void emptyWhenNothing() {
            org.mockito.Mockito.when(squadRepository.findById("DDW")).thenReturn(java.util.Optional.empty());
            org.mockito.Mockito.when(rollupRepository.findBySquadId("DDW")).thenReturn(java.util.List.of());
            org.junit.jupiter.api.Assertions.assertTrue(service.getRollup("DDW").isEmpty());
        }
    }

    @org.junit.jupiter.api.Nested
    @org.junit.jupiter.api.DisplayName("ID do quadro Scrum")
    class RapidViewIdTests {
        @org.junit.jupiter.api.Test
        @org.junit.jupiter.api.DisplayName("saveSquad guarda o rapidViewId enviado (antes era descartado)")
        void savesRapidViewId() {
            org.mockito.Mockito.when(squadRepository.findById("DDW")).thenReturn(java.util.Optional.empty());
            org.mockito.Mockito.when(squadRepository.save(org.mockito.ArgumentMatchers.any(com.agilespace.backend.domain.Squad.class))).thenAnswer(i -> i.getArgument(0));

            com.agilespace.backend.domain.Squad saved = service.saveSquad(com.agilespace.backend.domain.Squad.builder().id("DDW").rapidViewId("1234").build());

            org.junit.jupiter.api.Assertions.assertEquals("1234", saved.getRapidViewId());
        }
    }
}
