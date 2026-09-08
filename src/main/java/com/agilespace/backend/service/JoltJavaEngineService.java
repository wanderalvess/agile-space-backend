package com.agilespace.backend.service;

import com.agilespace.backend.dto.JoltTransformRequestDto;
import com.agilespace.backend.dto.JoltTransformResponseDto;
import com.bazaarvoice.jolt.Chainr;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class JoltJavaEngineService {

    private final ObjectMapper objectMapper;

    /**
     * Executa a transformação JOLT utilizando a biblioteca oficial da Bazaarvoice.
     */
    public JoltTransformResponseDto transform(JoltTransformRequestDto request) {
        long startTime = System.currentTimeMillis();
        try {
            Object normalizedInput = normalizeInput(request.getInput());
            List<Object> normalizedSpec = normalizeSpec(request.getSpec());

            if (normalizedSpec == null || normalizedSpec.isEmpty()) {
                throw new IllegalArgumentException("A especificação JOLT (spec) não pode ser vazia e deve ser um array válido.");
            }

            // Filtra operações exclusivas de emulação customizada do frontend (ex: custom-decode)
            // mantendo apenas operações suportadas nativamente pelo Chainr da Bazaarvoice
            List<Object> executableSpec = filterSupportedOperations(normalizedSpec);

            // Se a spec continha custom-decode de Base64, podemos pré-processar no Java
            normalizedInput = handleBase64Preprocess(normalizedInput, normalizedSpec);

            Chainr chainr = Chainr.fromSpec(executableSpec);
            Object result = chainr.transform(normalizedInput);

            long duration = System.currentTimeMillis() - startTime;
            log.info("Transformação JOLT (Bazaarvoice) executada com sucesso em {} ms", duration);

            return JoltTransformResponseDto.builder()
                    .success(true)
                    .output(result)
                    .executionTimeMs(duration)
                    .engine("JAVA_BAZAARVOICE")
                    .build();

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Erro na transformação JOLT oficial: {}", e.getMessage(), e);

            return JoltTransformResponseDto.builder()
                    .success(false)
                    .output(null)
                    .error(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                    .executionTimeMs(duration)
                    .engine("JAVA_BAZAARVOICE")
                    .build();
        }
    }

    private Object normalizeInput(Object rawInput) throws Exception {
        if (rawInput == null) return Collections.emptyMap();
        if (rawInput instanceof String str) {
            String trimmed = str.trim();
            if (trimmed.isEmpty()) return Collections.emptyMap();
            return objectMapper.readValue(trimmed, Object.class);
        }
        return rawInput;
    }

    @SuppressWarnings("unchecked")
    private List<Object> normalizeSpec(Object rawSpec) throws Exception {
        if (rawSpec == null) return Collections.emptyList();

        Object parsed = rawSpec;
        if (rawSpec instanceof String str) {
            String trimmed = str.trim();
            if (trimmed.isEmpty()) return Collections.emptyList();
            parsed = objectMapper.readValue(trimmed, Object.class);
        }

        // Se for um envelope completo de layout TOTVS SmartHub
        if (parsed instanceof Map<?, ?> map) {
            Object candidate = null;
            if (map.containsKey("tabela")) {
                Object tabelaObj = map.get("tabela");
                if (tabelaObj instanceof Map<?, ?> tabelaMap && tabelaMap.get("campos") instanceof List<?> campos) {
                    candidate = extractLayoutTransformacao(campos);
                }
            } else if (map.containsKey("campos") && map.get("campos") instanceof List<?> campos) {
                candidate = extractLayoutTransformacao(campos);
            } else if (map.containsKey("LAYOUTTRANSFORMACAO")) {
                candidate = map.get("LAYOUTTRANSFORMACAO");
            }

            if (candidate != null) {
                if (candidate instanceof String candidateStr) {
                    try {
                        parsed = objectMapper.readValue(candidateStr, Object.class);
                    } catch (Exception ignored) {
                        parsed = candidate;
                    }
                } else {
                    parsed = candidate;
                }
            }
        }

        if (parsed instanceof List<?> list) {
            return (List<Object>) list;
        }

        return Collections.emptyList();
    }

    private Object extractLayoutTransformacao(List<?> campos) {
        for (Object campo : campos) {
            if (campo instanceof Map<?, ?> campoMap) {
                Object nome = campoMap.get("nome");
                if ("LAYOUTTRANSFORMACAO".equalsIgnoreCase(String.valueOf(nome))) {
                    return campoMap.get("valor");
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Object> filterSupportedOperations(List<Object> specList) {
        List<Object> filtered = new ArrayList<>();
        Set<String> standardOps = Set.of(
                "shift", "default", "remove", "sort", "cardinality",
                "modify-overwrite-beta", "modify-default-beta"
        );

        for (Object item : specList) {
            if (item instanceof Map<?, ?> opMap) {
                Object op = opMap.get("operation");
                if (op != null && standardOps.contains(String.valueOf(op).toLowerCase())) {
                    filtered.add(opMap);
                }
            } else {
                filtered.add(item);
            }
        }
        return filtered;
    }

    @SuppressWarnings("unchecked")
    private Object handleBase64Preprocess(Object input, List<Object> specList) {
        // Se houver diretiva de custom-decode, decodifica campos Base64
        for (Object item : specList) {
            if (item instanceof Map<?, ?> opMap) {
                Object op = opMap.get("operation");
                if ("custom-decode".equalsIgnoreCase(String.valueOf(op)) || "custom-totvs".equalsIgnoreCase(String.valueOf(op))) {
                    Object subSpec = opMap.get("spec");
                    if (subSpec instanceof Map<?, ?> fieldMap && input instanceof Map<?, ?> inputMap) {
                        return decodeBase64InMap(new LinkedHashMap<>(inputMap), (Map<String, Object>) fieldMap);
                    }
                }
            }
        }
        return input;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decodeBase64InMap(Map<String, Object> inputMap, Map<String, Object> fieldRules) {
        for (Map.Entry<String, Object> entry : fieldRules.entrySet()) {
            String field = entry.getKey();
            Object val = inputMap.get(field);
            if (val instanceof String strVal) {
                try {
                    byte[] decoded = Base64.getDecoder().decode(strVal.trim());
                    String decodedStr = new String(decoded, StandardCharsets.UTF_8);
                    try {
                        inputMap.put(field, objectMapper.readValue(decodedStr, Object.class));
                    } catch (Exception e) {
                        inputMap.put(field, decodedStr);
                    }
                } catch (Exception ignored) {
                    // Mantém o valor original se não for Base64 válido
                }
            } else if (val instanceof Map<?, ?> nested && entry.getValue() instanceof Map<?, ?> nestedRules) {
                decodeBase64InMap((Map<String, Object>) nested, (Map<String, Object>) nestedRules);
            }
        }
        return inputMap;
    }
}
