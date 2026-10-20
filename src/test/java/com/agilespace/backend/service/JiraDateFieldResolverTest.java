package com.agilespace.backend.service;

import com.agilespace.backend.domain.Squad;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class JiraDateFieldResolverTest {

    private JiraService jira;
    private JiraDateFieldResolver resolver;
    private final AtomicLong now = new AtomicLong(1_000);

    @BeforeEach
    void setUp() {
        jira = mock(JiraService.class);
        resolver = new JiraDateFieldResolver(jira, new ObjectMapper());
        resolver.setClock(now::get);
    }

    private void fields(String json) {
        doReturn(ResponseEntity.ok(json)).when(jira).getFields(anyString(), anyString());
    }

    @Test
    void encontraPorNomeIgnorandoCaixaAcentoEEspacos() {
        fields("[{\"id\":\"customfield_1\",\"name\":\"  DATA E HORA DE INICIO \"},"
                + "{\"id\":\"customfield_2\",\"name\":\"data e hora de fim\"},"
                + "{\"id\":\"summary\",\"name\":\"Summary\"}]");

        var r = resolver.resolve("jira.x.com", "t");

        assertEquals(1, r.start().size());
        assertEquals("customfield_1", r.start().get(0).id());
        assertEquals("customfield_2", r.end().get(0).id());
        assertEquals("Summary", r.namesById().get("summary"));
    }

    @Test
    void nomesDuplicadosViramCandidatosEPickNaoEscolheEmSilencio() {
        fields("[{\"id\":\"customfield_1\",\"name\":\"Data e Hora de Início\"},"
                + "{\"id\":\"customfield_9\",\"name\":\"Data e Hora de Início\"}]");

        var r = resolver.resolve("jira.x.com", "t");

        assertEquals(2, r.start().size());
        assertNull(JiraDateFieldResolver.pick(null, r.start()));
        assertEquals("customfield_9", JiraDateFieldResolver.pick("customfield_9", r.start()), "o configurado vence");
    }

    @Test
    void nenhumEncontradoDevolveListasVazias() {
        fields("[{\"id\":\"customfield_1\",\"name\":\"Outro\"}]");

        var r = resolver.resolve("jira.x.com", "t");

        assertTrue(r.start().isEmpty());
        assertTrue(r.end().isEmpty());
        assertNull(JiraDateFieldResolver.pick(" ", r.start()));
    }

    @Test
    void cachePorDominioExpiraEm10Minutos() {
        fields("[]");

        resolver.resolve("Jira.X.com", "t");
        resolver.resolve("jira.x.com", "t");
        verify(jira, times(1)).getFields(anyString(), anyString());

        now.addAndGet(JiraDateFieldResolver.TTL_MS + 1);
        resolver.resolve("jira.x.com", "t");
        verify(jira, times(2)).getFields(anyString(), anyString());

        resolver.resolve("outro.com", "t");
        verify(jira, times(3)).getFields(anyString(), anyString());
    }

    @Test
    void falhaDoJiraLancaEmPortuguesENaoFicaEmCache() {
        when(jira.getFields(anyString(), anyString())).thenReturn(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("{}"));

        var ex = assertThrows(JiraDateFieldResolver.LookupException.class, () -> resolver.resolve("jira.x.com", "t"));
        assertTrue(ex.getMessage().contains("401"));

        fields("[]");
        assertDoesNotThrow(() -> resolver.resolve("jira.x.com", "t"));
    }

    @Test
    void excecaoERespostaInvalidaViramLookupException() {
        when(jira.getFields(anyString(), anyString())).thenThrow(new RuntimeException("rede"));
        assertThrows(JiraDateFieldResolver.LookupException.class, () -> resolver.resolve("a.com", "t"));

        fields("nao e json");
        assertThrows(JiraDateFieldResolver.LookupException.class, () -> resolver.resolve("b.com", "t"));

        fields("{\"x\":1}");
        assertThrows(JiraDateFieldResolver.LookupException.class, () -> resolver.resolve("c.com", "t"));
    }

    @Test
    void effectiveIdsNuncaLancaEUsaConfiguradoSemChamarOJira() {
        Squad both = Squad.builder().id("s").jiraStartField("customfield_5").jiraEndField("customfield_6").build();
        var ids = resolver.effectiveIds(both, "jira.x.com", "t");
        assertEquals("customfield_5", ids.start());
        assertEquals("customfield_6", ids.end());
        verifyNoInteractions(jira);

        when(jira.getFields(anyString(), anyString())).thenThrow(new RuntimeException("fora do ar"));
        Squad half = Squad.builder().id("s").jiraStartField("customfield_5").build();
        var fallback = resolver.effectiveIds(half, "jira.x.com", "t");
        assertEquals("customfield_5", fallback.start());
        assertNull(fallback.end());
    }

    @Test
    void effectiveIdsAutodetectaQuandoUnico() {
        fields("[{\"id\":\"customfield_1\",\"name\":\"Data e Hora de Início\"},{\"id\":\"customfield_2\",\"name\":\"Data e Hora de Fim\"}]");

        var ids = resolver.effectiveIds(Squad.builder().id("s").build(), "jira.x.com", "t");

        assertEquals("customfield_1", ids.start());
        assertEquals("customfield_2", ids.end());
    }
}
