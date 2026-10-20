package com.agilespace.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** parseIssue: datas planejadas vindas dos campos resolvidos por nome e a precedência antiga como fallback. */
class SquadSyncParseIssueDatesTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private SquadSyncService sync;

    @BeforeEach
    void setUp() {
        sync = new SquadSyncService(null, null, null, null, mapper, mock(JiraDateFieldResolver.class));
    }

    private JsonNode issue(String fieldsJson) throws Exception {
        return mapper.readTree("{\"key\":\"A-1\",\"fields\":{\"summary\":\"x\",\"created\":\"2026-10-01T10:00:00.000-0300\","
                + "\"updated\":\"2026-10-05T10:00:00.000-0300\"," + fieldsJson + "}}");
    }

    @Test
    void ambosCamposPreenchidosPreservamHorarioENaoSaoInferidos() throws Exception {
        var p = sync.parseIssue(issue("\"customfield_100\":\"2026-10-20T09:00:00.000-0300\",\"customfield_200\":\"2026-10-20T17:30:00.000-0300\""),
                null, "customfield_100", "customfield_200");

        assertEquals("2026-10-20T09:00:00-03:00", p.targetStart());
        assertEquals("2026-10-20T17:30:00-03:00", p.targetEnd());
        assertFalse(p.datesAreInferred());
    }

    @Test
    void campoSoDeDataGuardaSoData() throws Exception {
        var p = sync.parseIssue(issue("\"customfield_100\":\"2026-10-20\",\"customfield_200\":\"2026-10-22\""),
                null, "customfield_100", "customfield_200");

        assertEquals("2026-10-20", p.targetStart());
        assertEquals("2026-10-22", p.targetEnd());
        assertFalse(p.datesAreInferred());
    }

    @Test
    void faltandoUmDosCamposResolvidosContinuaInferidoMesmoComDuedate() throws Exception {
        var p = sync.parseIssue(issue("\"customfield_100\":\"2026-10-20T09:00:00.000-0300\",\"customfield_200\":null,\"duedate\":\"2026-10-25\""),
                null, "customfield_100", "customfield_200");

        assertEquals("2026-10-20T09:00:00-03:00", p.targetStart());
        assertEquals("2026-10-25", p.targetEnd(), "duedate preenche o valor, mas a data segue marcada como inferida");
        assertTrue(p.datesAreInferred());
    }

    @Test
    void semCamposResolvidosMantemPrecedenciaAntiga() throws Exception {
        var p = sync.parseIssue(issue("\"customfield_10015\":\"2026-10-10\",\"duedate\":\"2026-10-12\""), null);

        assertEquals("2026-10-10", p.targetStart());
        assertEquals("2026-10-12", p.targetEnd());
        assertFalse(p.datesAreInferred());
    }

    @Test
    void semNadaCaiEmCriacaoEUltimaEdicaoEMarcaInferido() throws Exception {
        var p = sync.parseIssue(issue("\"x\":1"), null, "customfield_100", "customfield_200");

        assertEquals("2026-10-01", p.targetStart());
        assertEquals("2026-10-05", p.targetEnd());
        assertTrue(p.datesAreInferred());
    }
}
