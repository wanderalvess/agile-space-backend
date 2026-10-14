package com.agilespace.backend.service.jolt;

import com.agilespace.backend.controller.JoltGeneratorController;
import com.agilespace.backend.service.JoltJavaEngineService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JoltSpecGenerator - escolhas da pessoa (overrides) refazem a spec e revalidam")
class JoltSpecGeneratorOverridesTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JoltSpecGeneratorService service = new JoltSpecGeneratorService(new JoltJavaEngineService(mapper), mapper);

    private JsonNode read(String path) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            return mapper.readTree(in);
        }
    }

    private JoltSpecGenerator.Rule rule(JoltSpecGeneratorService.Generated g, String output) {
        return g.rules().stream().filter(r -> r.output().equals(output)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Trocar a origem do endereço para o grupo de entrega e completar o de-para do atacado")
    void clientesChoices() throws Exception {
        JsonNode in = read("/jolt-generator/clientes/entrada.json");
        JsonNode out = read("/jolt-generator/clientes/saida.json");
        Map<String, JoltSpecGenerator.Override> choices = new LinkedHashMap<>();
        choices.put("items[].endereco", new JoltSpecGenerator.Override("COPY", "deliveryAddress", null, null, null));
        choices.put("items[].UtilizaPrecoAtacado", new JoltSpecGenerator.Override("VALUE_MAP", "wholesalePriceUses", null,
                Map.of("N", TextNode.valueOf("False"), "S", TextNode.valueOf("True")), null));

        JoltSpecGeneratorService.Generated g = service.generate(in, out, choices);

        assertTrue(g.validation().matches(), g.validation().toString());
        var endereco = rule(g, "items[].endereco");
        assertEquals("deliveryAddress", endereco.source());
        assertFalse(endereco.pending());
        assertEquals("definido por você", endereco.reason());
        assertFalse(rule(g, "items[].UtilizaPrecoAtacado").pending());
        assertTrue(g.spec().toString().contains("\"#True\""), "o valor que o exemplo não mostra também entra na spec");
        assertTrue(g.sources().get("items[]").contains("deliveryAddress"), "a tela recebe as origens possíveis da lista");
    }

    @Test
    @DisplayName("Fixar o tipo em 0 e de-para com 'qualquer outro' (situação da embalagem)")
    void produtosChoices() throws Exception {
        JsonNode in = read("/jolt-generator/produtos/entrada.json");
        JsonNode out = read("/jolt-generator/produtos/saida.json");
        Map<String, JoltSpecGenerator.Override> choices = new LinkedHashMap<>();
        choices.put("items[].tipo", new JoltSpecGenerator.Override("CONSTANT", null, mapper.valueToTree(0), null, null));
        choices.put("items[].produtoEmbalagens[].situacao", new JoltSpecGenerator.Override("VALUE_MAP", "inativo", null,
                Map.of("false", mapper.valueToTree(1)), mapper.valueToTree(0)));

        JoltSpecGeneratorService.Generated g = service.generate(in, out, choices);

        assertTrue(g.validation().matches(), g.validation().toString());
        assertEquals(JoltSpecGenerator.Kind.CONSTANT, rule(g, "items[].tipo").kind());
        var situacao = rule(g, "items[].produtoEmbalagens[].situacao");
        assertEquals("inativo", situacao.source());
        assertTrue(g.spec().toString().contains("\"#0\""), "o 'qualquer outro' vira a chave * do de-para");
        assertTrue(g.spec().toString().contains("=toInteger"), "o de-para numérico ganha a conversão de tipo");
    }

    @Test
    @DisplayName("Origem que não existe vira pendência; tipo inválido é recusado com 400")
    void invalidChoices() throws Exception {
        JsonNode in = read("/jolt-generator/clientes/entrada.json");
        JsonNode out = read("/jolt-generator/clientes/saida.json");

        var g = service.generate(in, out, Map.of("items[].nome",
                new JoltSpecGenerator.Override("COPY", "campoQueNaoExiste", null, null, null)));
        var nome = rule(g, "items[].nome");
        assertEquals(JoltSpecGenerator.Kind.UNRESOLVED, nome.kind());
        assertTrue(nome.pending());

        JoltGeneratorController controller = new JoltGeneratorController(service);
        assertThrows(ResponseStatusException.class, () -> controller.generate(new JoltGeneratorController.GenerateRequest(
                in, out, Map.of("items[].nome", new JoltSpecGenerator.Override("APAGAR", "name", null, null, null)))));
        assertThrows(ResponseStatusException.class, () -> controller.generate(new JoltGeneratorController.GenerateRequest(
                in, out, Map.of("items[].nome", new JoltSpecGenerator.Override("VALUE_MAP", "name", null,
                        Map.of("a*b", TextNode.valueOf("x")), null)))));
    }
}
