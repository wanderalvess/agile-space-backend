package com.agilespace.backend.service.jolt;

import com.agilespace.backend.service.JoltJavaEngineService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JoltSpecGenerator - casos reais: a spec gerada reproduz a saída esperada no motor Java")
class JoltSpecGeneratorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JoltSpecGeneratorService service = new JoltSpecGeneratorService(new JoltJavaEngineService(mapper), mapper);

    private JsonNode read(String path) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            assertNotNull(in, "fixture ausente: " + path);
            return mapper.readTree(in);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"clientes", "produtos"})
    void generatedSpecReproducesExpectedOutput(String caso) throws Exception {
        JsonNode entrada = read("/jolt-generator/" + caso + "/entrada.json");
        JsonNode saida = read("/jolt-generator/" + caso + "/saida.json");

        JoltSpecGeneratorService.Generated g = service.generate(entrada, saida);
        if (System.getenv("JOLT_GEN_DEBUG") != null) {
            System.out.println("SPEC " + caso + ": " + g.spec().toPrettyString());
            g.rules().forEach(r -> System.out.println("RULE " + caso + " " + r));
        }

        String diffs = g.validation().differences().stream()
                .map(d -> d.path() + " (" + d.kind() + "): esperado " + d.expected() + ", gerado " + d.actual())
                .collect(Collectors.joining("\n"));
        assertNull(g.validation().error(), "o motor recusou a spec gerada: " + g.validation().error() + "\n" + g.spec());
        assertTrue(g.validation().matches(), "diferenças:\n" + diffs + "\nspec:\n" + g.spec().toPrettyString());
        assertTrue(g.validation().checkedFields() > 20);
    }
}
