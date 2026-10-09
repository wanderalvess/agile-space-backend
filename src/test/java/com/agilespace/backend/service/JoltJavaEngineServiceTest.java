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
}
