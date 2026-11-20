package com.agilespace.backend.service;

import com.agilespace.backend.domain.SquadPokerTemplate;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.SquadPokerTemplateRepository;
import com.agilespace.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SquadPokerTemplateService - templates de sessão do Poker por squad")
class SquadPokerTemplateServiceTest {

    @Mock SquadPokerTemplateRepository repository;
    @Mock UserRepository userRepository;
    @InjectMocks SquadPokerTemplateService service;

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) {
        try { return mapper.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private SquadPokerTemplate stored(String createdBy) {
        return SquadPokerTemplate.builder().id("t1").squadId("DDW").name("Refino").deckType("hours")
                .settings(json("{}")).backlog(json("[]")).createdBy(createdBy).build();
    }

    @Test
    @DisplayName("salvar: limpa o nome, guarda quem salvou e só título/link/tipo das tarefas")
    void createSanitiza() {
        when(repository.countBySquadIdIgnoreCase("DDW")).thenReturn(0L);
        User u = new User();
        u.setName("Ana");
        when(userRepository.findById("u1")).thenReturn(Optional.of(u));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        SquadPokerTemplate in = SquadPokerTemplate.builder().name("  Refino semanal  ").deckType("hours")
                .settings(json("{\"refinementNotes\":true}"))
                .backlog(json("[{\"title\":\" T1 \",\"jiraLink\":\"https://j/ABC-1\",\"extra\":1},{\"title\":\"  \"}]"))
                .createdBy("outra-pessoa").build();
        SquadPokerTemplate out = service.create("DDW", in, "u1");

        assertEquals("Refino semanal", out.getName());
        assertEquals("u1", out.getCreatedBy());
        assertEquals("Ana", out.getCreatedByName());
        assertEquals("DDW", out.getSquadId());
        assertNotNull(out.getId());
        assertTrue(out.getSettings().path("refinementNotes").asBoolean());
        assertEquals(1, out.getBacklog().size());
        assertEquals("T1", out.getBacklog().get(0).path("title").asText());
        assertFalse(out.getBacklog().get(0).has("extra"));
    }

    @Test
    void createSemNomeFalha() {
        when(repository.countBySquadIdIgnoreCase("DDW")).thenReturn(0L);
        SquadPokerTemplate in = SquadPokerTemplate.builder().name(" ").deckType("hours").build();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.create("DDW", in, "u1"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(repository, never()).save(any());
    }

    @Test
    void createNoLimiteDaSquadFalha() {
        when(repository.countBySquadIdIgnoreCase("DDW")).thenReturn((long) SquadPokerTemplateService.MAX_TEMPLATES);
        SquadPokerTemplate in = SquadPokerTemplate.builder().name("X").deckType("hours").build();
        assertThrows(ResponseStatusException.class, () -> service.create("DDW", in, "u1"));
    }

    @Test
    void settingsQueNaoSaoObjetoFalham() {
        when(repository.countBySquadIdIgnoreCase("DDW")).thenReturn(0L);
        SquadPokerTemplate in = SquadPokerTemplate.builder().name("X").deckType("hours").settings(json("[1]")).build();
        assertThrows(ResponseStatusException.class, () -> service.create("DDW", in, "u1"));
    }

    @Test
    @DisplayName("renomear: quem salvou pode; só o nome muda")
    void renomearPeloAutor() {
        SquadPokerTemplate t = stored("u1");
        when(repository.findById("t1")).thenReturn(Optional.of(t));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        SquadPokerTemplate out = service.update("ddw", "t1", SquadPokerTemplate.builder().name("Novo").build(), "u1", false);
        assertEquals("Novo", out.getName());
        assertEquals("hours", out.getDeckType());
    }

    @Test
    void outraPessoaSemLiderancaNaoAltera() {
        when(repository.findById("t1")).thenReturn(Optional.of(stored("u1")));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.delete("DDW", "t1", "u2", false));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(repository, never()).delete(any());
    }

    @Test
    void liderancaExclui() {
        SquadPokerTemplate t = stored("u1");
        when(repository.findById("t1")).thenReturn(Optional.of(t));
        service.delete("DDW", "t1", "u2", true);
        verify(repository).delete(t);
    }

    @Test
    void templateDeOutraSquadNaoAparece() {
        when(repository.findById("t1")).thenReturn(Optional.of(stored("u1")));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.delete("OUTRA", "t1", "u1", true));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }
}
