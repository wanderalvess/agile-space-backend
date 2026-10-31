package com.agilespace.backend.service;

import com.agilespace.backend.domain.*;
import com.agilespace.backend.dto.JiraPublishDtos;
import com.agilespace.backend.repository.*;
import com.agilespace.backend.websocket.PokerWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("JiraPublishService - Poker → Jira (pai renomeada, sub-tarefas, comentários, fila)")
class JiraPublishServiceTest {

    @Mock JiraPublishJobRepository jobRepository;
    @Mock JiraPublishStepRepository stepRepository;
    @Mock JiraPublishEventRepository eventRepository;
    @Mock UserRepository userRepository;
    @Mock SquadAccessService squadAccessService;
    @Mock SquadSubtaskTemplateRepository templateRepository;
    @Mock PokerRoomRepository roomRepository;
    @Mock PokerParticipantRepository participantRepository;
    @Mock SquadRepository squadRepository;
    @Mock UserJiraConfigRepository userJiraConfigRepository;
    @Mock JiraService jiraService;
    @Mock PokerWebSocketHandler webSocketHandler;

    private final ObjectMapper mapper = new ObjectMapper();
    private JiraPublishService service;
    private PokerRoom room;

    @BeforeEach
    void setup() {
        service = new JiraPublishService(jobRepository, stepRepository, eventRepository, userRepository, squadAccessService, templateRepository, roomRepository,
                participantRepository, squadRepository, userJiraConfigRepository, jiraService, webSocketHandler, mapper);

        room = new PokerRoom();
        room.setId("room-1");
        room.setTeam("DDWMISSI");
        room.setDeckType("hours");
        room.setCreatorId("user-1");
        ArrayNode queue = mapper.createArrayNode();
        ObjectNode issue = queue.addObject();
        issue.put("id", "iss-1");
        issue.put("key", "DDWMISSI-100");
        issue.put("title", "Tarefa fazer hoje (DEV 4h / QA 2h)");
        issue.put("jiraType", "História");
        issue.put("devPoints", "10");
        issue.put("qaPoints", "12");
        issue.put("devNotes", "Trocar o cálculo de ST");
        issue.put("qaNotes", "Testar com NF de devolução");
        room.setIssuesQueue(queue);
    }

    private SquadSubtaskTemplate template(String id, String type, String role, String title, String comment, String parentType) {
        return SquadSubtaskTemplate.builder().id(id).squadId("DDWMISSI").parentIssueType(parentType)
                .jiraIssueTypeName(type).role(role).titleTemplate(title).commentTemplate(comment).active(true).position(0).build();
    }

    private void configuredTemplates() {
        when(templateRepository.findBySquadIdIgnoreCaseOrderByPositionAsc("DDWMISSI")).thenReturn(List.of(
                template("t1", "Codificação", "DEV", "{pai} - {tipo} - {resumo}", "DEV ({horas}): {notas}", null),
                template("t2", "Code Review", "NONE", "{pai} - {tipo} - {resumo}", null, null),
                template("t3", "Execução de TI", "QA", "{pai} - {tipo} - {resumo}", "QA ({horas}): {notas}", "História"),
                template("t4", "Só Manutenção", "DEV", "{pai} - {tipo}", null, "Manutenção")));
    }

    @Nested
    @DisplayName("prévia")
    class Preview {

        @Test
        void montaRenomearSubsEComentariosAPartirDosTemplatesDaSquad() {
            when(roomRepository.findById("room-1")).thenReturn(Optional.of(room));
            configuredTemplates();

            JiraPublishDtos.Preview p = service.preview("room-1", "iss-1", "user-1", "USER");

            assertEquals("DDWMISSI-100", p.getParentKey());
            assertEquals("DDWMISSI", p.getProjectKey());
            assertEquals(10.0, p.getDevHours());
            assertEquals(12.0, p.getQaHours());
            // rename + (t1 create + comment) + (t2 create) + (t3 create + comment); t4 é só de Manutenção
            assertEquals(6, p.getSteps().size());
            JiraPublishDtos.Step rename = p.getSteps().get(0);
            assertEquals(JiraPublishStep.RENAME_PARENT, rename.getKind());
            assertEquals("Tarefa fazer hoje (DEV 10h / QA 12h)", rename.getSummary(), "troca o sufixo antigo, não duplica");

            JiraPublishDtos.Step dev = p.getSteps().get(1);
            assertEquals(JiraPublishStep.CREATE_SUBTASK, dev.getKind());
            assertEquals("Codificação", dev.getIssueType());
            assertEquals("DDWMISSI-100 - Codificação - Tarefa fazer hoje", dev.getSummary());
            assertEquals(10.0, dev.getEstimateHours());
            JiraPublishDtos.Step devComment = p.getSteps().get(2);
            assertEquals(JiraPublishStep.ADD_COMMENT, devComment.getKind());
            assertEquals(1, devComment.getDependsOnIndex());
            assertEquals("DEV (10h): Trocar o cálculo de ST", devComment.getComment());

            JiraPublishDtos.Step review = p.getSteps().get(3);
            assertEquals("Code Review", review.getIssueType());
            assertNull(review.getEstimateHours());

            JiraPublishDtos.Step qa = p.getSteps().get(4);
            assertEquals(12.0, qa.getEstimateHours());
            assertEquals("QA (12h): Testar com NF de devolução", p.getSteps().get(5).getComment());
            assertTrue(p.getWarnings().isEmpty());
        }

        @Test
        void semTemplatesParaOTipoDaPaiSoRenomeiaEAvisa() {
            when(roomRepository.findById("room-1")).thenReturn(Optional.of(room));
            when(templateRepository.findBySquadIdIgnoreCaseOrderByPositionAsc("DDWMISSI"))
                    .thenReturn(List.of(template("t4", "Só Manutenção", "DEV", "{pai}", null, "Manutenção")));

            JiraPublishDtos.Preview p = service.preview("room-1", "iss-1", "user-1", "USER");
            assertEquals(2, p.getSteps().size());
            assertEquals(JiraPublishStep.RENAME_PARENT, p.getSteps().get(0).getKind());
            // as notas da sala não se perdem: viram um comentário proposto na própria pai
            JiraPublishDtos.Step c = p.getSteps().get(1);
            assertEquals(JiraPublishStep.ADD_COMMENT, c.getKind());
            assertEquals("DDWMISSI-100", c.getTargetKey());
            assertEquals(null, c.getDependsOnIndex());
            assertTrue(c.getComment().startsWith("DEV (10h):" + '\n' + "Trocar o cálculo de ST"));
            assertTrue(c.getComment().contains("QA (12h):" + '\n'));
            assertTrue(p.getWarnings().stream().anyMatch(w -> w.contains("Nenhuma sub-tarefa configurada")));
        }

        @Test
        void tarefaSemChaveDoJiraDa400() {
            ((ObjectNode) room.getIssuesQueue().get(0)).remove("key");
            when(roomRepository.findById("room-1")).thenReturn(Optional.of(room));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.preview("room-1", "iss-1", "user-1", "USER"));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }

        @Test
        void quemNaoEstaNaSalaRecebe403() {
            when(roomRepository.findById("room-1")).thenReturn(Optional.of(room));
            when(participantRepository.existsById("room-1_outsider")).thenReturn(false);

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.preview("room-1", "iss-1", "outsider", "USER"));
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        }

        @Test
        void baralhoSemHorasAvisaEMandaSubsSemEstimativa() {
            room.setDeckType("fibonacci");
            when(roomRepository.findById("room-1")).thenReturn(Optional.of(room));
            configuredTemplates();

            JiraPublishDtos.Preview p = service.preview("room-1", "iss-1", "user-1", "USER");

            assertFalse(p.isHoursDeck());
            assertNull(p.getSteps().get(1).getEstimateHours());
            assertEquals("Tarefa fazer hoje", p.getSteps().get(0).getSummary(), "sem horas, sem sufixo");
            assertFalse(p.getWarnings().isEmpty());
        }
    }

    @Nested
    @DisplayName("guard rails na validação dos passos")
    class Validation {

        private JiraPublishDtos.Step step(String kind) {
            return JiraPublishDtos.Step.builder().kind(kind).build();
        }

        @Test
        void recusaAcaoQueNaoSejaRenomearCriarOuComentar() {
            JiraPublishDtos.Step s = step("DELETE_ISSUE");
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.validateSteps(List.of(s), "DDWMISSI-100", Set.of("codificação")));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
            assertTrue(ex.getReason().contains("não permitida"));
        }

        @Test
        void recusaTipoDeSubTarefaForaDaConfiguracaoDaSquad() {
            JiraPublishDtos.Step s = step(JiraPublishStep.CREATE_SUBTASK);
            s.setIssueType("Épico");
            s.setSummary("x");
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.validateSteps(List.of(s), "DDWMISSI-100", Set.of("codificação")));
            assertTrue(ex.getReason().contains("não está entre as sub-tarefas configuradas"));
        }

        @Test
        void comentarioSemDependenciaSoPodeIrNaPai() {
            JiraPublishDtos.Step c = step(JiraPublishStep.ADD_COMMENT);
            c.setComment("oi");
            c.setTargetKey("DDWMISSI-999"); // outra issue: proibido
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.validateSteps(List.of(c), "DDWMISSI-100", Set.of("codificação")));
            assertTrue(ex.getReason().contains("só pode ir na issue pai"));

            JiraPublishDtos.Step semAlvo = step(JiraPublishStep.ADD_COMMENT);
            semAlvo.setComment("oi");
            assertThrows(ResponseStatusException.class,
                    () -> service.validateSteps(List.of(semAlvo), "DDWMISSI-100", Set.of("codificação")));
        }

        @Test
        void comentarioNaPaiEhAceitoEMiraAPai() {
            JiraPublishDtos.Step c = step(JiraPublishStep.ADD_COMMENT);
            c.setComment("Issue de teste: só renomear e comentar.");
            c.setTargetKey("ddwmissi-100");
            List<JiraPublishStep> out = service.validateSteps(List.of(c), "DDWMISSI-100", Set.of());
            assertEquals(1, out.size());
            assertEquals("DDWMISSI-100", out.get(0).getTargetKey());
            assertEquals(null, out.get(0).getDependsOn());
            assertEquals("Issue de teste: só renomear e comentar.", out.get(0).getPayload().path("body").asText());
        }

        @Test
        void comentarioComDependenciaInvalidaDa400() {
            JiraPublishDtos.Step c = step(JiraPublishStep.ADD_COMMENT);
            c.setComment("oi");
            c.setDependsOnIndex(7);
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.validateSteps(List.of(c), "DDWMISSI-100", Set.of("codificação")));
            assertTrue(ex.getReason().contains("sub-tarefa criada neste envio"));
        }

        @Test
        void renomearSempreMiraAPaiMesmoQueOClienteMandeOutraChave() {
            JiraPublishDtos.Step r = step(JiraPublishStep.RENAME_PARENT);
            r.setSummary("Novo título");
            r.setTargetKey("OUTRO-1");
            List<JiraPublishStep> out = service.validateSteps(List.of(r), "DDWMISSI-100", Set.of());
            assertEquals("DDWMISSI-100", out.get(0).getTargetKey());
        }

        @Test
        void soUmRenomearPorEnvio() {
            JiraPublishDtos.Step r1 = step(JiraPublishStep.RENAME_PARENT); r1.setSummary("a");
            JiraPublishDtos.Step r2 = step(JiraPublishStep.RENAME_PARENT); r2.setSummary("b");
            assertThrows(ResponseStatusException.class,
                    () -> service.validateSteps(List.of(r1, r2), "DDWMISSI-100", Set.of()));
        }

        @Test
        void ligaOComentarioAoPassoDeCriacaoPeloIndice() {
            JiraPublishDtos.Step c1 = step(JiraPublishStep.CREATE_SUBTASK);
            c1.setIssueType("Codificação"); c1.setSummary("Sub"); c1.setEstimateHours(10.0);
            JiraPublishDtos.Step cm = step(JiraPublishStep.ADD_COMMENT);
            cm.setComment("DEV: fazer x"); cm.setDependsOnIndex(0);
            List<JiraPublishStep> out = service.validateSteps(List.of(c1, cm), "DDWMISSI-100", Set.of("codificação"));
            assertEquals(2, out.size());
            assertEquals(out.get(0).getId(), out.get(1).getDependsOn());
            assertEquals(10.0, out.get(0).getPayload().get("estimateHours").asDouble());
            assertNull(out.get(1).getTargetKey(), "alvo só é conhecido depois de criar a sub");
        }
    }

    @Nested
    @DisplayName("envio e fila")
    class Submit {

        private void creds() {
            UserJiraConfig cfg = new UserJiraConfig();
            cfg.setUserId("user-1");
            cfg.setToken("tok");
            cfg.setDomain("jira.empresa.com.br");
            when(userJiraConfigRepository.findById("user-1")).thenReturn(Optional.of(cfg));
        }

        @Test
        void tarefaJaPublicadaDa409() {
            when(roomRepository.findById("room-1")).thenReturn(Optional.of(room));
            creds();
            JiraPublishJob done = JiraPublishJob.builder().id("j0").status(JiraPublishJob.DONE).parentKey("DDWMISSI-100").build();
            when(jobRepository.findByRoomIdAndIssueIdOrderByCreatedAtDesc("room-1", "iss-1")).thenReturn(List.of(done));

            JiraPublishDtos.SubmitRequest req = new JiraPublishDtos.SubmitRequest("iss-1", List.of());
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.submit("room-1", req, "user-1", "USER"));
            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        }

        @Test
        void semTokenDoJiraDa400AntesDeGravarQualquerCoisa() {
            when(roomRepository.findById("room-1")).thenReturn(Optional.of(room));
            when(userJiraConfigRepository.findById("user-1")).thenReturn(Optional.empty());

            JiraPublishDtos.SubmitRequest req = new JiraPublishDtos.SubmitRequest("iss-1", List.of());
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.submit("room-1", req, "user-1", "USER"));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
            verify(jobRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("execução dos passos")
    class Run {

        private final JiraPublishService.Creds creds = new JiraPublishService.Creds("jira.empresa.com.br", "tok");

        private JiraPublishJob job() {
            return JiraPublishJob.builder().id("job-1").squadId("DDWMISSI").roomId("room-1").issueId("iss-1")
                    .parentKey("DDWMISSI-100").requestedBy("user-1").status(JiraPublishJob.PENDING).build();
        }

        private JiraPublishStep rename(String summary) {
            ObjectNode p = mapper.createObjectNode().put("summary", summary);
            return JiraPublishStep.builder().id("s-rename").jobId("job-1").position(0).kind(JiraPublishStep.RENAME_PARENT)
                    .targetKey("DDWMISSI-100").payload(p).status(JiraPublishStep.PENDING).attempts(0).build();
        }

        private JiraPublishStep create(String id, int pos, String type, String summary, Double hours) {
            ObjectNode p = mapper.createObjectNode().put("issueType", type).put("summary", summary);
            if (hours != null) p.put("estimateHours", hours);
            return JiraPublishStep.builder().id(id).jobId("job-1").position(pos).kind(JiraPublishStep.CREATE_SUBTASK)
                    .targetKey("DDWMISSI-100").payload(p).status(JiraPublishStep.PENDING).attempts(0).build();
        }

        private JiraPublishStep comment(String id, int pos, String dependsOn, String body) {
            ObjectNode p = mapper.createObjectNode().put("body", body);
            return JiraPublishStep.builder().id(id).jobId("job-1").position(pos).kind(JiraPublishStep.ADD_COMMENT)
                    .dependsOn(dependsOn).payload(p).status(JiraPublishStep.PENDING).attempts(0).build();
        }

        private ObjectNode issueWithSummary(String summary) {
            ObjectNode n = mapper.createObjectNode();
            n.putObject("fields").put("summary", summary);
            return n;
        }

        private ObjectNode existingSub(String key, String summary, String type) {
            ObjectNode n = mapper.createObjectNode().put("key", key);
            n.putObject("fields").put("summary", summary).putObject("issuetype").put("name", type);
            return n;
        }

        @Test
        void executaNaOrdemEComentaNaSubRecemCriada() {
            JiraPublishJob job = job();
            List<JiraPublishStep> steps = List.of(
                    rename("Tarefa (DEV 10h / QA 12h)"),
                    create("s-c1", 1, "Codificação", "DDWMISSI-100 - Codificação - Tarefa", 10.0),
                    comment("s-m1", 2, "s-c1", "DEV: fazer x"));
            when(jiraService.getIssue(eq("jira.empresa.com.br"), eq("tok"), eq("DDWMISSI-100"), anyList()))
                    .thenReturn(issueWithSummary("Tarefa"));
            when(jiraService.listSubtasks("jira.empresa.com.br", "tok", "DDWMISSI-100")).thenReturn(new ArrayList<>());
            when(jiraService.createSubtask("jira.empresa.com.br", "tok", "DDWMISSI", "DDWMISSI-100", "Codificação",
                    "DDWMISSI-100 - Codificação - Tarefa", 10.0)).thenReturn("DDWMISSI-101");
            when(jiraService.addComment("jira.empresa.com.br", "tok", "DDWMISSI-101", "DEV: fazer x")).thenReturn("777");

            service.run(job, steps, creds);

            assertEquals(JiraPublishJob.DONE, job.getStatus());
            assertNotNull(job.getFinishedAt());
            verify(jiraService).updateIssueSummary("jira.empresa.com.br", "tok", "DDWMISSI-100", "Tarefa (DEV 10h / QA 12h)");
            assertEquals("DDWMISSI-101", steps.get(1).getResultKey());
            assertEquals("DDWMISSI-101", steps.get(2).getTargetKey(), "comentário vai na sub criada, não na pai");
            assertEquals("777", steps.get(2).getResultKey());
            for (JiraPublishStep s : steps) assertEquals(JiraPublishStep.DONE, s.getStatus());
            verify(webSocketHandler, atLeastOnce()).broadcastEvent(eq("room-1"), eq(JiraPublishService.WS_EVENT), any());
        }

        @Test
        void naoRenomeiaSeOTituloJaEstaIgualENaoDuplicaSubExistente() {
            JiraPublishJob job = job();
            List<JiraPublishStep> steps = List.of(
                    rename("Tarefa (DEV 10h / QA 12h)"),
                    create("s-c1", 1, "Codificação", "DDWMISSI-100 - Codificação - Tarefa", 10.0));
            when(jiraService.getIssue(any(), any(), eq("DDWMISSI-100"), anyList()))
                    .thenReturn(issueWithSummary("Tarefa (DEV 10h / QA 12h)"));
            List<com.fasterxml.jackson.databind.JsonNode> existing = new ArrayList<>();
            existing.add(existingSub("DDWMISSI-150", "ddwmissi-100 - codificação - tarefa", "Codificação"));
            when(jiraService.listSubtasks(any(), any(), eq("DDWMISSI-100"))).thenReturn(existing);

            service.run(job, steps, creds);

            assertEquals(JiraPublishJob.DONE, job.getStatus());
            verify(jiraService, never()).updateIssueSummary(any(), any(), any(), any());
            verify(jiraService, never()).createSubtask(any(), any(), any(), any(), any(), any(), any());
            assertEquals("DDWMISSI-150", steps.get(1).getResultKey(), "reaproveita a sub que já existia");
        }

        @Test
        void paraNoPrimeiroErroEGuardaOMotivoNoPassoENoJob() {
            JiraPublishJob job = job();
            List<JiraPublishStep> steps = List.of(
                    rename("Tarefa (DEV 10h)"),
                    create("s-c1", 1, "Codificação", "Sub", 10.0),
                    comment("s-m1", 2, "s-c1", "DEV: x"));
            when(jiraService.getIssue(any(), any(), eq("DDWMISSI-100"), anyList())).thenReturn(issueWithSummary("Tarefa"));
            when(jiraService.listSubtasks(any(), any(), eq("DDWMISSI-100"))).thenReturn(new ArrayList<>());
            when(jiraService.createSubtask(any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new JiraService.JiraWriteException(400, "Não foi possível criar sub-tarefa em DDWMISSI-100 (400): issuetype: tipo inválido"));

            service.run(job, steps, creds);

            assertEquals(JiraPublishJob.ERROR, job.getStatus());
            assertTrue(job.getErrorMessage().contains("Passo 2"));
            assertTrue(job.getErrorMessage().contains("tipo inválido"));
            assertEquals(JiraPublishStep.DONE, steps.get(0).getStatus());
            assertEquals(JiraPublishStep.ERROR, steps.get(1).getStatus());
            assertEquals(1, steps.get(1).getAttempts());
            assertEquals(JiraPublishStep.PENDING, steps.get(2).getStatus(), "o comentário nem foi tentado");
            verify(jiraService, never()).addComment(any(), any(), any(), any());
        }

        @Test
        void retentativaContinuaDoPassoQueFalhouSemRefazerOsConcluidos() {
            JiraPublishJob job = job();
            job.setStatus(JiraPublishJob.ERROR);
            JiraPublishStep done = rename("Tarefa (DEV 10h)");
            done.setStatus(JiraPublishStep.DONE);
            done.setResultKey("DDWMISSI-100");
            JiraPublishStep failed = create("s-c1", 1, "Codificação", "Sub", 10.0);
            failed.setStatus(JiraPublishStep.ERROR);
            failed.setErrorMessage("antes");
            failed.setAttempts(1);
            JiraPublishStep pending = comment("s-m1", 2, "s-c1", "DEV: x");
            List<JiraPublishStep> steps = List.of(done, failed, pending);

            when(roomRepository.findById("room-1")).thenReturn(Optional.of(room));
            when(jobRepository.findById("job-1")).thenReturn(Optional.of(job));
            when(stepRepository.findByJobIdOrderByPositionAsc("job-1")).thenReturn(steps);
            when(stepRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
            UserJiraConfig cfg = new UserJiraConfig();
            cfg.setUserId("user-1"); cfg.setToken("tok"); cfg.setDomain("jira.empresa.com.br");
            when(userJiraConfigRepository.findById("user-1")).thenReturn(Optional.of(cfg));
            when(jiraService.listSubtasks(any(), any(), eq("DDWMISSI-100"))).thenReturn(new ArrayList<>());
            when(jiraService.createSubtask(any(), any(), any(), any(), any(), any(), any())).thenReturn("DDWMISSI-102");
            when(jiraService.addComment(any(), any(), eq("DDWMISSI-102"), any())).thenReturn("1");

            JiraPublishDtos.Job dto = service.retry("room-1", "job-1", "user-1", "USER");

            assertEquals(JiraPublishJob.DONE, dto.getStatus());
            verify(jiraService, never()).getIssue(any(), any(), any(), anyList());
            verify(jiraService, never()).updateIssueSummary(any(), any(), any(), any());
            assertEquals(2, failed.getAttempts());
            assertEquals("DDWMISSI-102", pending.getTargetKey());
        }

        @Test
        void retentativaDeEnvioConcluidoDa409() {
            JiraPublishJob job = job();
            job.setStatus(JiraPublishJob.DONE);
            when(roomRepository.findById("room-1")).thenReturn(Optional.of(room));
            when(jobRepository.findById("job-1")).thenReturn(Optional.of(job));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.retry("room-1", "job-1", "user-1", "USER"));
            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        }
    }

    @Nested
    @DisplayName("formatação")
    class Formatting {

        @Test
        void tiraOSufixoDeEstimativaExistente() {
            assertEquals("Tarefa fazer hoje", JiraPublishService.stripEstimateSuffix("Tarefa fazer hoje (DEV 10h / QA 12h)"));
            assertEquals("Tarefa fazer hoje", JiraPublishService.stripEstimateSuffix("Tarefa fazer hoje (DEV 8h)"));
            assertEquals("Tarefa fazer hoje", JiraPublishService.stripEstimateSuffix("Tarefa fazer hoje (QA 1,5h) "));
            assertEquals("Ajuste (fiscal)", JiraPublishService.stripEstimateSuffix("Ajuste (fiscal)"), "parêntese comum fica");
        }

        @Test
        void montaOSufixoSoComOQueTemHoras() {
            assertEquals(" (DEV 10h / QA 12h)", JiraPublishService.estimateSuffix(10.0, 12.0));
            assertEquals(" (DEV 1,5h)", JiraPublishService.estimateSuffix(1.5, null));
            assertEquals(" (QA 4h)", JiraPublishService.estimateSuffix(null, 4.0));
            assertEquals("", JiraPublishService.estimateSuffix(null, null));
            assertEquals(" (DEV 10h)", JiraPublishService.estimateSuffix(10.0, 0.0));
            assertEquals("", JiraPublishService.estimateSuffix(0.0, 0.0));
        }

        @Test
        void leHorasDoBaralho() {
            assertEquals(10.0, JiraPublishService.parseHours("10"));
            assertEquals(1.5, JiraPublishService.parseHours("1,5h"));
            assertNull(JiraPublishService.parseHours("?"));
            assertNull(JiraPublishService.parseHours("☕"));
            assertNull(JiraPublishService.parseHours(null));
        }

        @Test
        void substituiVariaveisDoPadrao() {
            String out = JiraPublishService.render("{pai} - {tipo} - {resumo} [{horas}] {papel}: {notas}",
                    JiraPublishService.vars("DDW-1", "Resumo", "Codificação", "DEV", 10.0, "fazer x"));
            assertEquals("DDW-1 - Codificação - Resumo [10h] DEV: fazer x", out);
        }
    }
}
