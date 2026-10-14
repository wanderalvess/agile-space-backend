package com.agilespace.backend.service.jolt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Gera uma spec Jolt a partir de um par de exemplo: a entrada (resposta da API de origem) e a saída que a API de destino
 * espera. Para cada campo da saída procura a regra que reproduz o valor em TODOS os itens das listas alinhadas:
 * cópia, como texto, só dígitos, concatenação (inclusive com campos do item pai), de-para pelo nome do campo, ou valor
 * fixo/variável da rota. Valores fracos (0, 1, false, "", siglas curtas) aparecem em dezenas de campos e só viram cópia
 * quando o nome do campo também bate.
 *
 * <p>A spec sai em até quatro passos: {@code modify-overwrite-beta} (campos calculados, em campos temporários
 * {@code jgen_N} (chave que começa com "_" é ignorada pelo Jolt) no próprio item), {@code shift} (o mapeamento), {@code modify-overwrite-beta} (tipo de saída dos de-para)
 * e {@code default} (fixos e variáveis). O que um exemplo só não mostra (condições, filtros, o resto de um de-para) volta
 * como regra pendente para a pessoa confirmar.
 *
 * <p>Classe pura (sem Spring): quem valida rodando no motor é {@code JoltSpecGeneratorService}.
 */
public final class JoltSpecGenerator {

    public enum Kind { COPY, AS_TEXT, AS_NUMBER, DIGITS, CONCAT, VALUE_MAP, CONSTANT, VARIABLE, LIST, UNRESOLVED }

    public enum Confidence { HIGH, MEDIUM, LOW, NONE }

    /** Uma regra inferida, como a tela mostra: campo da saída, de onde vem, como, e se precisa de confirmação. */
    /** {@code scope}: a lista da saída a que o campo pertence ("" = raiz, "items[]", "items[].produtoEmbalagens[]"). */
    public record Rule(String output, String scope, Kind kind, String source, String detail, Confidence confidence,
                       boolean pending, String reason, List<String> alternatives, String example) {
    }

    /**
     * Escolha da pessoa para um campo, indexada pelo {@link Rule#output()}: {@code kind} COPY, AS_TEXT, DIGITS ou VALUE_MAP
     * com {@code source} (uma das origens de {@link Result#sources()} da mesma lista); CONSTANT com {@code value};
     * VALUE_MAP com {@code map} (valor da entrada → valor da saída) e, opcional, {@code otherwise} (qualquer outro valor).
     */
    public record Override(String kind, String source, JsonNode value, Map<String, JsonNode> map, JsonNode otherwise) {
    }

    /** {@code sources}: por lista da saída, as origens que a pessoa pode escolher (mesma grafia de {@link Rule#source()}). */
    public record Result(ArrayNode spec, List<Rule> rules, Map<String, List<String>> sources, Mode mode) {
    }

    /**
     * VALORES: a saída de exemplo traz os valores do mesmo registro da entrada (regras saem pelo valor).
     * CONTRATO: a saída é o exemplo do contrato do destino, com valores genéricos ("string", 0, true): as regras saem
     * pelo nome do campo e pelo tipo, e a validação confere campos e tipos, não valores.
     */
    public enum Mode { VALORES, CONTRATO }

    private static final Pattern PLACEHOLDER_TEXT =
            Pattern.compile("^(string|str|text|texto|number|numero|integer|int|long|double|decimal|boolean|bool|date|datetime|date-time|uuid)?$",
                    Pattern.CASE_INSENSITIVE);

    /** Valor de contrato: "string" (ou o nome de um tipo, ou vazio), 0, true/false. */
    public static boolean isPlaceholder(JsonNode v) {
        if (v == null || v.isNull() || v.isBoolean()) return true;
        if (v.isNumber()) return v.asDouble() == 0;
        return v.isTextual() && PLACEHOLDER_TEXT.matcher(v.asText().trim()).matches();
    }

    /** Contrato quando a maioria dos textos da saída é genérica ("string"). Variáveis {{X}} não contam. */
    static Mode detectMode(JsonNode output) {
        int[] texts = {0, 0};
        countTexts(output, texts);
        return texts[0] >= 3 && texts[1] * 10 >= texts[0] * 6 ? Mode.CONTRATO : Mode.VALORES;
    }

    private static void countTexts(JsonNode n, int[] acc) {
        if (n.isContainerNode()) {
            n.elements().forEachRemaining(c -> countTexts(c, acc));
        } else if (n.isTextual() && !VARIABLE.matcher(n.asText()).matches()) {
            acc[0]++;
            if (isPlaceholder(n)) acc[1]++;
        }
    }

    static final int MAX_SOURCES = 4000;
    static final int MAX_LIST_SCAN = 200;
    private static final int MAX_CONCAT_TRIES = 4000;
    private static final Pattern VARIABLE = Pattern.compile("^\\{\\{[A-Za-z0-9_]+}}$");
    private static final Pattern DIGITS_ONLY = Pattern.compile("^[0-9]{5,}$");
    private static final Pattern SHIFT_SAFE_KEY = Pattern.compile("^[^*&@$#\\[\\]().,]+$");
    private static final Pattern CONCAT_SAFE_LITERAL = Pattern.compile("^[^',()@]*$");

    private static final Set<String> GENERIC = Set.of("id", "codigo", "cod", "retaguarda", "valor", "de", "do", "da",
            "dos", "das", "por", "is", "the", "of", "e");

    private static final Map<String, String> GLOSSARY = Map.ofEntries(
            Map.entry("name", "nome"), Map.entry("address", "endereco"), Map.entry("district", "bairro"),
            Map.entry("city", "cidade"), Map.entry("state", "estado"), Map.entry("zip", "cep"),
            Map.entry("zipcode", "cep"), Map.entry("phone", "telefone"), Map.entry("number", "numero"),
            Map.entry("birth", "nascimento"), Map.entry("region", "regiao"), Map.entry("square", "praca"),
            Map.entry("activity", "atividade"), Map.entry("ramo", "atividade"), Map.entry("blocked", "bloqueado"),
            Map.entry("active", "situacao"), Map.entry("ativo", "situacao"), Map.entry("create", "cadastro"),
            Map.entry("created", "cadastro"), Map.entry("date", "data"), Map.entry("expiration", "vencimento"),
            Map.entry("days", "dias"), Map.entry("percentage", "percentual"), Map.entry("unit", "unidade"),
            Map.entry("barcode", "barras"), Map.entry("barra", "barras"), Map.entry("description", "descricao"),
            Map.entry("brand", "marca"), Map.entry("supplier", "fornecedor"), Map.entry("quantity", "quantidade"),
            Map.entry("price", "preco"), Map.entry("wholesale", "atacado"), Map.entry("uses", "utiliza"),
            Map.entry("complement", "complemento"), Map.entry("prod", "produto"), Map.entry("product", "produto"),
            Map.entry("fracao", "fracionado"), Map.entry("fracionamento", "fracionado"));

    // ------------------------------------------------------------------ estrutura interna

    /** Um par de nós alinhados: o item da entrada, o item da saída e os itens das listas acima (o mais próximo antes). */
    private record Pair(JsonNode in, JsonNode out, List<JsonNode> ancestors) {
    }

    /** Lista da saída: a chave (caminho dentro do item de cima) e a posição do "*" correspondente no padrão de entrada. */
    private record OutLevel(String key, int wildcardPos) {
    }

    /**
     * Escopo = um nível de lista. {@code inPattern} é o caminho da raiz da entrada até o item ("items", "*", ...);
     * {@code ancestorLens.get(i)} é o tamanho do prefixo de {@code inPattern} onde está o i-ésimo ancestral.
     */
    private record Scope(List<String> inPattern, List<OutLevel> outLevels, List<Pair> pairs, List<Integer> ancestorLens,
                         String display) {
    }

    /** Uma origem possível: nível (0 = o próprio item, n = n-ésimo item acima), caminho, e o valor em cada par. */
    private record Src(int level, List<Object> path, List<JsonNode> values) {
        boolean hasIndex() {
            return path.stream().anyMatch(p -> p instanceof Integer);
        }

        String pathString() {
            StringBuilder sb = new StringBuilder();
            for (Object p : path) {
                if (p instanceof Integer i) {
                    sb.append('[').append(i).append(']');
                } else {
                    if (sb.length() > 0) sb.append('.');
                    sb.append(p);
                }
            }
            return sb.toString();
        }

        String lastKey() {
            for (int i = path.size() - 1; i >= 0; i--) {
                if (path.get(i) instanceof String s && !GENERIC.contains(normalize(s))) return s;
            }
            return path.isEmpty() ? "" : String.valueOf(path.get(path.size() - 1));
        }
    }

    /** Uma decisão para um campo, antes de virar spec. */
    private static final class Plan {
        Kind kind;
        Src source;
        List<Object> concatParts; // String (literal) ou Src
        Map<String, JsonNode> valueMap;
        JsonNode constant;
        JsonNode otherwise;
        Confidence confidence = Confidence.HIGH;
        String reason = "";
        List<String> alternatives = List.of();
        double score;
    }

    private final JsonNodeFactory nodes = JsonNodeFactory.instance;
    private final ObjectNode modify = nodes.objectNode();
    private final ObjectNode shift = nodes.objectNode();
    private final ObjectNode post = nodes.objectNode();
    private final ObjectNode defaults = nodes.objectNode();
    private final List<Rule> rules = new ArrayList<>();
    private final Map<String, Override> overrides;
    private Mode mode = Mode.VALORES;
    /** Glossário da equipe: campo da saída (termKey) → campos da entrada já ligados a ele em specs válidas. */
    private Map<String, Set<String>> learned = Map.of();
    /** Campo da saída em inferência agora (o glossário compara nomes inteiros, não palavras). */
    private String currentName;
    private final Map<String, List<String>> sourcesByScope = new LinkedHashMap<>();
    private int temp;
    static final int MAX_LISTED_SOURCES = 400;

    private JoltSpecGenerator(Map<String, Override> overrides) {
        this.overrides = overrides == null ? Map.of() : overrides;
    }

    public static Result generate(JsonNode input, JsonNode output) {
        return generate(input, output, Map.of());
    }

    public static Result generate(JsonNode input, JsonNode output, Map<String, Override> overrides) {
        return generate(input, output, overrides, Map.of());
    }

    public static Result generate(JsonNode input, JsonNode output, Map<String, Override> overrides,
                                  Map<String, Set<String>> learned) {
        if (input == null || !input.isContainerNode() || output == null || !output.isObject()) {
            throw new IllegalArgumentException("Entrada e saída precisam ser objetos ou listas JSON.");
        }
        JoltSpecGenerator g = new JoltSpecGenerator(overrides);
        g.learned = learned == null ? Map.of() : learned;
        g.mode = detectMode(output);
        Scope root = new Scope(List.of(), List.of(), List.of(new Pair(input, output, List.of())), List.of(), "");
        g.processRoot(root);
        return new Result(g.buildSpec(), List.copyOf(g.rules), Collections.unmodifiableMap(g.sourcesByScope), g.mode);
    }

    private void listSources(Scope scope, String key) {
        List<String> list = sourcesByScope.computeIfAbsent(key, k -> new ArrayList<>());
        for (Src s : sources(scope)) {
            if (list.size() >= MAX_LISTED_SOURCES) break;
            String d = display(s);
            if (!list.contains(d)) list.add(d);
        }
    }

    /**
     * Aplica a escolha da pessoa, se houver; senão infere. Devolve null quando a origem escolhida não existe nesta lista
     * (quem chama tenta outra lista ou registra como não resolvido).
     */
    private Plan decide(Scope scope, List<String> field, List<JsonNode> targets, String display) {
        Override o = overrides.get(display);
        if (o == null) return infer(scope, field, targets);
        String kind = o.kind() == null ? "" : o.kind().trim().toUpperCase(Locale.ROOT);
        Plan p = new Plan();
        p.reason = "definido por você";
        if (kind.equals("CONSTANT")) {
            p.kind = Kind.CONSTANT;
            p.constant = o.value() != null ? o.value() : targets.get(0);
            return p;
        }
        Kind k;
        try {
            k = Kind.valueOf(kind);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Tipo de regra inválido para " + display + ": " + o.kind());
        }
        if (k != Kind.COPY && k != Kind.AS_TEXT && k != Kind.AS_NUMBER && k != Kind.DIGITS && k != Kind.VALUE_MAP) {
            throw new IllegalArgumentException("Tipo de regra não aceito para " + display + ": " + o.kind());
        }
        Src chosen = null;
        for (Src s : sources(scope)) {
            if (display(s).equals(o.source())) {
                chosen = s;
                break;
            }
        }
        if (chosen == null) return null;
        p.kind = k;
        p.source = chosen;
        if (k == Kind.VALUE_MAP) {
            if (o.map() == null || o.map().isEmpty()) {
                throw new IllegalArgumentException("De-para sem valores para " + display);
            }
            for (String key : o.map().keySet()) {
                if (!SHIFT_SAFE_KEY.matcher(key).matches()) {
                    throw new IllegalArgumentException("Valor de entrada não aceito no de-para de " + display + ": " + key);
                }
            }
            p.valueMap = new LinkedHashMap<>(o.map());
            p.otherwise = o.otherwise();
        }
        return p;
    }

    // ------------------------------------------------------------------ escopos

    private void processRoot(Scope root) {
        JsonNode out = root.pairs().get(0).out();
        List<List<String>> leaves = new ArrayList<>();
        List<Scope> children = new ArrayList<>();
        collectOutput(out, new ArrayList<>(), leaves, root, children);

        Scope main = children.isEmpty() ? null : children.get(0);
        listSources(root, "");
        Scope mainFirst = main == null ? null : new Scope(main.inPattern(), main.outLevels(), List.of(main.pairs().get(0)),
                main.ancestorLens(), main.display());
        if (mainFirst != null) listSources(mainFirst, "");
        for (List<String> field : leaves) {
            JsonNode target = at(out, field);
            String display = String.join(".", field);
            if (overrides.containsKey(display)) {
                // Campo da raiz escolhido pela pessoa: a origem pode estar na raiz ou no item da lista principal.
                Plan chosen = decide(root, field, List.of(target), display);
                if (chosen != null) {
                    emit(root, field, chosen, display, false);
                    continue;
                }
                Plan fromItem = mainFirst == null ? null : decide(mainFirst, field, List.of(target), display);
                if (fromItem != null) {
                    emitAbsolute(mainFirst, field, fromItem, display, target.isArray());
                } else {
                    rules.add(new Rule(display, "", Kind.UNRESOLVED, overrides.get(display).source(), null, Confidence.NONE,
                            true, "a origem escolhida não existe nesta entrada", List.of(), null));
                }
                continue;
            }
            if (target.isArray() && main != null && target.size() == main.pairs().size() && allScalar(target)) {
                List<JsonNode> targets = new ArrayList<>();
                target.forEach(targets::add);
                Plan plan = infer(main, field, targets);
                if (plan.kind == Kind.CONSTANT || plan.kind == Kind.UNRESOLVED) {
                    plan = constantOrUnresolved(List.of(target));
                    emit(root, field, plan, display, false);
                } else {
                    emitAbsolute(main, field, plan, display, true);
                }
                continue;
            }
            Plan atRoot = infer(root, field, List.of(target));
            if (atRoot.kind != Kind.CONSTANT && atRoot.kind != Kind.VARIABLE && atRoot.kind != Kind.UNRESOLVED) {
                emit(root, field, atRoot, display, false);
                continue;
            }
            if (main != null && !target.isContainerNode() && atRoot.kind != Kind.VARIABLE) {
                Scope first = new Scope(main.inPattern(), main.outLevels(), List.of(main.pairs().get(0)),
                        main.ancestorLens(), main.display());
                Plan fromItem = infer(first, field, List.of(target));
                if (fromItem.kind != Kind.CONSTANT && fromItem.kind != Kind.UNRESOLVED && fromItem.kind != Kind.VARIABLE) {
                    emitAbsolute(first, field, fromItem, display, false);
                    continue;
                }
            }
            emit(root, field, atRoot, display, false);
        }
        for (Scope child : children) {
            processScope(child);
        }
    }

    private void processScope(Scope scope) {
        listSources(scope, scope.display());
        JsonNode out = scope.pairs().get(0).out();
        List<List<String>> leaves = new ArrayList<>();
        List<Scope> children = new ArrayList<>();
        collectOutput(out, new ArrayList<>(), leaves, scope, children);
        boolean mapped = false;
        List<String> firstConstant = null;
        JsonNode firstConstantValue = null;
        for (List<String> field : leaves) {
            List<JsonNode> targets = new ArrayList<>();
            for (Pair p : scope.pairs()) targets.add(at(p.out(), field));
            String display = scope.display() + "." + String.join(".", field);
            Plan plan = decide(scope, field, targets, display);
            if (plan == null) {
                rules.add(new Rule(display, scope.display(), Kind.UNRESOLVED, overrides.get(display).source(), null,
                        Confidence.NONE, true, "a origem escolhida não existe nesta lista", List.of(), null));
                continue;
            }
            emit(scope, field, plan, display, false);
            if (plan.kind == Kind.CONSTANT || plan.kind == Kind.VARIABLE) {
                if (firstConstant == null && !(plan.constant.isTextual()
                        && (plan.constant.asText().startsWith("=") || plan.constant.asText().startsWith("@")))) {
                    firstConstant = field;
                    firstConstantValue = plan.constant;
                }
            } else if (plan.kind != Kind.UNRESOLVED) {
                mapped = true;
            }
        }
        // Lista só de valores fixos: o default não cria item em lista vazia. Um dos fixos vai pelo item da entrada
        // (modify + shift), o que cria um item na saída para cada item da entrada; o default completa o resto.
        if (!mapped && firstConstant != null) {
            String t = newTemp();
            modifyAt(scope).set(t, firstConstantValue.deepCopy());
            putShift(scope, List.of(t), dst(scope, firstConstant, 1, false, false));
        }
        for (Scope child : children) {
            processScope(child);
        }
    }

    /** Separa os campos-folha do item da saída e as listas de objetos (que viram escopos filhos alinhados). */
    private void collectOutput(JsonNode node, List<String> prefix, List<List<String>> leaves, Scope scope,
                               List<Scope> children) {
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            List<String> path = new ArrayList<>(prefix);
            path.add(e.getKey());
            JsonNode v = e.getValue();
            if (v.isObject() && v.size() > 0) {
                collectOutput(v, path, leaves, scope, children);
            } else if (v.isArray() && v.size() > 0 && allObjects(v)) {
                Scope child = alignList(scope, path);
                String display = (scope.display().isEmpty() ? "" : scope.display() + ".") + String.join(".", path) + "[]";
                if (child != null) {
                    children.add(child);
                } else {
                    rules.add(new Rule(display, scope.display(), Kind.UNRESOLVED, null, null, Confidence.NONE, true,
                            "nenhuma lista da entrada tem o mesmo número de itens e conteúdo parecido", List.of(), null));
                }
            } else {
                leaves.add(path);
            }
        }
    }

    /** Escolhe a lista da entrada que melhor explica a lista {@code outPath} da saída (mesmo tamanho, mais campos resolvidos). */
    private Scope alignList(Scope scope, List<String> outPath) {
        List<List<String>> candidates = new ArrayList<>();
        findInputLists(scope.pairs().get(0).in(), new ArrayList<>(), candidates, 0);
        Scope best = null;
        double bestScore = -1;
        String outName = outPath.get(outPath.size() - 1);
        for (List<String> inPath : candidates) {
            List<Pair> pairs = new ArrayList<>();
            boolean ok = true;
            for (Pair p : scope.pairs()) {
                JsonNode inList = at(p.in(), inPath);
                JsonNode outList = at(p.out(), outPath);
                boolean contractExample = mode == Mode.CONTRATO && outList.isArray() && outList.size() == 1
                        && inList.isArray() && inList.size() >= 1;
                if (!inList.isArray() || !outList.isArray() || !allObjects(inList)
                        || (inList.size() != outList.size() && !contractExample)) {
                    ok = false;
                    break;
                }
                List<JsonNode> anc = new ArrayList<>();
                anc.add(p.in());
                anc.addAll(p.ancestors());
                // Contrato: o exemplo tem um item só; o primeiro item da entrada representa a lista (a spec vale para todos).
                int n = contractExample ? 1 : inList.size();
                for (int i = 0; i < n; i++) pairs.add(new Pair(inList.get(i), outList.get(i), anc));
            }
            if (!ok || pairs.isEmpty()) continue;
            List<String> pattern = new ArrayList<>(scope.inPattern());
            pattern.addAll(inPath);
            pattern.add("*");
            List<Integer> lens = new ArrayList<>();
            lens.add(scope.inPattern().size());
            lens.addAll(scope.ancestorLens());
            List<OutLevel> levels = new ArrayList<>(scope.outLevels());
            levels.add(new OutLevel(String.join(".", outPath), pattern.size() - 1));
            String display = (scope.display().isEmpty() ? "" : scope.display() + ".") + String.join(".", outPath) + "[]";
            Scope child = new Scope(pattern, levels, pairs, lens, display);
            String inName = inPath.get(inPath.size() - 1);
            double nameSim = singular(normalize(outName)).equals(singular(normalize(inName))) ? 1.0
                    : similarity(tokens(outName), tokens(inName));
            double s = scoreScope(child) + 4 * nameSim;
            if (s > bestScore) {
                bestScore = s;
                best = child;
            }
        }
        return best;
    }

    private void findInputLists(JsonNode node, List<String> prefix, List<List<String>> out, int depth) {
        if (depth > 6 || !node.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            List<String> path = new ArrayList<>(prefix);
            path.add(e.getKey());
            if (e.getValue().isArray() && e.getValue().size() > 0 && allObjects(e.getValue())) {
                out.add(path);
            } else if (e.getValue().isObject()) {
                findInputLists(e.getValue(), path, out, depth + 1);
            }
        }
    }

    /** Quanto a lista candidata explica: cópias e cálculos valem mais que fixos. */
    private double scoreScope(Scope scope) {
        double total = 0;
        List<List<String>> leaves = new ArrayList<>();
        collectLeavesOnly(scope.pairs().get(0).out(), new ArrayList<>(), leaves);
        for (List<String> field : leaves) {
            List<JsonNode> targets = new ArrayList<>();
            for (Pair p : scope.pairs()) targets.add(at(p.out(), field));
            Plan plan = infer(scope, field, targets);
            // O que veio do item de cima explicaria qualquer lista: só conta o que sai da própria lista.
            boolean local = plan.source != null ? plan.source.level() == 0
                    : plan.concatParts != null && plan.concatParts.stream().anyMatch(o -> o instanceof Src src && src.level() == 0);
            if (!local) continue;
            total += switch (plan.kind) {
                case COPY, DIGITS, CONCAT -> plan.confidence == Confidence.LOW ? 1 : 3;
                case AS_TEXT, AS_NUMBER -> 2;
                case VALUE_MAP -> 1;
                default -> 0;
            };
        }
        return total;
    }

    private void collectLeavesOnly(JsonNode node, List<String> prefix, List<List<String>> leaves) {
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            List<String> path = new ArrayList<>(prefix);
            path.add(e.getKey());
            JsonNode v = e.getValue();
            if (v.isObject() && v.size() > 0) collectLeavesOnly(v, path, leaves);
            else if (!(v.isArray() && v.size() > 0 && allObjects(v))) leaves.add(path);
        }
    }

    // ------------------------------------------------------------------ origens

    private List<Src> sources(Scope scope) {
        List<Src> out = new ArrayList<>();
        Pair first = scope.pairs().get(0);
        List<List<Object>> paths = new ArrayList<>();
        leafPaths(first.in(), new ArrayList<>(), paths, scope.inPattern().isEmpty());
        for (List<Object> path : paths) addSource(scope, 0, path, out);
        for (int level = 1; level <= first.ancestors().size(); level++) {
            JsonNode anc = first.ancestors().get(level - 1);
            int ancLen = scope.ancestorLens().get(level - 1);
            int below = level == 1 ? scope.inPattern().size() : scope.ancestorLens().get(level - 2);
            // Não desce pela lista que leva ao item atual (seria o próprio item, por índice); o resto do caminho
            // (ex.: produto.descricao, ao lado de produto.embalagens) continua valendo.
            List<String> excluded = new ArrayList<>();
            for (int i = ancLen; i < below; i++) {
                if (!"*".equals(scope.inPattern().get(i))) excluded.add(scope.inPattern().get(i));
            }
            List<List<Object>> ancPaths = new ArrayList<>();
            leafPaths(anc, new ArrayList<>(), ancPaths, false);
            for (List<Object> path : ancPaths) {
                if (!excluded.isEmpty() && startsWith(path, excluded)) continue;
                addSource(scope, level, path, out);
            }
            if (out.size() > MAX_SOURCES) break;
        }
        return out;
    }

    private static boolean startsWith(List<Object> path, List<String> prefix) {
        if (path.size() < prefix.size()) return false;
        for (int i = 0; i < prefix.size(); i++) {
            if (!prefix.get(i).equals(path.get(i))) return false;
        }
        return true;
    }

    private void addSource(Scope scope, int level, List<Object> path, List<Src> out) {
        if (out.size() > MAX_SOURCES) return;
        List<JsonNode> values = new ArrayList<>();
        for (Pair p : scope.pairs()) {
            JsonNode base = level == 0 ? p.in() : p.ancestors().get(level - 1);
            JsonNode v = atAny(base, path);
            if (v.isMissingNode() || v.isNull() || v.isContainerNode()) return;
            values.add(v);
        }
        out.add(new Src(level, path, values));
    }

    private void leafPaths(JsonNode node, List<Object> prefix, List<List<Object>> out, boolean skipArrays) {
        if (out.size() > MAX_SOURCES || prefix.size() > 12) return;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                List<Object> path = new ArrayList<>(prefix);
                path.add(e.getKey());
                leafPaths(e.getValue(), path, out, skipArrays);
            }
        } else if (node.isArray()) {
            if (skipArrays) return;
            for (int i = 0; i < Math.min(node.size(), MAX_LIST_SCAN); i++) {
                List<Object> path = new ArrayList<>(prefix);
                path.add(i);
                leafPaths(node.get(i), path, out, false);
            }
        } else if (!node.isNull() && !prefix.isEmpty()) {
            out.add(prefix);
        }
    }

    // ------------------------------------------------------------------ inferência de um campo

    private Plan infer(Scope scope, List<String> field, List<JsonNode> targets) {
        JsonNode t0 = targets.get(0);
        String name = field.get(field.size() - 1);
        currentName = name;
        Set<String> nameTokens = tokens(name);

        if (allEqual(targets) && t0.isTextual() && VARIABLE.matcher(t0.asText()).matches()) {
            Plan p = new Plan();
            p.kind = Kind.VARIABLE;
            p.constant = t0;
            p.reason = "variável da rota";
            return p;
        }
        if (targets.stream().anyMatch(t -> t.isMissingNode() || t.isContainerNode())) {
            return constantOrUnresolved(targets);
        }
        if (mode == Mode.CONTRATO && targets.stream().allMatch(JoltSpecGenerator::isPlaceholder)) {
            return inferByName(scope, nameTokens, name, t0);
        }
        List<Src> all = sources(scope);
        // Fraco = valor comum (0, 1, false, "", sigla) E igual em todos os itens. Se muda de item para item, o campo
        // que acompanha cada valor é prova forte, mesmo que o valor seja curto.
        boolean weak = targets.stream().allMatch(JoltSpecGenerator::isWeak) && allEqual(targets);

        Plan copy = pickBest(all, targets, nameTokens, weak, Kind.COPY, this::sameValue);
        if (copy != null) return copy;
        if (t0.isTextual()) {
            Plan asText = pickBest(all, targets, nameTokens, weak, Kind.AS_TEXT,
                    (s, t) -> !s.isTextual() && s.isValueNode() && s.asText().equals(t.asText()));
            if (asText != null) return asText;
            if (targets.stream().allMatch(t -> DIGITS_ONLY.matcher(t.asText()).matches())) {
                Plan digits = pickBest(all, targets, nameTokens, false, Kind.DIGITS,
                        (s, t) -> s.isTextual() && !s.asText().equals(t.asText())
                                && s.asText().replaceAll("\\D", "").equals(t.asText()));
                if (digits != null) return digits;
            }
            if (!weak) {
                Plan concat = concat(all, targets);
                if (concat != null) return concat;
            }
        }
        Plan map = valueMap(all, targets, nameTokens);
        if (map != null) return map;
        Plan constant = constantOrUnresolved(targets);
        if (constant.kind == Kind.CONSTANT) {
            List<String> same = new ArrayList<>();
            for (Src s : all) {
                if (matchesAll(s, targets, this::sameValue)) same.add(display(s));
                if (same.size() >= 5) break;
            }
            if (!same.isEmpty()) {
                constant.confidence = Confidence.LOW;
                constant.reason = "valor fixo; o mesmo valor aparece em campos de nome diferente";
                constant.alternatives = same;
            } else {
                constant.reason = "não existe na entrada";
            }
        }
        return constant;
    }

    /**
     * Contrato sem valores: a origem é o campo da entrada de nome mais parecido (glossário, plural, prefixos), e a regra
     * sai do tipo que o contrato declara. Sem nome parecido fica sem origem, para a pessoa escolher.
     */
    private Plan inferByName(Scope scope, Set<String> nameTokens, String name, JsonNode t0) {
        List<Src> all = sources(scope);
        Src best = null;
        double bestScore = -1;
        double bestSim = 0;
        int ties = 0;
        boolean targetIsId = isIdName(name);
        for (Src s : all) {
            double sim = sourceSimilarity(nameTokens, s);
            // "cidade" x "cityId": o nome bate, mas um é o nome e o outro é o código. Origem id/código só serve a
            // campo do contrato que também é id/código.
            if (!targetIsId && isIdName(s.lastKey()) && !isLearned(s)) sim -= 0.4;
            if (sim < 0.5) continue;
            boolean sameKind = t0.isTextual() ? s.values().get(0).isTextual() : typeCompatible(s.values().get(0), t0);
            double score = rank(s, sim) + (sameKind ? 0.2 : 0);
            if (score > bestScore + 1e-9) {
                best = s;
                bestScore = score;
                bestSim = sim;
                ties = 0;
            } else if (Math.abs(score - bestScore) < 1e-9) {
                ties++;
            }
        }
        Plan p = new Plan();
        if (best == null) {
            p.kind = Kind.UNRESOLVED;
            p.confidence = Confidence.NONE;
            p.reason = "contrato sem valores e nenhum campo da entrada com nome parecido: escolha a origem";
            return p;
        }
        JsonNode v = best.values().get(0);
        p.source = best;
        if (t0.isTextual() && !v.isTextual()) p.kind = Kind.AS_TEXT;
        else if (t0.isNumber() && v.isTextual()) p.kind = Kind.AS_NUMBER;
        else p.kind = Kind.COPY;
        boolean sameName = normalize(name).equals(normalize(best.lastKey()));
        p.confidence = (sameName || bestSim >= 0.99) && ties == 0 ? Confidence.HIGH : bestSim >= 0.8 ? Confidence.MEDIUM : Confidence.LOW;
        p.reason = "pelo nome do campo (o contrato não traz valores)"
                + (typeCompatible(v, t0) ? "" : "; o tipo da entrada difere do contrato")
                + (ties > 0 ? "; outro campo tem nome igualmente parecido" : "");
        List<String> alts = new ArrayList<>();
        for (Src s : all) {
            double sim = sourceSimilarity(nameTokens, s) - (!targetIsId && isIdName(s.lastKey()) ? 0.4 : 0);
            if (s != best && sim >= 0.5) alts.add(display(s));
            if (alts.size() >= 4) break;
        }
        p.alternatives = alts;
        if (isLearned(best)) {
            p.confidence = Confidence.HIGH;
            p.reason = "ligado antes pela equipe (glossário)";
        }
        return p;
    }

    /** Nome de identificador: termina em "Id"/"ID", começa com "id"/"cod", ou é "codigo" + algo. */
    static boolean isIdName(String raw) {
        String n = raw.replaceAll("[^A-Za-z]", "");
        return n.matches("(?i)^(id|cod|codigo).*") || n.matches(".*[a-z](Id|ID|Cod|Codigo)$") || n.equalsIgnoreCase("id");
    }

    private static boolean typeCompatible(JsonNode source, JsonNode target) {
        if (target.isTextual()) return true; // tudo vira texto
        if (target.isNumber()) return source.isNumber() || (source.isTextual() && source.asText().matches("-?\\d+(\\.\\d+)?"));
        if (target.isBoolean()) return source.isBoolean();
        return true;
    }

    private interface ValueTest {
        boolean test(JsonNode source, JsonNode target);
    }

    private Plan pickBest(List<Src> all, List<JsonNode> targets, Set<String> nameTokens, boolean weak, Kind kind,
                          ValueTest test) {
        List<Src> matches = new ArrayList<>();
        for (Src s : all) if (matchesAll(s, targets, test)) matches.add(s);
        if (matches.isEmpty()) return null;
        Map<Src, Double> sims = new HashMap<>();
        for (Src s : matches) sims.put(s, sourceSimilarity(nameTokens, s));
        List<Src> ranked = new ArrayList<>(matches);
        ranked.sort(Comparator.comparingDouble((Src s) -> -rank(s, sims.get(s))));
        if (weak) {
            ranked.removeIf(s -> sims.get(s) < 0.5);
            if (ranked.isEmpty()) return null;
        }
        Src best = ranked.get(0);
        Plan p = new Plan();
        p.kind = kind;
        p.source = best;
        double sim = sims.get(best);
        long sameTop = ranked.stream().filter(s -> Math.abs(sims.get(s) - sim) < 1e-9).count();
        if (ranked.size() == 1 || (sim >= 0.8 && sameTop == 1)) {
            p.confidence = weak && sim < 0.8 ? Confidence.MEDIUM : Confidence.HIGH;
        } else {
            p.confidence = Confidence.MEDIUM;
        }
        p.reason = switch (kind) {
            case AS_TEXT -> "mesmo valor, convertido em texto";
            case DIGITS -> "mesmo valor sem a pontuação";
            default -> weak ? "valor comum; escolhido pelo nome do campo" : "mesmo valor";
        };
        if (ranked.size() > 1) {
            p.alternatives = ranked.subList(1, Math.min(ranked.size(), 5)).stream().map(this::display).toList();
            if (p.confidence != Confidence.HIGH) p.reason = p.reason + "; " + ranked.size() + " origens possíveis";
        }
        if (isLearned(best)) {
            p.confidence = Confidence.HIGH;
            p.reason = "ligado antes pela equipe (glossário)";
        }
        return p;
    }

    private static double rank(Src s, double sim) {
        return sim + (s.level() == 0 ? 0.1 : 0) - (s.hasIndex() ? 0.15 : 0) - s.path().size() * 0.01 - s.level() * 0.02;
    }

    private boolean matchesAll(Src s, List<JsonNode> targets, ValueTest test) {
        for (int i = 0; i < targets.size(); i++) {
            if (!test.test(s.values().get(i), targets.get(i))) return false;
        }
        return true;
    }

    private boolean sameValue(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) return a.decimalValue().compareTo(b.decimalValue()) == 0;
        return a.getNodeType() == b.getNodeType() && a.equals(b);
    }

    /** Concatenação: monta o texto da saída com valores da entrada e literais entre eles, igual em todos os itens. */
    private Plan concat(List<Src> all, List<JsonNode> targets) {
        String s0 = targets.get(0).asText();
        List<Src> textual = new ArrayList<>();
        for (Src s : all) {
            String v = s.values().get(0).asText();
            if (!v.isEmpty() && s.values().stream().allMatch(JsonNode::isValueNode)) textual.add(s);
        }
        List<Object> slots = new ArrayList<>(); // String literal ou List<Src> candidatos
        StringBuilder literal = new StringBuilder();
        int i = 0;
        boolean strong = false;
        while (i < s0.length()) {
            int bestLen = 0;
            List<Src> here = new ArrayList<>();
            for (Src s : textual) {
                String v = s.values().get(0).asText();
                if (!s0.startsWith(v, i)) continue;
                if (v.length() == 1 && !standsAlone(s0, i, 1)) continue;
                if (v.length() > bestLen) {
                    bestLen = v.length();
                    here.clear();
                }
                if (v.length() == bestLen) here.add(s);
            }
            if (bestLen == 0) {
                literal.append(s0.charAt(i));
                i++;
                continue;
            }
            if (literal.length() > 0) {
                slots.add(literal.toString());
                literal.setLength(0);
            }
            here.sort(Comparator.comparingDouble((Src s) -> -rank(s, 0)));
            slots.add(here);
            if (bestLen >= 3) strong = true;
            i += bestLen;
        }
        if (literal.length() > 0) slots.add(literal.toString());
        long slotCount = slots.stream().filter(o -> o instanceof List).count();
        int literalChars = slots.stream().filter(o -> o instanceof String).mapToInt(o -> ((String) o).length()).sum();
        // Sem pedaço longo, só aceita com sinal claro de montagem: literal de 2+ caracteres, ou pedaços curtos
        // separados por pontuação com pelo menos um número (ex.: "3-ST" = id + "-" + tipo). "S/N" continua fixo.
        if (!strong && literalChars < 2 && !shortNumericPieces(s0, slots)) return null;
        if (slotCount == 0 || (slotCount == 1 && slots.size() == 1)) return null;
        for (Object o : slots) {
            if (o instanceof String lit && !CONCAT_SAFE_LITERAL.matcher(lit).matches()) return null;
        }
        List<Object> chosen = new ArrayList<>();
        int[] tries = {0};
        if (!searchConcat(slots, 0, chosen, targets, tries)) return null;
        Plan p = new Plan();
        p.kind = Kind.CONCAT;
        p.concatParts = chosen;
        boolean ambiguous = slots.stream().anyMatch(o -> o instanceof List<?> l && l.size() > 1);
        p.confidence = ambiguous ? Confidence.MEDIUM : Confidence.HIGH;
        p.reason = ambiguous ? "texto montado com campos da entrada; algum pedaço tem mais de uma origem"
                : "texto montado com campos da entrada";
        return p;
    }

    @SuppressWarnings("unchecked")
    private static boolean shortNumericPieces(String s0, List<Object> slots) {
        int pos = 0;
        boolean numeric = false;
        for (Object o : slots) {
            if (o instanceof String lit) {
                pos += lit.length();
                continue;
            }
            Src first = ((List<Src>) o).get(0);
            String v = first.values().get(0).asText();
            if (!standsAlone(s0, pos, v.length())) return false;
            if (first.values().get(0).isNumber() || v.chars().allMatch(Character::isDigit)) numeric = true;
            pos += v.length();
        }
        return numeric;
    }

    @SuppressWarnings("unchecked")
    private boolean searchConcat(List<Object> slots, int idx, List<Object> chosen, List<JsonNode> targets, int[] tries) {
        if (++tries[0] > MAX_CONCAT_TRIES) return false;
        if (idx == slots.size()) {
            for (int pair = 0; pair < targets.size(); pair++) {
                StringBuilder sb = new StringBuilder();
                for (Object o : chosen) sb.append(o instanceof Src s ? s.values().get(pair).asText() : (String) o);
                if (!sb.toString().equals(targets.get(pair).asText())) return false;
            }
            return true;
        }
        Object slot = slots.get(idx);
        if (slot instanceof String) {
            chosen.add(slot);
            if (searchConcat(slots, idx + 1, chosen, targets, tries)) return true;
            chosen.remove(chosen.size() - 1);
            return false;
        }
        for (Src s : (List<Src>) slot) {
            chosen.add(s);
            if (searchConcat(slots, idx + 1, chosen, targets, tries)) return true;
            chosen.remove(chosen.size() - 1);
        }
        return false;
    }

    private static boolean standsAlone(String s, int at, int len) {
        boolean before = at == 0 || !Character.isLetterOrDigit(s.charAt(at - 1));
        boolean after = at + len >= s.length() || !Character.isLetterOrDigit(s.charAt(at + len));
        return before && after;
    }

    /** De-para: um campo da entrada de nome parecido, cujos valores levam sempre ao mesmo valor da saída. */
    private Plan valueMap(List<Src> all, List<JsonNode> targets, Set<String> nameTokens) {
        if (nameTokens.isEmpty()) return null; // "id" x "idRetaguarda": bom para cópia, não para inventar de-para
        Src best = null;
        double bestSim = 0;
        Map<String, JsonNode> bestMap = null;
        for (Src s : all) {
            double sim = sourceSimilarity(nameTokens, s);
            if (sim < 0.6 || sim <= bestSim) continue;
            Map<String, JsonNode> map = new LinkedHashMap<>();
            boolean ok = true;
            for (int i = 0; i < targets.size(); i++) {
                String key = s.values().get(i).asText();
                JsonNode t = targets.get(i);
                if (!t.isValueNode() || t.isNull() || !SHIFT_SAFE_KEY.matcher(key).matches() || !isCode(t)
                        || (map.containsKey(key) && !map.get(key).equals(t))) {
                    ok = false;
                    break;
                }
                map.put(key, t);
            }
            if (ok) {
                best = s;
                bestSim = sim;
                bestMap = map;
            }
        }
        if (best == null) return null;
        Plan p = new Plan();
        p.kind = Kind.VALUE_MAP;
        p.source = best;
        p.valueMap = bestMap;
        p.confidence = Confidence.LOW;
        p.reason = "de-para pelo nome do campo; o exemplo só mostra " + bestMap.size()
                + (bestMap.size() == 1 ? " valor" : " valores") + ": confirme os demais";
        return p;
    }

    /** Valor que cabe num de-para: número, booleano ou código curto (nada vazio, longo ou com espaço). */
    private static boolean isCode(JsonNode t) {
        if (t.isNumber() || t.isBoolean()) return true;
        String v = t.asText();
        return !v.isEmpty() && v.length() <= 20 && v.indexOf(' ') < 0;
    }

    private Plan constantOrUnresolved(List<JsonNode> targets) {
        Plan p = new Plan();
        if (allEqual(targets) && !targets.get(0).isMissingNode()) {
            p.kind = Kind.CONSTANT;
            p.constant = targets.get(0);
        } else {
            p.kind = Kind.UNRESOLVED;
            p.confidence = Confidence.NONE;
            p.reason = "o valor muda de um item para outro e nenhum campo da entrada explica";
        }
        return p;
    }

    // ------------------------------------------------------------------ emissão

    private void emitAbsolute(Scope scope, List<String> field, Plan plan, String display, boolean asArray) {
        emitInto(scope, plan, field, display, true, asArray);
    }

    private void emit(Scope scope, List<String> field, Plan plan, String display, boolean asArray) {
        emitInto(scope, plan, field, display, scope.inPattern().isEmpty(), asArray);
    }

    private void emitInto(Scope scope, Plan plan, List<String> field, String display, boolean absolute, boolean asArray) {
        String fieldPath = String.join(".", field);
        String example = null;
        switch (plan.kind) {
            case VARIABLE, CONSTANT -> {
                putDefault(scope, field, plan.constant, absolute);
                example = plan.constant.toString();
            }
            case COPY -> {
                if (plan.source.level() == 0 && !plan.source.hasIndex()) {
                    List<String> keys = plan.source.path().stream().map(String::valueOf).toList();
                    putShift(scope, keys, dst(scope, field, keys.size(), absolute, asArray));
                } else {
                    String t = newTemp();
                    modifyAt(scope).put(t, ref(scope, plan.source));
                    putShift(scope, List.of(t), dst(scope, field, 1, absolute, asArray));
                }
                example = plan.source.values().get(0).toString();
            }
            case AS_NUMBER -> {
                String t = newTemp();
                String target = at(scope.pairs().get(0).out(), field).isIntegralNumber() ? "=toInteger(" : "=toDouble(";
                modifyAt(scope).put(t, target + ref(scope, plan.source) + ")");
                putShift(scope, List.of(t), dst(scope, field, 1, absolute, asArray));
                example = plan.source.values().get(0) + " → número";
            }
            case AS_TEXT -> {
                String t = newTemp();
                modifyAt(scope).put(t, "=toString(" + ref(scope, plan.source) + ")");
                putShift(scope, List.of(t), dst(scope, field, 1, absolute, asArray));
                example = plan.source.values().get(0) + " → \"" + plan.source.values().get(0).asText() + "\"";
            }
            case DIGITS -> {
                String t = newTemp();
                ObjectNode m = modifyAt(scope);
                m.put(t + "_partes", "=split('\\D'," + ref(scope, plan.source) + ")");
                m.put(t, "=join('',@(1," + t + "_partes))");
                putShift(scope, List.of(t), dst(scope, field, 1, absolute, asArray));
                example = plan.source.values().get(0) + " → " + plan.source.values().get(0).asText().replaceAll("\\D", "");
            }
            case CONCAT -> {
                String t = newTemp();
                List<String> args = new ArrayList<>();
                StringBuilder ex = new StringBuilder();
                for (Object o : plan.concatParts) {
                    if (o instanceof Src s) {
                        args.add(ref(scope, s));
                        ex.append(s.values().get(0).asText());
                    } else {
                        args.add("'" + o + "'");
                        ex.append(o);
                    }
                }
                modifyAt(scope).put(t, "=concat(" + String.join(",", args) + ")");
                putShift(scope, List.of(t), dst(scope, field, 1, absolute, asArray));
                example = ex.toString();
            }
            case VALUE_MAP -> {
                // Sempre sobre uma cópia: o mesmo campo pode ser copiado para outro destino, e a chave dele no
                // shift não pode ser ao mesmo tempo um destino (texto) e um de-para (objeto).
                String t = newTemp();
                modifyAt(scope).put(t, ref(scope, plan.source));
                List<String> keys = List.of(t);
                String target = null;
                for (Map.Entry<String, JsonNode> e : plan.valueMap.entrySet()) {
                    List<String> path = new ArrayList<>(keys);
                    path.add(e.getKey());
                    path.add("#" + e.getValue().asText());
                    target = dst(scope, field, path.size(), absolute, asArray);
                    putShift(scope, path, target);
                    JsonNode v = e.getValue();
                    if (v.isIntegralNumber()) putPost(scope, field, "=toInteger", absolute);
                    else if (v.isNumber()) putPost(scope, field, "=toDouble", absolute);
                    else if (v.isBoolean()) putPost(scope, field, "=toBoolean", absolute);
                }
                if (plan.otherwise != null && !plan.otherwise.isNull()) {
                    List<String> path = new ArrayList<>(keys);
                    path.add("*");
                    path.add("#" + plan.otherwise.asText());
                    putShift(scope, path, dst(scope, field, path.size(), absolute, asArray));
                    JsonNode v = plan.otherwise;
                    if (v.isIntegralNumber()) putPost(scope, field, "=toInteger", absolute);
                    else if (v.isNumber()) putPost(scope, field, "=toDouble", absolute);
                    else if (v.isBoolean()) putPost(scope, field, "=toBoolean", absolute);
                }
                StringBuilder ex = new StringBuilder();
                plan.valueMap.forEach((k, v) -> ex.append(ex.length() == 0 ? "" : ", ").append(k).append(" → ").append(v));
                if (plan.otherwise != null && !plan.otherwise.isNull()) ex.append(", outro → ").append(plan.otherwise);
                example = ex.toString();
            }
            case UNRESOLVED, LIST -> {
            }
        }
        String source = plan.source != null ? display(plan.source)
                : plan.concatParts != null ? concatDisplay(plan.concatParts) : null;
        boolean pending = plan.kind == Kind.UNRESOLVED || plan.confidence != Confidence.HIGH;
        rules.add(new Rule(display.isEmpty() ? fieldPath : display, absolute ? "" : scope.display(), plan.kind, source, null,
                plan.confidence, pending,
                plan.reason, plan.alternatives, example));
    }

    private String concatDisplay(List<Object> parts) {
        List<String> out = new ArrayList<>();
        for (Object o : parts) out.add(o instanceof Src s ? display(s) : "'" + o + "'");
        return String.join(" + ", out);
    }

    private String display(Src s) {
        return (s.level() == 0 ? "" : "↑".repeat(s.level())) + s.pathString();
    }

    private String newTemp() {
        return "jgen_" + (++temp);
    }

    /** {@code @(k,caminho)} visto de uma chave dentro do item do escopo. */
    private String ref(Scope scope, Src s) {
        int k = s.level() == 0 ? 1 : 1 + (scope.inPattern().size() - scope.ancestorLens().get(s.level() - 1));
        return "@(" + k + "," + s.pathString() + ")";
    }

    private ObjectNode modifyAt(Scope scope) {
        return walk(modify, scope.inPattern());
    }

    private ObjectNode walk(ObjectNode root, List<String> keys) {
        ObjectNode node = root;
        for (String k : keys) {
            JsonNode child = node.get(k);
            if (child == null || !child.isObject()) {
                child = node.putObject(k);
            }
            node = (ObjectNode) child;
        }
        return node;
    }

    /** Destino do shift: listas da saída com o índice do "*" da entrada ({@code [&n]}), contado a partir da folha. */
    private String dst(Scope scope, List<String> field, int keysBelowItem, boolean absolute, boolean asArray) {
        String f = String.join(".", field);
        if (absolute) return asArray ? f + "[]" : f;
        int depth = scope.inPattern().size() + keysBelowItem;
        StringBuilder sb = new StringBuilder();
        for (OutLevel level : scope.outLevels()) {
            if (sb.length() > 0) sb.append('.');
            sb.append(level.key()).append("[&").append(depth - 1 - level.wildcardPos()).append(']');
        }
        return sb + "." + f;
    }

    private void putShift(Scope scope, List<String> keys, String target) {
        ObjectNode node = walk(shift, scope.inPattern());
        for (int i = 0; i < keys.size() - 1; i++) {
            JsonNode child = node.get(keys.get(i));
            if (child == null || !child.isObject()) child = node.putObject(keys.get(i));
            node = (ObjectNode) child;
        }
        String leaf = keys.get(keys.size() - 1);
        JsonNode existing = node.get(leaf);
        if (existing == null) {
            node.put(leaf, target);
        } else if (existing.isTextual()) {
            ArrayNode arr = node.putArray(leaf);
            arr.add(existing.asText());
            arr.add(target);
        } else if (existing.isArray()) {
            ((ArrayNode) existing).add(target);
        }
    }

    private void putPost(Scope scope, List<String> field, String fn, boolean absolute) {
        ObjectNode node = post;
        if (!absolute) {
            for (OutLevel level : scope.outLevels()) {
                for (String k : level.key().split("\\.")) node = walk(node, List.of(k));
                node = walk(node, List.of("*"));
            }
        }
        node = walk(node, field.subList(0, field.size() - 1));
        node.put(field.get(field.size() - 1), fn);
    }

    private void putDefault(Scope scope, List<String> field, JsonNode value, boolean absolute) {
        ObjectNode node = defaults;
        if (!absolute) {
            List<OutLevel> levels = scope.outLevels();
            for (OutLevel level : levels) {
                String[] parts = level.key().split("\\.");
                for (int i = 0; i < parts.length - 1; i++) node = walk(node, List.of(parts[i]));
                node = walk(node, List.of(parts[parts.length - 1] + "[]", "*"));
            }
        }
        node = walk(node, field.subList(0, field.size() - 1));
        node.set(field.get(field.size() - 1), value.deepCopy());
    }

    private ArrayNode buildSpec() {
        ArrayNode spec = nodes.arrayNode();
        if (modify.size() > 0) spec.add(op("modify-overwrite-beta", modify));
        if (shift.size() > 0) spec.add(op("shift", shift));
        if (post.size() > 0) spec.add(op("modify-overwrite-beta", post));
        if (defaults.size() > 0) spec.add(op("default", defaults));
        return spec;
    }

    private ObjectNode op(String name, ObjectNode body) {
        ObjectNode o = nodes.objectNode();
        o.put("operation", name);
        o.set("spec", body);
        return o;
    }

    // ------------------------------------------------------------------ nomes

    static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT);
    }

    /** Palavras do nome (camelCase, _ e .), sem acento, traduzidas pelo glossário, sem as genéricas (id, codigo, valor...). */
    static Set<String> tokens(String name) {
        Set<String> out = new LinkedHashSet<>();
        String spaced = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
        for (String raw : spaced.split("[^A-Za-z0-9À-ÿ]+")) {
            if (raw.isBlank()) continue;
            String t = normalize(raw).replaceAll("[0-9]+", "");
            if (t.isEmpty()) continue;
            t = GLOSSARY.getOrDefault(t, t);
            if (t.length() > 3 && t.endsWith("s") && !t.endsWith("ss")) t = t.substring(0, t.length() - 1);
            if (!GENERIC.contains(t)) out.add(t);
        }
        return out;
    }

    private static String singular(String s) {
        return s.length() > 3 && s.endsWith("s") && !s.endsWith("ss") ? s.substring(0, s.length() - 1) : s;
    }

    private static boolean fuzzyEquals(String a, String b) {
        if (a.equals(b)) return true;
        int common = 0;
        while (common < Math.min(a.length(), b.length()) && a.charAt(common) == b.charAt(common)) common++;
        return common >= 4 && common >= 0.7 * Math.min(a.length(), b.length());
    }

    static double similarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0;
        int matches = 0;
        for (String x : a) {
            for (String y : b) {
                if (fuzzyEquals(x, y)) {
                    matches++;
                    break;
                }
            }
        }
        return (double) matches / Math.max(a.size(), b.size());
    }

    /** Nome do campo como o glossário guarda: sem acento, minúsculo, só letras e dígitos. */
    public static String termKey(String raw) {
        return raw == null ? "" : normalize(raw).replaceAll("[^a-z0-9]", "");
    }

    /** Último nome de campo de uma origem como a tela mostra ("↑produto.embalagens[0].codigoDeBarra" → "codigoDeBarra"). */
    public static String lastFieldOf(String display) {
        if (display == null) return "";
        String clean = display.replace("↑", "").replaceAll("\\[\\d+]", "");
        int dot = clean.lastIndexOf('.');
        return dot >= 0 ? clean.substring(dot + 1) : clean;
    }

    private boolean isLearned(Src s) {
        if (currentName == null || learned.isEmpty()) return false;
        Set<String> to = learned.get(termKey(currentName));
        if (to == null) return false;
        return to.contains(termKey(s.lastKey()));
    }

    private double sourceSimilarity(Set<String> target, Src s) {
        // Ligação que a equipe já confirmou numa spec válida vale mais que qualquer semelhança de nome.
        if (isLearned(s)) return 1.2;
        double last = similarity(target, tokens(s.lastKey()));
        Set<String> all = new LinkedHashSet<>();
        for (Object p : s.path()) if (p instanceof String str) all.addAll(tokens(str));
        // Nome todo genérico (ex.: "id") só casa com alvo também genérico (ex.: "idRetaguarda").
        if (all.isEmpty() && !target.isEmpty()) return 0;
        return Math.max(last, 0.9 * similarity(target, all));
    }

    // ------------------------------------------------------------------ JSON

    private static boolean isWeak(JsonNode v) {
        if (v.isBoolean() || v.isNull()) return true;
        if (v.isNumber()) {
            double d = v.asDouble();
            return d == 0 || d == 1 || d == -1;
        }
        if (v.isTextual()) return v.asText().length() <= 2;
        return false;
    }

    private static boolean allEqual(List<JsonNode> values) {
        for (JsonNode v : values) if (!v.equals(values.get(0))) return false;
        return true;
    }

    private static boolean allObjects(JsonNode array) {
        for (JsonNode n : array) if (!n.isObject()) return false;
        return true;
    }

    private static boolean allScalar(JsonNode array) {
        for (JsonNode n : array) if (n.isContainerNode()) return false;
        return true;
    }

    private static JsonNode at(JsonNode node, List<String> path) {
        JsonNode cur = node;
        for (String k : path) {
            if (cur == null || !cur.isObject()) return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
            cur = cur.get(k);
        }
        return cur == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : cur;
    }

    private static JsonNode atAny(JsonNode node, List<Object> path) {
        JsonNode cur = node;
        for (Object k : path) {
            if (cur == null) break;
            cur = k instanceof Integer i ? (cur.isArray() ? cur.get(i) : null) : (cur.isObject() ? cur.get((String) k) : null);
        }
        return cur == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : cur;
    }

    /** Lista as regras na ordem em que a tela mostra: pendentes primeiro. */
    public static List<Rule> pendingFirst(List<Rule> rules) {
        List<Rule> copy = new ArrayList<>(rules);
        copy.sort(Comparator.comparing((Rule r) -> !r.pending()));
        return Collections.unmodifiableList(copy);
    }
}
