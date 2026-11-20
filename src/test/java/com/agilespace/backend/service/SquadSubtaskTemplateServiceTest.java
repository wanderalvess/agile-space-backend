package com.agilespace.backend.service;

import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.domain.SquadSubtaskTemplate;
import com.agilespace.backend.domain.UserJiraConfig;
import com.agilespace.backend.repository.SquadRepository;
import com.agilespace.backend.repository.SquadSubtaskTemplateRepository;
import com.agilespace.backend.repository.UserJiraConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SquadSubtaskTemplateService - sub-tarefas do time por tipo de issue pai")
class SquadSubtaskTemplateServiceTest {

    @Mock SquadSubtaskTemplateRepository repository;
    @Mock SquadRepository squadRepository;
    @Mock UserJiraConfigRepository userJiraConfigRepository;
    @Mock JiraService jiraService;
    @InjectMocks SquadSubtaskTemplateService service;

    private final ObjectMapper mapper = new ObjectMapper();

    private SquadSubtaskTemplate in(String type, String role, String title) {
        return SquadSubtaskTemplate.builder().jiraIssueTypeName(type).role(role).titleTemplate(title).build();
    }

    @Test
    void substituiTudoNumerandoAPosicaoPelaOrdemRecebida() {
        when(repository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<SquadSubtaskTemplate> saved = service.replaceAll("DDWMISSI", List.of(
                in("Codificação", "dev", "{pai} - {tipo}"),
                in("Code Review", null, "{pai} - {tipo}")));

        verify(repository).deleteBySquadIdIgnoreCase("DDWMISSI");
        assertEquals(2, saved.size());
        assertEquals(0, saved.get(0).getPosition());
        assertEquals(1, saved.get(1).getPosition());
        assertEquals("DEV", saved.get(0).getRole(), "papel normalizado em maiúsculas");
        assertEquals("NONE", saved.get(1).getRole(), "sem papel = NONE");
        assertEquals("DDWMISSI", saved.get(0).getSquadId());
        assertNotNull(saved.get(0).getId());
        assertTrue(saved.get(0).getActive());
    }

    @Test
    void recusaSubSemNomeDoTipoNoJira() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.replaceAll("DDWMISSI", List.of(in("  ", "DEV", "{pai}"))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(repository, never()).deleteBySquadIdIgnoreCase(any());
    }

    @Test
    void recusaPapelDesconhecido() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.replaceAll("DDWMISSI", List.of(in("Codificação", "PO", "{pai}"))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void listaTiposDoJiraComOTokenDeQuemPedeEProjetoDaSquad() {
        Squad squad = new Squad();
        squad.setId("DDWMISSI");
        squad.setJiraProjectKey("DDWMISSI");
        squad.setJiraDomain("jira.squad.com");
        when(squadRepository.findById("DDWMISSI")).thenReturn(Optional.of(squad));
        UserJiraConfig cfg = new UserJiraConfig();
        cfg.setToken("tok");
        cfg.setDomain("jira.user.com");
        when(userJiraConfigRepository.findById("u1")).thenReturn(Optional.of(cfg));
        ObjectNode t = mapper.createObjectNode().put("id", "5").put("name", "Codificação").put("subtask", true);
        when(jiraService.listProjectIssueTypes("jira.user.com", "tok", "DDWMISSI")).thenReturn(List.of(t));

        List<Map<String, Object>> out = service.listJiraIssueTypes("DDWMISSI", "u1");

        assertEquals(1, out.size());
        assertEquals("Codificação", out.get(0).get("name"));
        assertEquals(true, out.get(0).get("subtask"));
    }

    @Test
    void semTokenDoJiraOrientaAConfigurarOPerfil() {
        when(squadRepository.findById("DDWMISSI")).thenReturn(Optional.of(new Squad()));
        when(userJiraConfigRepository.findById("u1")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.listJiraIssueTypes("DDWMISSI", "u1"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("token do Jira"));
    }
}
