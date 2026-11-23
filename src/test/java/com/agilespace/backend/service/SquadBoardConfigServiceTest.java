package com.agilespace.backend.service;

import com.agilespace.backend.domain.SquadBoardConfig;
import com.agilespace.backend.repository.SquadBoardConfigRepository;
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
@DisplayName("SquadBoardConfigService - personalização do quadro Scrum da squad")
class SquadBoardConfigServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Mock SquadBoardConfigRepository repository;
    @InjectMocks SquadBoardConfigService service;

    private JsonNode json(String text) throws Exception {
        return mapper.readTree(text);
    }

    private ResponseStatusException rejected(String body) throws Exception {
        JsonNode node = json(body);
        return assertThrows(ResponseStatusException.class, () -> service.patch("DDW", node, "u1"));
    }

    @Test
    @DisplayName("squad sem personalização devolve um quadro vazio, não 404")
    void semLinhaDevolveVazio() {
        when(repository.findById("DDW")).thenReturn(Optional.empty());

        SquadBoardConfig cfg = service.get("DDW");

        assertEquals("DDW", cfg.getSquadId());
        assertNull(cfg.getQuickFilters());
        assertNull(cfg.getSwimlanes());
        assertNull(cfg.getColumns());
        assertNull(cfg.getSwimlaneStrategy());
    }

    @Test
    @DisplayName("grava só as partes enviadas e guarda quem mexeu")
    void gravaSoOQueVeio() throws Exception {
        SquadBoardConfig existing = SquadBoardConfig.builder().squadId("DDW").swimlaneStrategy("epics")
                .columns(json("[{\"id\":1,\"min\":0,\"max\":3}]")).build();
        when(repository.findById("DDW")).thenReturn(Optional.of(existing));
        when(repository.save(any(SquadBoardConfig.class))).thenAnswer(i -> i.getArgument(0));

        SquadBoardConfig out = service.patch("DDW", json("{\"quickFilters\":[{\"id\":1,\"name\":\"Meus\"}]}"), "u1");

        assertEquals(1, out.getQuickFilters().size());
        assertEquals("epics", out.getSwimlaneStrategy(), "parte não enviada fica como estava");
        assertEquals(1, out.getColumns().size(), "parte não enviada fica como estava");
        assertEquals("u1", out.getUpdatedBy());
    }

    @Test
    @DisplayName("null apaga a personalização daquela parte")
    void nullApaga() throws Exception {
        SquadBoardConfig existing = SquadBoardConfig.builder().squadId("DDW").swimlaneStrategy("epics")
                .swimlanes(json("[{\"id\":1}]")).build();
        when(repository.findById("DDW")).thenReturn(Optional.of(existing));
        when(repository.save(any(SquadBoardConfig.class))).thenAnswer(i -> i.getArgument(0));

        SquadBoardConfig out = service.patch("DDW", json("{\"swimlanes\":null,\"swimlaneStrategy\":null}"), "u1");

        assertNull(out.getSwimlanes());
        assertNull(out.getSwimlaneStrategy());
    }

    @Test
    @DisplayName("recusa campo desconhecido, estratégia inválida e parte que não é lista")
    void recusaEntradaInvalida() throws Exception {
        when(repository.findById("DDW")).thenReturn(Optional.empty());

        assertEquals(HttpStatus.BAD_REQUEST, rejected("{\"outro\":1}").getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, rejected("{\"swimlaneStrategy\":\"zzz\"}").getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, rejected("{\"swimlaneStrategy\":3}").getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, rejected("{\"quickFilters\":{\"a\":1}}").getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, rejected("[1,2]").getStatusCode());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("lista grande demais é recusada")
    void recusaListaEnorme() {
        StringBuilder sb = new StringBuilder("{\"columns\":[");
        for (int i = 0; i < SquadBoardConfigService.MAX_ITEMS + 1; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"id\":").append(i).append("}");
        }
        sb.append("]}");
        when(repository.findById("DDW")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.patch("DDW", json(sb.toString()), "u1"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }
}
