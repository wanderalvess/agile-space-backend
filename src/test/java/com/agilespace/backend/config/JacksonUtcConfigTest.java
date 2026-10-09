package com.agilespace.backend.config;

import com.agilespace.backend.domain.UserKanbanCard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** O mapper REAL do Spring (com a autoconfiguração) envia datas do servidor com Z e aceita os dois formatos na entrada. */
@JsonTest
@Import(JacksonUtcConfig.class)
class JacksonUtcConfigTest {

    @Autowired
    private ObjectMapper mapper;

    record Carrier(LocalDateTime when) {}

    @Test
    void dataDoServidorSaiComZ() throws Exception {
        String json = mapper.writeValueAsString(new Carrier(LocalDateTime.of(2026, 10, 9, 23, 0, 30)));
        assertEquals("2026-10-09T23:00:30Z", mapper.readTree(json).get("when").asText());
    }

    @Test
    void fracaoDeSegundoEPreservada() throws Exception {
        String json = mapper.writeValueAsString(new Carrier(LocalDateTime.of(2026, 10, 9, 23, 0, 30, 241_118_000)));
        assertEquals("2026-10-09T23:00:30.241118Z", mapper.readTree(json).get("when").asText());
    }

    @Test
    void entradaComZOuOffsetViraUtcSemFuso() throws Exception {
        assertEquals(LocalDateTime.of(2026, 10, 9, 23, 0, 30), mapper.readValue("{\"when\":\"2026-10-09T23:00:30Z\"}", Carrier.class).when());
        assertEquals(LocalDateTime.of(2026, 10, 9, 23, 0, 0), mapper.readValue("{\"when\":\"2026-10-09T20:00:00-03:00\"}", Carrier.class).when());
        assertEquals(LocalDateTime.of(2026, 10, 9, 23, 0, 0), mapper.readValue("{\"when\":\"2026-10-09T20:00:00-0300\"}", Carrier.class).when());
    }

    @Test
    void entradaSemFusoOuSoDataContinuaComoAntes() throws Exception {
        assertEquals(LocalDateTime.of(2026, 10, 9, 8, 30, 0), mapper.readValue("{\"when\":\"2026-10-09T08:30:00\"}", Carrier.class).when());
        assertEquals(LocalDateTime.of(2026, 10, 9, 0, 0, 0), mapper.readValue("{\"when\":\"2026-10-09\"}", Carrier.class).when());
        assertNull(mapper.readValue("{\"when\":null}", Carrier.class).when());
    }

    @Test
    void prazoDigitadoNoKanbanNaoGanhaZ() throws Exception {
        UserKanbanCard card = new UserKanbanCard();
        card.setDueDate(LocalDateTime.of(2026, 10, 15, 0, 0, 0));
        card.setUpdatedAt(LocalDateTime.of(2026, 10, 9, 23, 0, 0));
        JsonNode json = mapper.valueToTree(card);
        assertEquals("2026-10-15T00:00:00", json.get("dueDate").asText(), "horário digitado fica sem fuso");
        assertTrue(json.get("updatedAt").asText().endsWith("Z"), "timestamp do servidor sai com Z");
        UserKanbanCard back = mapper.readValue("{\"dueDate\":\"2026-10-15T00:00:00\"}", UserKanbanCard.class);
        assertEquals(LocalDateTime.of(2026, 10, 15, 0, 0, 0), back.getDueDate());
    }

    @Test
    void mapasEDtosTambemSaemComZ() throws Exception {
        String json = mapper.writeValueAsString(Map.of("at", LocalDateTime.of(2026, 1, 2, 3, 4, 5)));
        assertTrue(json.contains("2026-01-02T03:04:05Z"));
    }
}
