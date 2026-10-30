package com.agilespace.backend.service;

import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.domain.SquadIssueSnapshot;
import com.agilespace.backend.domain.UserJiraConfig;
import com.agilespace.backend.repository.SquadIssueSnapshotRepository;
import com.agilespace.backend.repository.UserJiraConfigRepository;
import com.agilespace.backend.service.JiraDateFieldResolver.FieldRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SquadJiraDateFieldsServiceTest {

    private SquadService squadService;
    private SquadIssueSnapshotRepository snapshots;
    private UserJiraConfigRepository userCfg;
    private JiraDateFieldResolver resolver;
    private SquadJiraDateFieldsService service;

    @BeforeEach
    void setUp() {
        squadService = mock(SquadService.class);
        snapshots = mock(SquadIssueSnapshotRepository.class);
        userCfg = mock(UserJiraConfigRepository.class);
        resolver = mock(JiraDateFieldResolver.class);
        service = new SquadJiraDateFieldsService(squadService, snapshots, userCfg, resolver);
        when(snapshots.findBySquadId("sq")).thenReturn(List.of());
    }

    private void connected() {
        when(userCfg.findById("u1")).thenReturn(Optional.of(UserJiraConfig.builder().userId("u1").token("tk").domain("jira.x.com").build()));
    }

    private JiraDateFieldResolver.Resolution res(List<FieldRef> s, List<FieldRef> e) {
        return new JiraDateFieldResolver.Resolution(s, e, Map.of("customfield_1", "Data e Hora de Início", "customfield_2", "Data e Hora de Fim"));
    }

    @Test
    void autodetectaQuandoCadaNomeTemUmCandidato() {
        connected();
        when(squadService.getSquad("sq")).thenReturn(Optional.of(Squad.builder().id("sq").build()));
        when(resolver.resolve("jira.x.com", "tk")).thenReturn(res(
                List.of(new FieldRef("customfield_1", "Data e Hora de Início")), List.of(new FieldRef("customfield_2", "Data e Hora de Fim"))));
        SquadIssueSnapshot ok = SquadIssueSnapshot.builder().targetStart("2026-10-20").targetEnd("2026-10-21").datesAreInferred(false).build();
        SquadIssueSnapshot inf = SquadIssueSnapshot.builder().targetStart("2026-10-01").targetEnd("2026-10-02").datesAreInferred(true).build();
        when(snapshots.findBySquadId("sq")).thenReturn(List.of(ok, inf));

        var r = service.get("sq", "u1");

        assertEquals("customfield_1", r.startFieldId());
        assertEquals("customfield_2", r.endFieldId());
        assertEquals("Data e Hora de Início", r.startFieldName());
        assertTrue(r.autoDetected());
        assertNull(r.warning());
        assertEquals(2, r.sample().total());
        assertEquals(1, r.sample().startFilled());
        assertEquals(1, r.sample().endFilled());
    }

    @Test
    void nomeDuplicadoNaoEscolheEAvisa() {
        connected();
        when(squadService.getSquad("sq")).thenReturn(Optional.of(Squad.builder().id("sq").build()));
        when(resolver.resolve(anyString(), anyString())).thenReturn(res(
                List.of(new FieldRef("customfield_1", "Data e Hora de Início"), new FieldRef("customfield_9", "Data e Hora de Início")),
                List.of(new FieldRef("customfield_2", "Data e Hora de Fim"))));

        var r = service.get("sq", "u1");

        assertNull(r.startFieldId());
        assertEquals(2, r.candidates().start().size());
        assertEquals("customfield_2", r.endFieldId());
        assertTrue(r.warning().contains("mais de um campo"));
    }

    @Test
    void configuradoDaSquadVenceEOAutoDetectedFicaFalso() {
        connected();
        when(squadService.getSquad("sq")).thenReturn(Optional.of(Squad.builder().id("sq").jiraStartField("customfield_1").jiraEndField("customfield_2").build()));
        when(resolver.resolve(anyString(), anyString())).thenReturn(res(
                List.of(new FieldRef("customfield_1", "Data e Hora de Início"), new FieldRef("customfield_9", "Data e Hora de Início")), List.of()));

        var r = service.get("sq", "u1");

        assertEquals("customfield_1", r.startFieldId());
        assertEquals("Data e Hora de Fim", r.endFieldName());
        assertFalse(r.autoDetected());
    }

    @Test
    void jiraNaoConectadoDevolveNulosEAvisoSemErro() {
        when(squadService.getSquad("sq")).thenReturn(Optional.empty());
        when(userCfg.findById("u1")).thenReturn(Optional.empty());

        var r = service.get("sq", "u1");

        assertNull(r.startFieldId());
        assertTrue(r.candidates().start().isEmpty());
        assertTrue(r.warning().contains("Jira não conectado"));
        verifyNoInteractions(resolver);
    }

    @Test
    void falhaDoFieldDevolveAvisoEmPortugues() {
        connected();
        when(squadService.getSquad("sq")).thenReturn(Optional.of(Squad.builder().id("sq").build()));
        when(resolver.resolve(anyString(), anyString())).thenThrow(new JiraDateFieldResolver.LookupException("O Jira não listou os campos (status 500)."));

        var r = service.get("sq", "u1");

        assertNull(r.startFieldId());
        assertEquals("O Jira não listou os campos (status 500).", r.warning());
    }

    @Test
    void updateGravaIdsValidosEVazioVoltaParaAutodeteccao() {
        connected();
        when(squadService.saveJiraDateFields(eq("sq"), any(), any())).thenReturn(Squad.builder().id("sq").build());
        when(resolver.resolve(anyString(), anyString())).thenReturn(res(List.of(), List.of()));

        service.update("sq", "u1", " customfield_12345 ", "  ");

        verify(squadService).saveJiraDateFields("sq", "customfield_12345", null);
    }

    @Test
    void updateRejeitaIdInvalido() {
        var ex = assertThrows(ResponseStatusException.class, () -> service.update("sq", "u1", "campo qualquer", null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertThrows(ResponseStatusException.class, () -> service.update("sq", "u1", null, "customfield_1; DROP"));
        verify(squadService, never()).saveJiraDateFields(any(), any(), any());
    }

    @Test
    void camposConhecidosSaoAceitos() {
        assertEquals("duedate", SquadJiraDateFieldsService.validate("duedate", "fim"));
        assertNull(SquadJiraDateFieldsService.validate(null, "fim"));
    }
}
