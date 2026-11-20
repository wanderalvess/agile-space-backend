package com.agilespace.backend.service.jolt;

import com.agilespace.backend.domain.JoltGlossaryTerm;
import com.agilespace.backend.repository.JoltGlossaryTermRepository;
import com.agilespace.backend.service.JoltJavaEngineService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@DisplayName("Gerador de spec - glossário que aprende com a equipe")
class JoltGlossaryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    private JsonNode read(String path) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            return mapper.readTree(in);
        }
    }

    @Test
    @DisplayName("Par já aprendido vem ligado com certeza alta, mesmo sem nome parecido")
    void learnedPairIsUsed() throws Exception {
        JsonNode entrada = read("/jolt-generator/clientes/entrada.json");
        JsonNode contrato = json("{\"items\":[{\"cpfCnpj\":\"string\",\"cidade\":\"string\",\"nome\":\"string\"}]}");

        var semGlossario = JoltSpecGenerator.generate(entrada, contrato, Map.of());
        var cpf = semGlossario.rules().stream().filter(r -> r.output().equals("items[].cpfCnpj")).findFirst().orElseThrow();
        assertEquals(JoltSpecGenerator.Kind.UNRESOLVED, cpf.kind());

        var comGlossario = JoltSpecGenerator.generate(entrada, contrato, Map.of(),
                Map.of("cpfcnpj", Set.of("personidentificationnumber")));
        cpf = comGlossario.rules().stream().filter(r -> r.output().equals("items[].cpfCnpj")).findFirst().orElseThrow();
        assertEquals("personIdentificationNumber", cpf.source());
        assertEquals(JoltSpecGenerator.Confidence.HIGH, cpf.confidence());
        assertFalse(cpf.pending());
        assertTrue(cpf.reason().contains("glossário"), cpf.reason());
    }

    @Test
    @DisplayName("Aprende só quando a spec cumpre a saída e a tela pediu")
    void learnsOnlyProvenChoices() throws Exception {
        JoltGlossaryService glossary = mock(JoltGlossaryService.class);
        when(glossary.load()).thenReturn(Map.of());
        JoltSpecGeneratorService service = new JoltSpecGeneratorService(new JoltJavaEngineService(mapper), mapper);
        service.setGlossary(glossary);

        JsonNode in = json("{\"items\":[{\"codProd\":\"7\",\"outro\":\"9\"},{\"codProd\":\"8\",\"outro\":\"9\"}]}");
        JsonNode out = json("{\"items\":[{\"sku\":\"7\"},{\"sku\":\"8\"}]}");
        var certo = Map.of("items[].sku", new JoltSpecGenerator.Override("COPY", "codProd", null, null, null));
        var errado = Map.of("items[].sku", new JoltSpecGenerator.Override("COPY", "outro", null, null, null));

        assertTrue(service.generate(in, out, certo, false).validation().matches());
        verify(glossary, never()).learn(anyList());

        assertFalse(service.generate(in, out, errado, true).validation().matches());
        verify(glossary, never()).learn(anyList());

        assertTrue(service.generate(in, out, certo, true).validation().matches());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JoltGlossaryService.Pair>> pairs = ArgumentCaptor.forClass(List.class);
        verify(glossary).learn(pairs.capture());
        assertEquals(List.of(new JoltGlossaryService.Pair("sku", "codProd")), pairs.getValue());
    }

    @Test
    @DisplayName("Escolha sem origem (fixo, variável) não vira par; nomes vêm sem lista nem ↑")
    void learnablePairs() {
        var pairs = JoltSpecGeneratorService.learnablePairs(Map.of(
                "items[].embalagens[].ean", new JoltSpecGenerator.Override("DIGITS", "↑produto.embalagens[0].codigoDeBarra", null, null, null),
                "items[].fixo", new JoltSpecGenerator.Override("CONSTANT", null, mapper.getNodeFactory().textNode("x"), null, null)));
        assertEquals(List.of(new JoltGlossaryService.Pair("ean", "codigoDeBarra")), pairs);
    }

    @Test
    @DisplayName("Grava normalizado, conta usos e ignora nomes iguais")
    void learnNormalizesAndCounts() {
        JoltGlossaryTermRepository repo = mock(JoltGlossaryTermRepository.class);
        JoltGlossaryTerm existente = JoltGlossaryTerm.builder().termFrom("idretaguarda").termTo("id").uses(2).build();
        when(repo.findByTermFromAndTermTo("idretaguarda", "id")).thenReturn(Optional.of(existente));
        when(repo.findByTermFromAndTermTo("situacao", "active")).thenReturn(Optional.empty());
        JoltGlossaryService glossary = new JoltGlossaryService(repo);

        int saved = glossary.learn(List.of(
                new JoltGlossaryService.Pair("idRetaguarda", "id"),
                new JoltGlossaryService.Pair("Situação", "active"),
                new JoltGlossaryService.Pair("email", "EMAIL"),
                new JoltGlossaryService.Pair("idRetaguarda", "id")));

        assertEquals(2, saved);
        assertEquals(3, existente.getUses());
        ArgumentCaptor<JoltGlossaryTerm> captor = ArgumentCaptor.forClass(JoltGlossaryTerm.class);
        verify(repo, times(2)).save(captor.capture());
        JoltGlossaryTerm novo = captor.getAllValues().get(1);
        assertEquals("situacao", novo.getTermFrom());
        assertEquals("active", novo.getTermTo());
        assertEquals(1, novo.getUses());
        verify(repo, never()).findByTermFromAndTermTo(eq("email"), any());
    }

    @Test
    @DisplayName("load agrupa os pares por campo da saída")
    void loadGroups() {
        JoltGlossaryTermRepository repo = mock(JoltGlossaryTermRepository.class);
        when(repo.findAll()).thenReturn(List.of(
                JoltGlossaryTerm.builder().termFrom("nome").termTo("name").build(),
                JoltGlossaryTerm.builder().termFrom("nome").termTo("tradename").build()));
        assertEquals(Map.of("nome", Set.of("name", "tradename")), new JoltGlossaryService(repo).load());
    }
}
