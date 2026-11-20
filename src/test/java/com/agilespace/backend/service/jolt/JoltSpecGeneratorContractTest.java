package com.agilespace.backend.service.jolt;

import com.agilespace.backend.service.JoltJavaEngineService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JoltSpecGenerator - contrato sem valores (\"nome\": \"string\")")
class JoltSpecGeneratorContractTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JoltSpecGeneratorService service = new JoltSpecGeneratorService(new JoltJavaEngineService(mapper), mapper);

    private JsonNode read(String path) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            return mapper.readTree(in);
        }
    }

    /** Saída como o contrato do destino costuma vir: texto vira "string", número 0, booleano true; variáveis ficam. */
    private JsonNode asContract(JsonNode n) {
        if (n.isObject()) {
            ObjectNode o = mapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                o.set(e.getKey(), asContract(e.getValue()));
            }
            return o;
        }
        if (n.isArray()) {
            ArrayNode a = mapper.createArrayNode();
            if (!n.isEmpty()) a.add(asContract(n.get(0)));
            return a;
        }
        if (n.isTextual()) return n.asText().startsWith("{{") ? n : TextNode.valueOf("string");
        if (n.isNumber()) return IntNode.valueOf(0);
        if (n.isBoolean()) return BooleanNode.TRUE;
        return n;
    }

    private JoltSpecGenerator.Rule rule(JoltSpecGeneratorService.Generated g, String output) {
        return g.rules().stream().filter(r -> r.output().equals(output)).findFirst().orElseThrow(() -> new AssertionError(output));
    }

    @Test
    @DisplayName("Detecta o contrato, liga pelo nome e valida campos e tipos")
    void clientesContract() throws Exception {
        JsonNode entrada = read("/jolt-generator/clientes/entrada.json");
        JsonNode contrato = asContract(read("/jolt-generator/clientes/saida.json"));

        JoltSpecGeneratorService.Generated g = service.generate(entrada, contrato, Map.of());

        assertEquals(JoltSpecGenerator.Mode.CONTRATO, g.mode());
        assertEquals("name", rule(g, "items[].nome").source(), "glossário: name → nome");
        assertEquals("email", rule(g, "items[].email").source());
        assertFalse(rule(g, "items[].email").pending(), "mesmo nome: certeza alta");
        assertEquals("id", rule(g, "items[].idRetaguarda").source());
        assertEquals("squareId", rule(g, "items[].IdRetaguardaPraca").source(), "glossário: square → praça");
        assertEquals("minimumExpirationLotDays", rule(g, "items[].vencimentoDias").source());
        assertEquals(JoltSpecGenerator.Kind.VARIABLE, rule(g, "items[].idInquilino").kind());
        var cidade = rule(g, "items[].cidade");
        assertNotEquals("cityId", cidade.source(), "o nome da cidade não vem do código da cidade");
        assertTrue(cidade.source().endsWith("City"), cidade.source());

        var cpf = rule(g, "items[].cpfCnpj");
        assertEquals(JoltSpecGenerator.Kind.UNRESOLVED, cpf.kind(), "nenhum nome parecido: a pessoa escolhe, nada é inventado");
        assertTrue(cpf.pending());
        assertFalse(g.spec().toString().contains("\"#string\""), "nunca um de-para para o valor genérico do contrato");

        var v = g.validation();
        assertNull(v.error());
        assertFalse(v.matches(), "campos sem origem faltam na saída");
        assertTrue(v.differences().stream().anyMatch(d -> d.path().equals("items[0].cpfCnpj") && d.kind().startsWith("faltou")));
        assertTrue(v.differences().stream().noneMatch(d -> d.kind().startsWith("tipo")),
                "o que foi ligado sai com o tipo do contrato: " + v.differences());
    }

    @Test
    @DisplayName("Escolhendo a origem do que faltou, a saída passa a cumprir o contrato")
    void clientesContractWithChoices() throws Exception {
        JsonNode entrada = read("/jolt-generator/clientes/entrada.json");
        JsonNode contrato = asContract(read("/jolt-generator/clientes/saida.json"));
        JoltSpecGeneratorService.Generated first = service.generate(entrada, contrato, Map.of());

        Map<String, JoltSpecGenerator.Override> choices = new java.util.LinkedHashMap<>();
        for (JoltSpecGenerator.Rule r : first.rules()) {
            if (r.kind() != JoltSpecGenerator.Kind.UNRESOLVED) continue;
            String src = first.sources().get(r.scope()).get(0);
            choices.put(r.output(), new JoltSpecGenerator.Override("AS_TEXT", src, null, null, null));
        }
        // Os de tipo número ou booleano no contrato: fixos.
        choices.replaceAll((field, o) -> {
            JsonNode t = contrato.at("/" + field.replace("[]", "/0").replace('.', '/'));
            if (t.isNumber()) return new JoltSpecGenerator.Override("CONSTANT", null, IntNode.valueOf(0), null, null);
            if (t.isBoolean()) return new JoltSpecGenerator.Override("CONSTANT", null, BooleanNode.TRUE, null, null);
            if (t.isArray()) return new JoltSpecGenerator.Override("CONSTANT", null, contrato.at("/" + field), null, null);
            return o;
        });

        JoltSpecGeneratorService.Generated g = service.generate(entrada, contrato, choices);

        assertTrue(g.validation().matches(), g.validation().differences().toString());
    }
}
