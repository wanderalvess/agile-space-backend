package com.agilespace.backend.service;

import com.agilespace.backend.dto.JoltTransformRequestDto;
import com.agilespace.backend.dto.JoltTransformResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JoltJavaEngineServiceTest {

    private JoltJavaEngineService joltService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        joltService = new JoltJavaEngineService(objectMapper);
    }

    @Test
    @DisplayName("Deve executar transformação JOLT Shift básica com sucesso")
    void testBasicShift() {
        String inputJson = """
                {
                    "cliente": {
                        "nome": "João Silva",
                        "idade": 35
                    }
                }
                """;

        String specJson = """
                [
                    {
                        "operation": "shift",
                        "spec": {
                            "cliente": {
                                "nome": "customer.fullName",
                                "idade": "customer.age"
                            }
                        }
                    }
                ]
                """;

        JoltTransformRequestDto request = JoltTransformRequestDto.builder()
                .input(inputJson)
                .spec(specJson)
                .build();

        JoltTransformResponseDto response = joltService.transform(request);

        assertTrue(response.isSuccess());
        assertEquals("JAVA_BAZAARVOICE", response.getEngine());
        assertNotNull(response.getOutput());

        @SuppressWarnings("unchecked")
        Map<String, Object> outputMap = (Map<String, Object>) response.getOutput();
        @SuppressWarnings("unchecked")
        Map<String, Object> customer = (Map<String, Object>) outputMap.get("customer");

        assertNotNull(customer);
        assertEquals("João Silva", customer.get("fullName"));
        assertEquals(35, customer.get("age"));
    }

    @Test
    @DisplayName("Deve extrair e executar spec dentro de envelope TOTVS SmartHub")
    void testSmartHubEnvelopeExtraction() {
        String inputJson = """
                {
                    "codigo": "001",
                    "descricao": "PRODUTO TESTE"
                }
                """;

        String layoutEnvelopeJson = """
                {
                    "tabela": {
                        "campos": [
                            {
                                "nome": "LAYOUTTRANSFORMACAO",
                                "valor": "[{\\"operation\\": \\"shift\\", \\"spec\\": {\\"codigo\\": \\"id\\", \\"descricao\\": \\"name\\"}}]"
                            }
                        ]
                    }
                }
                """;

        JoltTransformRequestDto request = JoltTransformRequestDto.builder()
                .input(inputJson)
                .spec(layoutEnvelopeJson)
                .build();

        JoltTransformResponseDto response = joltService.transform(request);

        assertTrue(response.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> outputMap = (Map<String, Object>) response.getOutput();
        assertEquals("001", outputMap.get("id"));
        assertEquals("PRODUTO TESTE", outputMap.get("name"));
    }

    @Test
    @DisplayName("Deve retornar erro amigável em caso de spec inválida")
    void testInvalidSpec() {
        JoltTransformRequestDto request = JoltTransformRequestDto.builder()
                .input("{\"a\": 1}")
                .spec("[]") // Spec vazia
                .build();

        JoltTransformResponseDto response = joltService.transform(request);

        assertFalse(response.isSuccess());
        assertNotNull(response.getError());
    }

    private static final String IDENTITY_SPEC = "[{\"operation\":\"shift\",\"spec\":{\"*\":\"&\"}}]";

    @Test
    @DisplayName("Recusa payload com aninhamento profundo demais")
    void testRejectsDeepNesting() {
        StringBuilder open = new StringBuilder();
        StringBuilder close = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            open.append("{\"a\":");
            close.append("}");
        }
        String deep = open + "1" + close;

        JoltTransformResponseDto response = joltService.transform(
                JoltTransformRequestDto.builder().input(deep).spec(IDENTITY_SPEC).build());

        assertFalse(response.isSuccess());
        assertTrue(response.getError().contains("aninhamento"));
    }

    @Test
    @DisplayName("Recusa payload com elementos demais")
    void testRejectsTooManyNodes() {
        List<Integer> big = new java.util.ArrayList<>();
        for (int i = 0; i <= JoltJavaEngineService.MAX_INPUT_NODES; i++) big.add(i);

        JoltTransformResponseDto response = joltService.transform(
                JoltTransformRequestDto.builder().input(Map.of("itens", big)).spec(IDENTITY_SPEC).build());

        assertFalse(response.isSuccess());
        assertTrue(response.getError().contains("grande demais"));
    }

    @Test
    @DisplayName("Recusa texto de entrada acima do limite sem tentar fazer o parse")
    void testRejectsHugeText() {
        String huge = "\"" + "x".repeat((int) JoltJavaEngineService.MAX_TEXT_CHARS + 10) + "\"";

        JoltTransformResponseDto response = joltService.transform(
                JoltTransformRequestDto.builder().input(huge).spec(IDENTITY_SPEC).build());

        assertFalse(response.isSuccess());
        assertTrue(response.getError().contains("limite"));
    }

    @Test
    @DisplayName("Recusa spec com operações demais")
    void testRejectsTooManyOperations() {
        List<Object> ops = new java.util.ArrayList<>();
        for (int i = 0; i <= JoltJavaEngineService.MAX_SPEC_OPERATIONS; i++) {
            ops.add(Map.of("operation", "shift", "spec", Map.of("*", "&")));
        }

        JoltTransformResponseDto response = joltService.transform(
                JoltTransformRequestDto.builder().input("{\"a\":1}").spec(ops).build());

        assertFalse(response.isSuccess());
        assertTrue(response.getError().contains("operações demais"));
    }

    @Test
    @DisplayName("JSON inválido devolve mensagem curta, sem trecho do código-fonte do Jackson")
    void testInvalidJsonMessageIsShort() {
        JoltTransformResponseDto response = joltService.transform(
                JoltTransformRequestDto.builder().input("{\"a\": ").spec(IDENTITY_SPEC).build());

        assertFalse(response.isSuccess());
        assertTrue(response.getError().startsWith("JSON inválido"));
        assertFalse(response.getError().contains("\n"));
        assertFalse(response.getError().contains("Source:"));
    }

    @Test
    @DisplayName("Falha inesperada não vaza detalhe interno")
    void testUnexpectedFailureIsGeneric() {
        // Spec com operação shift cujo conteúdo não é um objeto: erro do Jolt ou interno, nunca stack.
        JoltTransformResponseDto response = joltService.transform(
                JoltTransformRequestDto.builder().input("{\"a\":1}")
                        .spec("[{\"operation\":\"shift\",\"spec\":5}]").build());

        assertFalse(response.isSuccess());
        assertFalse(response.getError().contains("at com."));
        assertTrue(response.getError().length() <= 410);
    }

    @Test
    @DisplayName("safeMessage corta em uma linha e limita o tamanho")
    void testSafeMessage() {
        assertEquals("linha1", JoltJavaEngineService.safeMessage("linha1\nlinha2"));
        assertEquals("Entrada inválida.", JoltJavaEngineService.safeMessage(null));
        assertTrue(JoltJavaEngineService.safeMessage("x".repeat(1000)).length() <= 401);
    }

    @Test
    @DisplayName("custom-totvs decodifica Base64 dentro de lista (data → * → conteudo), como o motor JS")
    @SuppressWarnings("unchecked")
    void base64InsideListWithWildcard() throws Exception {
        String conteudo = java.util.Base64.getEncoder().encodeToString(
                "{\"Coo\":\"2765\",\"Loja\":{\"IdRetaguarda\":\"1\"},\"ValorBruto\":383.50}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String input = "{\"data\":[{\"id\":715254,\"conteudo\":\"" + conteudo + "\"},{\"id\":2,\"conteudo\":\"nao-e-base64!\"}]}";
        String spec = """
                [
                  {"operation": "custom-totvs", "spec": {"data": {"*": {"conteudo": "=base64ToObject"}}}},
                  {"operation": "shift", "spec": {"data": {"*": {
                      "id": "items[&1].idPdv",
                      "conteudo": {"Coo": "items[&2].numCupom", "Loja": {"IdRetaguarda": "items[&3].codFilial"},
                                   "ValorBruto": "items[&2].vlTabela"}
                  }}}}
                ]
                """;

        JoltTransformResponseDto response = joltService.transform(new JoltTransformRequestDto(
                objectMapper.readValue(input, Object.class), objectMapper.readValue(spec, Object.class), null));

        assertTrue(response.isSuccess(), response.getError());
        List<Map<String, Object>> items = (List<Map<String, Object>>) ((Map<String, Object>) response.getOutput()).get("items");
        assertEquals("2765", items.get(0).get("numCupom"));
        assertEquals("1", items.get(0).get("codFilial"));
        assertEquals(383.5, ((Number) items.get(0).get("vlTabela")).doubleValue());
        assertEquals(715254, items.get(0).get("idPdv"));
        // Texto que não é Base64 fica como estava, sem derrubar a transformação.
        assertEquals(2, items.get(1).get("idPdv"));
        assertNull(items.get(1).get("numCupom"));
    }

    // ── Comportamento do motor Java (a tela usa só ele desde que o motor JavaScript saiu) ──────────────────────

    private Object run(Object input, String spec) throws Exception {
        JoltTransformResponseDto response = joltService.transform(new JoltTransformRequestDto(
                objectMapper.readValue(objectMapper.writeValueAsString(input), Object.class),
                objectMapper.readValue(spec, Object.class), null));
        assertTrue(response.isSuccess(), response.getError());
        return response.getOutput();
    }

    /** Números iguais em valor contam como iguais (1 e 1.0), o resto é comparação estrita. */
    private static Object canonical(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new java.util.TreeMap<>();
            m.forEach((k, val) -> out.put(String.valueOf(k), canonical(val)));
            return out;
        }
        if (v instanceof List<?> l) return l.stream().map(JoltJavaEngineServiceTest::canonical).toList();
        if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) return n.longValue();
        return v;
    }

    @Test
    @DisplayName("Layout real de clientes (PDVSync): a saída é exatamente a esperada, inclusive os tipos")
    void realCustomerLayoutMatchesExpected() throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of("src/test/resources/jolt-engine/clientes");
        Object input = objectMapper.readValue(dir.resolve("input.json").toFile(), Object.class);
        Object spec = objectMapper.readValue(dir.resolve("spec.json").toFile(), Object.class);
        Object expected = objectMapper.readValue(dir.resolve("expected.json").toFile(), Object.class);

        JoltTransformResponseDto response = joltService.transform(new JoltTransformRequestDto(input, spec, null));

        assertTrue(response.isSuccess(), response.getError());
        assertEquals(canonical(expected), canonical(response.getOutput()));
    }

    @Test
    @DisplayName("Literal '#' é sempre texto e um modify depois converte o tipo (sexo vira número)")
    @SuppressWarnings("unchecked")
    void hashLiteralIsTextUntilModifyConverts() throws Exception {
        Map<String, Object> out = (Map<String, Object>) run(Map.of("a", "M"), """
                [{"operation":"shift","spec":{"a":{"M":{"#1":"sexo"}}}},
                 {"operation":"modify-overwrite-beta","spec":{"sexo":"=toInteger"}}]""");
        assertEquals(1, out.get("sexo"));
        Map<String, Object> text = (Map<String, Object>) run(Map.of("a", "M"),
                "[{\"operation\":\"shift\",\"spec\":{\"a\":{\"M\":{\"#1\":\"sexo\"}}}}]");
        assertEquals("1", text.get("sexo"));
    }

    @Test
    @DisplayName("Funções do modify que o Jolt oficial tem funcionam (leftPad, split/join, concat, divideAndRound, sum)")
    @SuppressWarnings("unchecked")
    void officialModifyFunctions() throws Exception {
        Map<String, Object> out = (Map<String, Object>) run(
                Map.of("a", "7", "cpf", "123.456.789-00", "n", List.of(1, 2, 3), "x", 10, "y", 4), """
                [{"operation":"modify-overwrite-beta","spec":{
                   "p":"=leftPad(@(1,a),3,'0')",
                   "arr":"=split('\\\\D',@(1,cpf))","j":"=join('', @(1,arr))",
                   "k":"=concat('id-',@(1,a))",
                   "r":"=divideAndRound(2,@(1,x),@(1,y))",
                   "t":"=intSum(@(1,n))"}}]""");
        assertEquals("007", out.get("p"));
        assertEquals("12345678900", out.get("j"));
        assertEquals("id-7", out.get("k"));
        assertEquals(2.5, ((Number) out.get("r")).doubleValue());
        assertEquals(6, ((Number) out.get("t")).intValue());
    }

    @Test
    @DisplayName("Funções que NÃO existem no Jolt oficial (replace, multiply, padLeft) não fazem nada: a tela mostra o que o servidor de produção faria")
    @SuppressWarnings("unchecked")
    void nonOfficialFunctionsDoNothing() throws Exception {
        Map<String, Object> out = (Map<String, Object>) run(Map.of("a", "a-b", "x", 3), """
                [{"operation":"modify-overwrite-beta","spec":{
                   "r":"=replace(@(1,a),'-','_')","m":"=multiply(@(1,x),2)","p":"=padLeft(@(1,a),5,'0')"}}]""");
        assertEquals("a-b", out.get("r"));
        assertEquals(3, out.get("m"));
        assertEquals("a-b", out.get("p"));
    }
}
