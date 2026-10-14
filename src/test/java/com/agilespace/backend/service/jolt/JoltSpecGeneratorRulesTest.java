package com.agilespace.backend.service.jolt;

import com.agilespace.backend.controller.JoltGeneratorController;
import com.agilespace.backend.service.JoltJavaEngineService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JoltSpecGenerator - regras de inferência")
class JoltSpecGeneratorRulesTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JoltSpecGeneratorService service = new JoltSpecGeneratorService(new JoltJavaEngineService(mapper), mapper);

    private JoltSpecGenerator.Rule rule(JoltSpecGeneratorService.Generated g, String output) {
        return g.rules().stream().filter(r -> r.output().equals(output)).findFirst()
                .orElseThrow(() -> new AssertionError("sem regra para " + output + ": " + g.rules()));
    }

    private JoltSpecGeneratorService.Generated gen(String in, String out) throws Exception {
        JoltSpecGeneratorService.Generated g = service.generate(mapper.readTree(in), mapper.readTree(out));
        assertTrue(g.validation().matches(), "a spec gerada tem que reproduzir a saída: " + g.validation() + "\n" + g.spec());
        return g;
    }

    @Test
    @DisplayName("Valor fraco (0) sem nome parecido vira fixo pendente, não cópia de um campo qualquer")
    void weakValueWithoutNameBecomesConstant() throws Exception {
        var g = gen("{\"items\":[{\"cityId\":0,\"nome\":\"A\"}]}", "{\"items\":[{\"enviarpnet\":0,\"nome\":\"A\"}]}");
        var r = rule(g, "items[].enviarpnet");
        assertEquals(JoltSpecGenerator.Kind.CONSTANT, r.kind());
        assertTrue(r.pending());
        assertTrue(r.alternatives().contains("cityId"));
    }

    @Test
    @DisplayName("Cópia em lista vale para todos os itens; valor que muda vem do campo, não de um fixo")
    void copyMustHoldForEveryItem() throws Exception {
        var g = gen("{\"items\":[{\"a\":\"x\",\"b\":\"x\"},{\"a\":\"y\",\"b\":\"z\"}]}",
                "{\"items\":[{\"campo\":\"x\"},{\"campo\":\"z\"}]}");
        var r = rule(g, "items[].campo");
        assertEquals(JoltSpecGenerator.Kind.COPY, r.kind());
        assertEquals("b", r.source());
    }

    @Test
    @DisplayName("Só dígitos e concatenação com literal")
    void digitsAndConcat() throws Exception {
        var g = gen("{\"items\":[{\"doc\":\"215.358.992-47\",\"id\":150,\"dt\":\"2026-09-18T12:00:03\"}]}",
                "{\"items\":[{\"cpfCnpj\":\"21535899247\",\"chave\":\"cli-150-2026-09-18T12:00:03\"}]}");
        assertEquals(JoltSpecGenerator.Kind.DIGITS, rule(g, "items[].cpfCnpj").kind());
        var concat = rule(g, "items[].chave");
        assertEquals(JoltSpecGenerator.Kind.CONCAT, concat.kind());
        assertEquals("'cli-' + id + '-' + dt", concat.source());
    }

    @Test
    @DisplayName("De-para pelo nome fica pendente (só o valor visto); nome genérico (id) não inventa de-para")
    void valueMapIsPendingAndNeedsRealName() throws Exception {
        var g = gen("{\"items\":[{\"wholesalePriceUses\":\"N\",\"id\":3}]}",
                "{\"items\":[{\"UtilizaPrecoAtacado\":\"False\",\"idRetaguarda\":\"X1\"}]}");
        var map = rule(g, "items[].UtilizaPrecoAtacado");
        assertEquals(JoltSpecGenerator.Kind.VALUE_MAP, map.kind());
        assertTrue(map.pending());
        assertNotEquals(JoltSpecGenerator.Kind.VALUE_MAP, rule(g, "items[].idRetaguarda").kind());
    }

    @Test
    @DisplayName("Endpoint recusa exemplo sem saída e exemplo grande demais")
    void controllerValidatesRequest() {
        JoltGeneratorController controller = new JoltGeneratorController(service);
        assertThrows(ResponseStatusException.class,
                () -> controller.generate(new JoltGeneratorController.GenerateRequest(mapper.createObjectNode(), null, null)));
        var big = mapper.createArrayNode();
        for (int i = 0; i < 60_000; i++) big.add(i);
        JsonNode input = mapper.createObjectNode().set("items", big);
        assertThrows(ResponseStatusException.class,
                () -> controller.generate(new JoltGeneratorController.GenerateRequest(input, mapper.createObjectNode(), null)));
    }
}
