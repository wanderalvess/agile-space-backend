package com.agilespace.backend.service.jolt;

import com.agilespace.backend.dto.JoltTransformRequestDto;
import com.agilespace.backend.dto.JoltTransformResponseDto;
import com.agilespace.backend.service.JoltJavaEngineService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gera a spec a partir do par entrada/saída e prova o resultado: roda a spec gerada no motor Java (o mesmo do
 * {@code /api/jolt/transform}) e compara com a saída esperada, campo a campo (ordem de chave não conta).
 */
@Service
@RequiredArgsConstructor
public class JoltSpecGeneratorService {

    static final int MAX_DIFFERENCES = 50;

    private final JoltJavaEngineService engine;
    private final ObjectMapper objectMapper;

    public record Difference(String path, String kind, JsonNode expected, JsonNode actual) {
    }

    public record Validation(boolean ran, boolean matches, String error, List<Difference> differences, int checkedFields) {
    }

    public record Generated(JsonNode spec, List<JoltSpecGenerator.Rule> rules, Validation validation, int pending) {
    }

    public Generated generate(JsonNode input, JsonNode expected) {
        JoltSpecGenerator.Result result = JoltSpecGenerator.generate(input, expected);
        Validation validation = validate(input, result.spec(), expected);
        int pending = (int) result.rules().stream().filter(JoltSpecGenerator.Rule::pending).count();
        return new Generated(result.spec(), JoltSpecGenerator.pendingFirst(result.rules()), validation, pending);
    }

    public Validation validate(JsonNode input, JsonNode spec, JsonNode expected) {
        JoltTransformResponseDto response = engine.transform(JoltTransformRequestDto.builder()
                .input(objectMapper.convertValue(input, Object.class))
                .spec(objectMapper.convertValue(spec, Object.class))
                .build());
        if (!response.isSuccess()) {
            return new Validation(true, false, response.getError(), List.of(), 0);
        }
        JsonNode actual = objectMapper.valueToTree(response.getOutput());
        List<Difference> diffs = new ArrayList<>();
        int[] checked = {0};
        compare("", expected, actual, diffs, checked);
        return new Validation(true, diffs.isEmpty(), null, diffs, checked[0]);
    }

    /** Compara como JSON: números pelo valor (5.5 = 5.50), mas tipo diferente (150 x "150") conta. */
    static void compare(String path, JsonNode expected, JsonNode actual, List<Difference> out, int[] checked) {
        if (out.size() >= MAX_DIFFERENCES) return;
        if (actual == null || actual.isMissingNode()) {
            out.add(new Difference(path, "faltou na saída gerada", expected, null));
            return;
        }
        if (expected.isObject() && actual.isObject()) {
            Set<String> keys = new LinkedHashSet<>();
            expected.fieldNames().forEachRemaining(keys::add);
            Iterator<Map.Entry<String, JsonNode>> it = actual.fields();
            while (it.hasNext()) keys.add(it.next().getKey());
            for (String k : keys) {
                String p = path.isEmpty() ? k : path + "." + k;
                if (!expected.has(k)) {
                    out.add(new Difference(p, "a mais na saída gerada", null, actual.get(k)));
                } else {
                    compare(p, expected.get(k), actual.get(k), out, checked);
                }
            }
            return;
        }
        if (expected.isArray() && actual.isArray()) {
            if (expected.size() != actual.size()) {
                out.add(new Difference(path, "lista com tamanho diferente", expected, actual));
                return;
            }
            for (int i = 0; i < expected.size(); i++) compare(path + "[" + i + "]", expected.get(i), actual.get(i), out, checked);
            return;
        }
        checked[0]++;
        boolean same = expected.isNumber() && actual.isNumber()
                ? expected.decimalValue().compareTo(actual.decimalValue()) == 0
                : expected.equals(actual);
        if (!same) out.add(new Difference(path, "valor diferente", expected, actual));
    }
}
