package com.agilespace.backend.service;

import com.agilespace.backend.domain.*;
import com.agilespace.backend.dto.JiraPublishDtos;
import com.agilespace.backend.repository.*;
import com.agilespace.backend.websocket.PokerWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("JiraPublishService - gestão da fila: log, descarte, conferência e retomada")
class JiraPublishQueueTest {

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
    private final JiraPublishService.Creds creds = new JiraPublishService.Creds("jira.empresa.com.br", "tok");
    private JiraPublishService service;

    @BeforeEach
    void setup() {
        service = new JiraPublishService(jobRepository, stepRepository, eventRepository, userRepository, squadAccessService,
                templateRepository, roomRepository, participantRepository, squadRepository, userJiraConfigRepository,
                jiraService, webSocketHandler, mapper);
    }

    // ------------------------------------------------------------------ montagem

    private JiraPublishJob job(String status, LocalDateTime updatedAt) {
        return JiraPublishJob.builder().id("job-1").squadId("DDWMISSI").roomId("room-1").issueId("iss-1")
                .parentKey("DDWMISSI-100").parentSummary("Tarefa").requestedBy("user-1").status(status)
                .createdAt(updatedAt).updatedAt(updatedAt).build();
    }

    private JiraPublishStep step(String id, int pos, String kind, String status, ObjectNode payload) {
        return JiraPublishStep.builder().id(id).jobId("job-1").position(pos).kind(kind).status(status).attempts(0).payload(payload).build();
    }

    private JiraPublishStep rename(String status, String summary) {
        JiraPublishStep s = step("s0", 0, JiraPublishStep.RENAME_PARENT, status, mapper.createObjectNode().put("summary", summary));
        s.setTargetKey("DDWMISSI-100");
        return s;
    }

    private JiraPublishStep comment(String status, String body) {
        JiraPublishStep s = step("s1", 1, JiraPublishStep.ADD_COMMENT, status, mapper.createObjectNode().put("body", body));
        s.setTargetKey("DDWMISSI-100");
        return s;
    }

    private JsonNode issueWithSummary(String summary) {
        ObjectNode n = mapper.createObjectNode();
        n.putObject("fields").put("summary", summary);
        return n;
    }

    private JsonNode jiraComment(String id, String body) {
        return mapper.createObjectNode().put("id", id).put("body", body);
    }

    private List<String> loggedActions() {
        ArgumentCaptor<JiraPublishEvent> cap = ArgumentCaptor.forClass(JiraPublishEvent.class);
        verify(eventRepository, atLeast(0)).save(cap.capture());
        return cap.getAllValues().stream().map(JiraPublishEvent::getAction).toList();
    }

    // ------------------------------------------------------------------ log de tentativas

    @Test
    void execucaoRegistraCadaPassoEOFimNoLog() {
        JiraPublishJob job = job(JiraPublishJob.PENDING, LocalDateTime.now());
        List<JiraPublishStep> steps = List.of(rename(JiraPublishStep.PENDING, "Tarefa (DEV 6h)"), comment(JiraPublishStep.PENDING, "Apoio docker"));
        when(jiraService.getIssue(any(), any(), eq("DDWMISSI-100"), anyList())).thenReturn(issueWithSummary("Tarefa"));
        when(jiraService.listComments(any(), any(), eq("DDWMISSI-100"))).thenReturn(new ArrayList<>());
        when(jiraService.addComment(any(), any(), eq("DDWMISSI-100"), eq("Apoio docker"))).thenReturn("555");

        service.run(job, steps, creds, "user-2");

        assertEquals(List.of("STEP_DONE", "STEP_DONE", "JOB_DONE"), loggedActions());
        ArgumentCaptor<JiraPublishEvent> cap = ArgumentCaptor.forClass(JiraPublishEvent.class);
        verify(eventRepository, times(3)).save(cap.capture());
        assertEquals("user-2", cap.getAllValues().get(0).getUserId(), "o log guarda quem disparou a execução");
        assertEquals("555", cap.getAllValues().get(1).getResultKey());
        assertTrue(cap.getAllValues().get(0).getMessage().contains("alterado"));
    }

    @Test
    void erroDePassoVaiParaOLogComAMensagemDoJira() {
        JiraPublishJob job = job(JiraPublishJob.PENDING, LocalDateTime.now());
        List<JiraPublishStep> steps = List.of(rename(JiraPublishStep.PENDING, "Novo"), comment(JiraPublishStep.PENDING, "x"));
        when(jiraService.getIssue(any(), any(), eq("DDWMISSI-100"), anyList()))
                .thenThrow(new JiraService.JiraWriteException(403, "sem permissão (403)"));

        service.run(job, steps, creds, "user-1");

        assertEquals(JiraPublishJob.ERROR, job.getStatus());
        assertEquals(List.of("STEP_ERROR"), loggedActions());
        ArgumentCaptor<JiraPublishEvent> cap = ArgumentCaptor.forClass(JiraPublishEvent.class);
        verify(eventRepository).save(cap.capture());
        assertEquals(Boolean.FALSE, cap.getValue().getOk());
        assertEquals("sem permissão (403)", cap.getValue().getMessage());
        assertEquals(0, cap.getValue().getStepPosition());
    }

    @Test
    void falhaAoGravarOLogNaoDerrubaOEnvio() {
        JiraPublishJob job = job(JiraPublishJob.PENDING, LocalDateTime.now());
        List<JiraPublishStep> steps = List.of(rename(JiraPublishStep.PENDING, "Tarefa"));
        when(jiraService.getIssue(any(), any(), eq("DDWMISSI-100"), anyList())).thenReturn(issueWithSummary("Tarefa"));
        when(eventRepository.save(any())).thenThrow(new RuntimeException("banco fora"));

        service.run(job, steps, creds, "user-1");

        assertEquals(JiraPublishJob.DONE, job.getStatus());
    }

    @Test
    void comentarioJaPresenteNaoEhRepetido() {
        JiraPublishJob job = job(JiraPublishJob.PENDING, LocalDateTime.now());
        List<JiraPublishStep> steps = List.of(comment(JiraPublishStep.PENDING, "Apoio docker"));
        when(jiraService.listComments(any(), any(), eq("DDWMISSI-100")))
                .thenReturn(new ArrayList<>(List.of(jiraComment("42", "Apoio docker\r\n"))));

        service.run(job, steps, creds, "user-1");

        verify(jiraService, never()).addComment(any(), any(), any(), any());
        assertEquals("42", steps.get(0).getResultKey());
        assertEquals(JiraPublishJob.DONE, job.getStatus());
    }

    @Test
    void listaOLogComNomeDeQuemAgiu() {
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(job(JiraPublishJob.ERROR, LocalDateTime.now())));
        when(squadAccessService.matchesSquad("DDWMISSI", "user-1", "USER")).thenReturn(true);
        when(eventRepository.findByJobIdOrderByCreatedAtAsc(eq("job-1"), any(Pageable.class))).thenReturn(List.of(
                JiraPublishEvent.builder().id("e1").jobId("job-1").action("STEP_ERROR").ok(false).message("403").userId("user-1")
                        .createdAt(LocalDateTime.now()).build()));
        User u = new User();
        u.setId("user-1");
        u.setName("Wanderson");
        when(userRepository.findAllById(any())).thenReturn(List.of(u));

        List<JiraPublishDtos.Event> events = service.listEvents("job-1", "user-1", "USER");

        assertEquals(1, events.size());
        assertEquals("Wanderson", events.get(0).getUserName());
    }

    @Test
    void logSoParaMembroDaSquad() {
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(job(JiraPublishJob.ERROR, LocalDateTime.now())));
        when(squadAccessService.matchesSquad("DDWMISSI", "intruso", "USER")).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.listEvents("job-1", "intruso", "USER"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    // ------------------------------------------------------------------ fila da squad

    @Test
    void filaDaSquadTrazNomeSalaEDominio() {
        JiraPublishJob j = job(JiraPublishJob.ERROR, LocalDateTime.now());
        when(squadAccessService.matchesSquad("DDWMISSI", "user-1", "USER")).thenReturn(true);
        when(jobRepository.findBySquadIdIgnoreCaseOrderByCreatedAtDesc(eq("DDWMISSI"), any(Pageable.class))).thenReturn(List.of(j));
        when(stepRepository.findByJobIdInOrderByJobIdAscPositionAsc(List.of("job-1")))
                .thenReturn(List.of(rename(JiraPublishStep.ERROR, "Tarefa (DEV 6h)")));
        User u = new User();
        u.setId("user-1");
        u.setName("Wanderson");
        when(userRepository.findAllById(any())).thenReturn(List.of(u));
        PokerRoom room = new PokerRoom();
        room.setId("room-1");
        room.setTitle("Refinamento de outubro");
        when(roomRepository.findAllById(any())).thenReturn(List.of(room));
        UserJiraConfig cfg = new UserJiraConfig();
        cfg.setDomain("jiraproducao.totvs.com.br");
        when(userJiraConfigRepository.findById("user-1")).thenReturn(Optional.of(cfg));

        JiraPublishDtos.SquadQueue q = service.listForSquad("DDWMISSI", "user-1", "USER");

        assertEquals("jiraproducao.totvs.com.br", q.getJiraDomain());
        assertEquals(1, q.getJobs().size());
        assertEquals("Wanderson", q.getJobs().get(0).getRequestedByName());
        assertEquals("Refinamento de outubro", q.getJobs().get(0).getRoomTitle());
        assertEquals(1, q.getJobs().get(0).getSteps().size());
    }

    @Test
    void filaDaSquadNegaQuemNaoEMembro() {
        when(squadAccessService.matchesSquad("DDWMISSI", "intruso", "USER")).thenReturn(false);
        assertThrows(ResponseStatusException.class, () -> service.listForSquad("DDWMISSI", "intruso", "USER"));
        verify(jobRepository, never()).findBySquadIdIgnoreCaseOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void envioEmAndamentoHaMuitoTempoApareceComoTravado() {
        JiraPublishJob stale = job(JiraPublishJob.RUNNING, LocalDateTime.now().minusMinutes(JiraPublishService.STALE_MINUTES + 5));
        JiraPublishJob fresh = job(JiraPublishJob.RUNNING, LocalDateTime.now());
        assertTrue(service.toDto(stale, List.of()).isStale());
        assertFalse(service.toDto(fresh, List.of()).isStale());
        assertFalse(service.toDto(job(JiraPublishJob.ERROR, LocalDateTime.now().minusDays(2)), List.of()).isStale());
    }

    // ------------------------------------------------------------------ descartar

    @Test
    void donoDescartaEnvioComErroESaiDaFilaAvisandoASala() {
        JiraPublishJob j = job(JiraPublishJob.ERROR, LocalDateTime.now());
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(j));
        when(squadAccessService.matchesSquad("DDWMISSI", "user-1", "USER")).thenReturn(true);
        when(stepRepository.findByJobIdOrderByPositionAsc("job-1")).thenReturn(List.of(rename(JiraPublishStep.DONE, "x"), comment(JiraPublishStep.ERROR, "y")));

        service.discard("job-1", "user-1", "USER");

        verify(jobRepository).delete(j);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(webSocketHandler).broadcastEvent(eq("room-1"), eq(JiraPublishService.WS_EVENT), payload.capture());
        assertEquals("DISCARDED", ((JiraPublishDtos.Job) payload.getValue()).getStatus());
        verifyNoInteractions(jiraService);
    }

    @Test
    void outroMembroSemGestaoNaoDescarta() {
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(job(JiraPublishJob.ERROR, LocalDateTime.now())));
        when(squadAccessService.matchesSquad("DDWMISSI", "user-9", "USER")).thenReturn(true);
        when(squadAccessService.canManageSquad("DDWMISSI", "user-9", "USER")).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.discard("job-1", "user-9", "USER"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(jobRepository, never()).delete(any());
    }

    @Test
    void liderancaDescartaEnvioDeOutraPessoa() {
        JiraPublishJob j = job(JiraPublishJob.ERROR, LocalDateTime.now());
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(j));
        when(squadAccessService.matchesSquad("DDWMISSI", "lider", "LEAD")).thenReturn(true);
        when(squadAccessService.canManageSquad("DDWMISSI", "lider", "LEAD")).thenReturn(true);
        when(stepRepository.findByJobIdOrderByPositionAsc("job-1")).thenReturn(List.of());

        service.discard("job-1", "lider", "LEAD");

        verify(jobRepository).delete(j);
    }

    @Test
    void naoDescartaEnvioConcluidoNemEmAndamento() {
        when(squadAccessService.matchesSquad("DDWMISSI", "user-1", "USER")).thenReturn(true);
        when(jobRepository.findById("job-1"))
                .thenReturn(Optional.of(job(JiraPublishJob.DONE, LocalDateTime.now())))
                .thenReturn(Optional.of(job(JiraPublishJob.RUNNING, LocalDateTime.now())));

        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> service.discard("job-1", "user-1", "USER")).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> service.discard("job-1", "user-1", "USER")).getStatusCode());
        verify(jobRepository, never()).delete(any());
    }

    @Test
    void descartaEnvioTravadoEmAndamento() {
        JiraPublishJob j = job(JiraPublishJob.RUNNING, LocalDateTime.now().minusMinutes(JiraPublishService.STALE_MINUTES + 1));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(j));
        when(squadAccessService.matchesSquad("DDWMISSI", "user-1", "USER")).thenReturn(true);
        when(stepRepository.findByJobIdOrderByPositionAsc("job-1")).thenReturn(List.of());

        service.discard("job-1", "user-1", "USER");

        verify(jobRepository).delete(j);
    }

    // ------------------------------------------------------------------ retomar pela tela da squad

    @Test
    void retomadaPelaTelaDaSquadUsaOTokenDeQuemClicaERegistraNoLog() {
        JiraPublishJob j = job(JiraPublishJob.ERROR, LocalDateTime.now());
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(j));
        when(squadAccessService.matchesSquad("DDWMISSI", "user-2", "USER")).thenReturn(true);
        UserJiraConfig cfg = new UserJiraConfig();
        cfg.setDomain("jira.empresa.com.br");
        cfg.setToken("tok");
        when(userJiraConfigRepository.findById("user-2")).thenReturn(Optional.of(cfg));
        JiraPublishStep failed = rename(JiraPublishStep.ERROR, "Tarefa");
        failed.setErrorMessage("403");
        when(stepRepository.findByJobIdOrderByPositionAsc("job-1")).thenReturn(List.of(failed));
        when(stepRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));
        when(jiraService.getIssue(eq("jira.empresa.com.br"), eq("tok"), eq("DDWMISSI-100"), anyList())).thenReturn(issueWithSummary("Tarefa"));

        JiraPublishDtos.Job out = service.retryJob("job-1", "user-2", "USER");

        assertEquals(JiraPublishJob.DONE, j.getStatus());
        assertEquals(JiraPublishStep.DONE, failed.getStatus());
        assertEquals(List.of("RETRY", "STEP_DONE", "JOB_DONE"), loggedActions());
        assertNotNull(out);
    }

    @Test
    void naoRetomaEnvioEmAndamentoRecenteMasRetomaOTravado() {
        when(squadAccessService.matchesSquad("DDWMISSI", "user-1", "USER")).thenReturn(true);
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(job(JiraPublishJob.RUNNING, LocalDateTime.now())));

        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> service.retryJob("job-1", "user-1", "USER")).getStatusCode());
    }

    // ------------------------------------------------------------------ conferir no Jira

    @Test
    void conferenciaConfirmaTituloSubEComentarioSemEscreverNada() {
        JiraPublishJob j = job(JiraPublishJob.DONE, LocalDateTime.now());
        JiraPublishStep sub = step("s1", 1, JiraPublishStep.CREATE_SUBTASK, JiraPublishStep.DONE, mapper.createObjectNode().put("summary", "Cod"));
        sub.setResultKey("DDWMISSI-101");
        JiraPublishStep com = comment(JiraPublishStep.DONE, "Apoio docker");
        com.setPosition(2);
        configuraConferencia(j, List.of(rename(JiraPublishStep.DONE, "Tarefa (DEV 6h)"), sub, com));
        when(jiraService.getIssue(any(), any(), eq("DDWMISSI-100"), anyList())).thenReturn(issueWithSummary("Tarefa (DEV 6h)"));
        when(jiraService.listSubtasks(any(), any(), eq("DDWMISSI-100"))).thenReturn(List.of(mapper.createObjectNode().put("key", "DDWMISSI-101")));
        when(jiraService.listComments(any(), any(), eq("DDWMISSI-100"))).thenReturn(List.of(jiraComment("9", "Apoio docker")));

        JiraPublishDtos.VerifyResult r = service.verify("job-1", "user-1", "USER");

        assertTrue(r.isAllOk());
        assertEquals(3, r.getConfirmed());
        assertEquals(3, r.getTotal());
        assertEquals(List.of("VERIFIED"), loggedActions());
        verify(jiraService, never()).addComment(any(), any(), any(), any());
        verify(jiraService, never()).updateIssueSummary(any(), any(), any(), any());
        verify(jiraService, never()).createSubtask(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void conferenciaApontaTituloAlteradoEPassoNaoEnviado() {
        JiraPublishJob j = job(JiraPublishJob.ERROR, LocalDateTime.now());
        configuraConferencia(j, List.of(rename(JiraPublishStep.DONE, "Tarefa (DEV 6h)"), comment(JiraPublishStep.ERROR, "Apoio")));
        when(jiraService.getIssue(any(), any(), eq("DDWMISSI-100"), anyList())).thenReturn(issueWithSummary("Outro título"));

        JiraPublishDtos.VerifyResult r = service.verify("job-1", "user-1", "USER");

        assertFalse(r.isAllOk());
        assertEquals(0, r.getConfirmed());
        assertEquals(1, r.getTotal(), "só conta o que estava concluído");
        assertEquals("DIVERGENT", r.getChecks().get(0).getStatus());
        assertTrue(r.getChecks().get(0).getDetail().contains("Outro título"));
        assertEquals("NOT_SENT", r.getChecks().get(1).getStatus());
    }

    @Test
    void conferenciaComJiraForaDoArVira502ComMotivo() {
        JiraPublishJob j = job(JiraPublishJob.DONE, LocalDateTime.now());
        configuraConferencia(j, List.of(rename(JiraPublishStep.DONE, "Tarefa")));
        when(jiraService.getIssue(any(), any(), eq("DDWMISSI-100"), anyList())).thenThrow(new JiraService.JiraWriteException(401, "token inválido"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.verify("job-1", "user-1", "USER"));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
        assertTrue(ex.getReason().contains("token inválido"));
        verify(eventRepository, never()).save(any());
    }

    private void configuraConferencia(JiraPublishJob j, List<JiraPublishStep> steps) {
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(j));
        when(squadAccessService.matchesSquad("DDWMISSI", "user-1", "USER")).thenReturn(true);
        UserJiraConfig cfg = new UserJiraConfig();
        cfg.setDomain("jira.empresa.com.br");
        cfg.setToken("tok");
        when(userJiraConfigRepository.findById("user-1")).thenReturn(Optional.of(cfg));
        when(stepRepository.findByJobIdOrderByPositionAsc("job-1")).thenReturn(steps);
    }
}
