package com.agilespace.backend.service.jolt;

import com.agilespace.backend.dto.JoltTransformRequestDto;
import com.agilespace.backend.dto.JoltTransformResponseDto;
import com.agilespace.backend.service.JoltJavaEngineService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

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
    /** Opcional: sem ele (testes de unidade), o gerador funciona só com o glossário fixo. */
    private JoltGlossaryService glossary;

    @Autowired(required = false)
    void setGlossary(JoltGlossaryService glossary) {
        this.glossary = glossary;
    }

    /** Tipos de escolha cuja origem vira par do glossário. */
    static final Set<String> LEARNABLE = Set.of("COPY", "AS_TEXT", "AS_NUMBER", "DIGITS", "VALUE_MAP");

    public record Difference(String path, String kind, JsonNode expected, JsonNode actual) {
    }

    public record Validation(boolean ran, boolean matches, String error, List<Difference> differences, int checkedFields) {
    }

    public record Generated(JsonNode spec, List<JoltSpecGenerator.Rule> rules, Validation validation, int pending,
                            Map<String, List<String>> sources, JoltSpecGenerator.Mode mode) {
    }

    public Generated generate(JsonNode input, JsonNode expected) {
        return generate(input, expected, Map.of());
    }

    /** Com as escolhas da pessoa ({@code overrides}, por campo da saída): refaz a spec e revalida. */
    public Generated generate(JsonNode input, JsonNode expected, Map<String, JoltSpecGenerator.Override> overrides) {
        return generate(input, expected, overrides, false);
    }

    /**
     * {@code learn}: se a spec cumprir a saída/contrato, as origens que a pessoa escolheu entram no glossário da
     * equipe. A tela pede isso só no "Gerar spec" final, não a cada ajuste.
     */
    public Generated generate(JsonNode input, JsonNode expected, Map<String, JoltSpecGenerator.Override> overrides,
                              boolean learn) {
        Map<String, Set<String>> learned = glossary == null ? Map.of() : glossary.load();
        JoltSpecGenerator.Result result = JoltSpecGenerator.generate(input, expected, overrides, learned);
        Validation validation = validate(input, result.spec(), expected, result.mode());
        if (learn && glossary != null && validation.matches()) glossary.learn(learnablePairs(overrides));
        int pending = (int) result.rules().stream().filter(JoltSpecGenerator.Rule::pending).count();
        return new Generated(result.spec(), JoltSpecGenerator.pendingFirst(result.rules()), validation, pending,
                result.sources(), result.mode());
    }

    /** Par (último nome do campo da saída, último nome da origem) de cada escolha com origem. */
    static List<JoltGlossaryService.Pair> learnablePairs(Map<String, JoltSpecGenerator.Override> overrides) {
        Function<String, String> last = f -> JoltSpecGenerator.lastFieldOf(f.replace("[]", ""));
        List<JoltGlossaryService.Pair> pairs = new ArrayList<>();
        if (overrides == null) return pairs;
        overrides.forEach((field, o) -> {
            if (o == null || o.source() == null || o.kind() == null || !LEARNABLE.contains(o.kind())) return;
            pairs.add(new JoltGlossaryService.Pair(last.apply(field), last.apply(o.source())));
        });
        return pairs;
    }

    public Validation validate(JsonNode input, JsonNode spec, JsonNode expected) {
        return validate(input, spec, expected, JoltSpecGenerator.Mode.VALORES);
    }

    /** CONTRATO: confere que cada campo do contrato existe com o tipo certo (texto, número, booleano), sem campo a mais. */
    public Validation validate(JsonNode input, JsonNode spec, JsonNode expected, JoltSpecGenerator.Mode mode) {
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
        if (mode == JoltSpecGenerator.Mode.CONTRATO) compareContract("", expected, actual, diffs, checked);
        else compare("", expected, actual, diffs, checked);
        return new Validation(true, diffs.isEmpty(), null, diffs, checked[0]);
    }

    static void compareContract(String path, JsonNode expected, JsonNode actual, List<Difference> out, int[] checked) {
        if (out.size() >= MAX_DIFFERENCES) return;
        if (actual == null || actual.isMissingNode() || actual.isNull()) {
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
                if (!expected.has(k)) out.add(new Difference(p, "a mais na saída gerada", null, actual.get(k)));
                else compareContract(p, expected.get(k), actual.get(k), out, checked);
            }
            return;
        }
        if (expected.isArray() && actual.isArray()) {
            // O contrato mostra um item de exemplo; cada item gerado tem que seguir esse formato.
            if (expected.isEmpty()) return;
            if (actual.isEmpty()) {
                out.add(new Difference(path, "lista vazia na saída gerada", expected, actual));
                return;
            }
            for (int i = 0; i < actual.size(); i++) compareContract(path + "[" + i + "]", expected.get(0), actual.get(i), out, checked);
            return;
        }
        checked[0]++;
        if (!JoltSpecGenerator.isPlaceholder(expected)) {
            // Valor concreto no contrato (fixo, variável): tem que ser igual.
            boolean same = expected.isNumber() && actual.isNumber()
                    ? expected.decimalValue().compareTo(actual.decimalValue()) == 0 : expected.equals(actual);
            if (!same) out.add(new Difference(path, "valor diferente do contrato", expected, actual));
            return;
        }
        boolean sameType = expected.isTextual() ? actual.isTextual()
                : expected.isNumber() ? actual.isNumber()
                : !expected.isBoolean() || actual.isBoolean();
        if (!sameType) out.add(new Difference(path, "tipo diferente do contrato", expected, actual));
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
