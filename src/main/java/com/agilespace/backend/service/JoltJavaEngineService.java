package com.agilespace.backend.service;

import com.agilespace.backend.dto.JoltTransformRequestDto;
import com.agilespace.backend.dto.JoltTransformResponseDto;
import com.bazaarvoice.jolt.Chainr;
import com.bazaarvoice.jolt.exception.JoltException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class JoltJavaEngineService {

    /** Limites de entrada: o endpoint é público (ver JwtAuthenticationFilter), então precisa ser barato de recusar. */
    static final int MAX_INPUT_NODES = 200_000;
    static final int MAX_SPEC_NODES = 20_000;
    static final int MAX_DEPTH = 64;
    static final long MAX_TEXT_CHARS = 2_000_000L;
    static final int MAX_SPEC_OPERATIONS = 50;
    static final long TIMEOUT_MS = 10_000L;
    private static final int MAX_ERROR_CHARS = 400;

    private final ObjectMapper objectMapper;

    /**
     * Pool limitado: o Jolt não é interrompível, então uma transformação travada ocupa uma thread.
     * Com fila curta, o excesso é recusado com "servidor ocupado" em vez de acumular trabalho.
     */
    private final ExecutorService executor = new ThreadPoolExecutor(
            2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
            r -> {
                Thread t = new Thread(r, "jolt-transform");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    /** Erro de entrada do usuário (mensagem segura para devolver ao cliente). */
    static class JoltInputException extends RuntimeException {
        JoltInputException(String message) {
            super(message);
        }
    }

    /**
     * Executa a transformação JOLT utilizando a biblioteca oficial da Bazaarvoice.
     * Entrada e spec têm limite de tamanho/profundidade, a execução tem tempo máximo e
     * falhas inesperadas voltam como mensagem genérica (detalhe só no log).
     */
    public JoltTransformResponseDto transform(JoltTransformRequestDto request) {
        long startTime = System.currentTimeMillis();
        try {
            Future<Object> future;
            try {
                future = executor.submit(() -> run(request));
            } catch (RejectedExecutionException busy) {
                return failure("O motor JOLT está ocupado. Tente novamente em instantes.", startTime);
            }
            Object result;
            try {
                result = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                future.cancel(true);
                log.warn("Transformação JOLT excedeu {} ms e foi abandonada", TIMEOUT_MS);
                return failure("A transformação excedeu o tempo máximo de " + (TIMEOUT_MS / 1000)
                        + " segundos. Simplifique a especificação ou reduza o payload.", startTime);
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof Exception ex) throw ex;
                throw ee;
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("Transformação JOLT (Bazaarvoice) executada com sucesso em {} ms", duration);

            return JoltTransformResponseDto.builder()
                    .success(true)
                    .output(result)
                    .executionTimeMs(duration)
                    .engine("JAVA_BAZAARVOICE")
                    .build();

        } catch (JoltInputException | IllegalArgumentException e) {
            log.info("Transformação JOLT recusada: {}", e.getMessage());
            return failure(safeMessage(e.getMessage()), startTime);
        } catch (JsonProcessingException e) {
            return failure("JSON inválido: " + safeMessage(e.getOriginalMessage()), startTime);
        } catch (JoltException e) {
            // Erros do Jolt descrevem a especificação do próprio usuário; seguros de devolver.
            log.info("Erro de especificação JOLT: {}", e.getMessage());
            return failure(safeMessage(e.getMessage()), startTime);
        } catch (Exception e) {
            // Falha inesperada: detalhe só no log; o cliente recebe mensagem genérica.
            log.error("Erro inesperado na transformação JOLT", e);
            return failure("Falha ao executar a transformação. Verifique a entrada e a especificação.", startTime);
        }
    }

    private Object run(JoltTransformRequestDto request) throws Exception {
        Object normalizedInput = normalizeInput(request.getInput());
        List<Object> normalizedSpec = normalizeSpec(request.getSpec());

        if (normalizedSpec == null || normalizedSpec.isEmpty()) {
            throw new IllegalArgumentException("A especificação JOLT (spec) não pode ser vazia e deve ser um array válido.");
        }
        if (normalizedSpec.size() > MAX_SPEC_OPERATIONS) {
            throw new JoltInputException("A especificação tem operações demais (máximo " + MAX_SPEC_OPERATIONS + ").");
        }
        checkSize(normalizedInput, MAX_INPUT_NODES, "O payload de entrada");
        checkSize(normalizedSpec, MAX_SPEC_NODES, "A especificação");

        // Filtra operações exclusivas de emulação customizada do frontend (ex: custom-decode)
        // mantendo apenas operações suportadas nativamente pelo Chainr da Bazaarvoice
        List<Object> executableSpec = filterSupportedOperations(normalizedSpec);

        // Se a spec continha custom-decode de Base64, podemos pré-processar no Java
        normalizedInput = handleBase64Preprocess(normalizedInput, normalizedSpec);
        checkSize(normalizedInput, MAX_INPUT_NODES, "O payload de entrada");

        Chainr chainr = Chainr.fromSpec(executableSpec);
        return chainr.transform(normalizedInput);
    }

    private JoltTransformResponseDto failure(String message, long startTime) {
        return JoltTransformResponseDto.builder()
                .success(false)
                .output(null)
                .error(message)
                .executionTimeMs(System.currentTimeMillis() - startTime)
                .engine("JAVA_BAZAARVOICE")
                .build();
    }

    /** Uma linha, sem trecho de código-fonte do Jackson, com tamanho limitado. */
    static String safeMessage(String raw) {
        if (raw == null || raw.isBlank()) return "Entrada inválida.";
        String msg = raw;
        int nl = msg.indexOf('\n');
        if (nl >= 0) msg = msg.substring(0, nl);
        msg = msg.trim();
        return msg.length() > MAX_ERROR_CHARS ? msg.substring(0, MAX_ERROR_CHARS) + "…" : msg;
    }

    /** Percorre a árvore sem recursão: limita profundidade, quantidade de nós e total de texto. */
    static void checkSize(Object root, int maxNodes, String label) {
        Deque<Object[]> stack = new ArrayDeque<>();
        stack.push(new Object[]{root, 1});
        int nodes = 0;
        long chars = 0;
        while (!stack.isEmpty()) {
            Object[] item = stack.pop();
            Object node = item[0];
            int depth = (Integer) item[1];
            if (++nodes > maxNodes) {
                throw new JoltInputException(label + " é grande demais (máximo de " + maxNodes + " elementos).");
            }
            if (depth > MAX_DEPTH) {
                throw new JoltInputException(label + " tem aninhamento profundo demais (máximo " + MAX_DEPTH + " níveis).");
            }
            if (node instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    chars += String.valueOf(e.getKey()).length();
                    stack.push(new Object[]{e.getValue(), depth + 1});
                }
            } else if (node instanceof Collection<?> c) {
                for (Object o : c) stack.push(new Object[]{o, depth + 1});
            } else if (node instanceof CharSequence cs) {
                chars += cs.length();
            }
            if (chars > MAX_TEXT_CHARS) {
                throw new JoltInputException(label + " excede o limite de texto (" + (MAX_TEXT_CHARS / 1_000_000) + " MB).");
            }
        }
    }

    private Object normalizeInput(Object rawInput) throws Exception {
        if (rawInput == null) return Collections.emptyMap();
        if (rawInput instanceof String str) {
            String trimmed = str.trim();
            if (trimmed.isEmpty()) return Collections.emptyMap();
            if (trimmed.length() > MAX_TEXT_CHARS) {
                throw new JoltInputException("O payload de entrada excede o limite de " + (MAX_TEXT_CHARS / 1_000_000) + " MB.");
            }
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
            if (trimmed.length() > MAX_TEXT_CHARS) {
                throw new JoltInputException("A especificação excede o limite de " + (MAX_TEXT_CHARS / 1_000_000) + " MB.");
            }
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
                    if (candidateStr.length() > MAX_TEXT_CHARS) {
                        throw new JoltInputException("A especificação excede o limite de " + (MAX_TEXT_CHARS / 1_000_000) + " MB.");
                    }
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
                        return decodeBase64InMap(new LinkedHashMap<String, Object>((Map<String, Object>) inputMap), (Map<String, Object>) fieldMap);
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
