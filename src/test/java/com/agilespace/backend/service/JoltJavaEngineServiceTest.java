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
}
