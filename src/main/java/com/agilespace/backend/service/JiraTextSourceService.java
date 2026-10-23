package com.agilespace.backend.service;

import com.agilespace.backend.dto.JiraTextSourcesRequest;
import com.agilespace.backend.dto.JiraTextSourcesResponse;
import com.agilespace.backend.dto.JiraTextSourcesResponse.Candidate;
import com.agilespace.backend.dto.JiraTextSourcesResponse.Field;
import com.agilespace.backend.dto.JiraTextSourcesResponse.IssueSources;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Diz de onde pode vir cada texto de Problema, Solução e Critérios de aceite de uma issue do Jira: o campo do Jira, a
 * descrição, cada comentário (com autor e data), as subtarefas e a issue pai.
 *
 * <p>Uma fonte é <b>explícita</b> quando o texto veio de um título conhecido ("Solução:", "Contexto") ou de um campo do Jira;
 * é <b>palpite</b> quando só foi deduzida (começo da descrição, linhas em Gherkin). Só uma fonte explícita, sem concorrente,
 * entra sozinha ({@code certa}); palpite ou mais de uma fonte explícita diferente ficam {@code revisar}, e a pessoa escolhe.
 * Sem nada, {@code vazio}. Subtarefa e issue pai só entram quando a própria issue não tem texto explícito para o campo.
 *
 * <p>É a mesma regra que o frontend aplicava no navegador (jiraService.ts); aqui ela tem um lugar só, com testes.
 */
@Service
public class JiraTextSourceService {

    public static final String CERTA = "certa";
    public static final String REVISAR = "revisar";
    public static final String VAZIO = "vazio";

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;
    private static final int MAX_LOOPS = 200;

    // ---- Cabeçalhos conhecidos (iguais aos de jiraService.ts) ----

    private static final List<String> PROBLEM = List.of(
            "PROBLEMA", "CONTEXTO", "MOTIVA[ÇC][ÃA]O", "POR\\s+QU[ÊE]", "ERRO\\s+ENCONTRADO");

    private static final List<String> SOLUTION = List.of(
            "SOLU[ÇC][ÃA]O", "RESOLU[ÇC][ÃA]O", "AN[ÁA]LISE",
            "O\\s+QUE\\s+FOI\\s+FEITO", "COMO\\s+FOI\\s+FEITO", "IMPLEMENTA[ÇC][ÃA]O",
            "ALTERA[ÇC][ÕO]ES?\\s+REALIZADAS?", "PROPOSTA\\s+(?:DE\\s+)?SOLU[ÇC][ÃA]O",
            "DETALHAMENTO\\s+FUNCIONAL", "OBJETIVO\\s+DA\\s+MUDAN[ÇC]A", "IMPACTOS?\\s+T[ÉE]CNICOS?");

    private static final List<String> CRITERIA = List.of(
            "CRIT[ÉE]RIOS?\\s+DE\\s+ACEITE", "CRIT[ÉE]RIOS?\\s+DE\\s+ACEITA[ÇC][ÃA]O",
            "ACCEPTANCE\\s+CRITERIA", "O\\s+QUE\\s+SER[ÁA]\\s+TESTADO",
            "CEN[ÁA]RIOS?\\s+DE\\s+TESTE", "COMPORTAMENTO\\s+ESPERADO",
            "ROTEIRO\\s+DE\\s+TESTES?", "CHECKLIST");

    /** Só servem de fim de seção: sem eles o conteúdo é engolido pela seção anterior. */
    private static final List<String> OTHER = List.of(
            "PONTOS?\\s+DE\\s+ATEN[ÇC][ÃA]O", "DOCUMENTA[ÇC][ÃA]O(?:\\s+E\\s+EVID[ÊE]NCIAS)?",
            "DADOS\\s+T[ÉE]CNICOS(?:\\s+E\\s+LINKS)?", "V[ÍI]DEO", "EVID[ÊE]NCIAS?",
            "ROTINA", "VERS[ÃA]O\\s+PARA\\s+TESTE", "TABELAS\\s+UTILIZADAS",
            "NECESSITA\\s+DE\\s+PERMISS[ÃA]O(?:\\s+OU\\s+PARAMETRIZA[ÇC][ÃA]O)?(?:\\?\\s*QUAIS\\?)?",
            "CEN[ÁA]RIOS?\\s+A\\s+SEREM\\s+TESTADOS");

    private static final List<String> ALL = concat(PROBLEM, SOLUTION, CRITERIA, OTHER);

    // Pula "h2." do wiki e até 8 caracteres que não são letra (emoji, *, #, número) antes do cabeçalho.
    private static final String HEADER_LEAD = "^(?:h[1-6]\\.[ \\t]*)?[^\\p{L}\\n]{0,8}";
    // "Contexto/Problema" colados viram um cabeçalho só.
    private static final String HEADER_GLUE = "(?:\\s*[\\/,]\\s*(?:" + String.join("|", ALL) + "))*";
    private static final String HEADER_TAIL =
            "(?:[ \\t]+\\p{L}{2,20}(?=[ \\t]*(?:\\n|$)))?(?:\\s*\\([^)]{0,40}\\))?[ \\t]*:?[ \\t]*\\n?";

    private static final Pattern HEADER_ALL = headerPattern(ALL);
    private static final Pattern HEADER_PROBLEM = headerPattern(PROBLEM);
    private static final Pattern HEADER_SOLUTION = headerPattern(SOLUTION);
    private static final Pattern HEADER_CRITERIA = headerPattern(CRITERIA);

    private static final Pattern GHERKIN_LINE = Pattern.compile("^\\s*(?:dado|given|quando|when|ent[ãa]o|then)\\b", FLAGS);
    private static final Pattern BULLET_LINE = Pattern.compile("^\\s*(?:[-*•#]|\\d+[.)]|\\[[ xX]\\])\\s+", FLAGS);

    private static final Pattern BR = Pattern.compile("<br\\s*/?>", FLAGS);
    private static final Pattern BLOCK_END = Pattern.compile("</(p|div|li|h[1-6])>", FLAGS);
    private static final Pattern ANY_TAG = Pattern.compile("<[^>]*>?", FLAGS);
    private static final Pattern NUM_ENTITY = Pattern.compile("&#(\\d+);");
    private static final Pattern WIKI_HEADING = Pattern.compile("^h[1-6]\\.[ \\t]*", FLAGS);
    private static final Pattern WIKI_COLOR = Pattern.compile("\\{color[^}]*\\}([\\s\\S]*?)\\{color\\}", FLAGS);
    private static final Pattern WIKI_BLOCK = Pattern.compile("\\{(?:quote|noformat|code[^}]*)\\}([\\s\\S]*?)\\{/?(?:quote|noformat|code)\\}", FLAGS);
    private static final Pattern WIKI_BOLD = Pattern.compile("\\*(\\S(?:[^*\\n]*\\S)?)\\*");
    private static final Pattern WIKI_RULE = Pattern.compile("^-{3,}[ \\t]*$", FLAGS);
    private static final Pattern SPACES = Pattern.compile("[ \\t]+");
    private static final Pattern BLANK_RUNS = Pattern.compile("\\n{3,}");
    private static final Pattern ANY_SPACE_RUN = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    // ---- API ----

    public JiraTextSourcesResponse analyze(JiraTextSourcesRequest request) {
        List<IssueSources> out = new ArrayList<>();
        for (JiraTextSourcesRequest.Issue issue : request.issues()) {
            out.add(analyzeIssue(issue));
        }
        return new JiraTextSourcesResponse(out);
    }

    IssueSources analyzeIssue(JiraTextSourcesRequest.Issue issue) {
        Own own = own(issue);
        Field problem = own.problem;
        Field solution = own.solution;
        Field criteria = own.criteria;

        List<JiraTextSourcesRequest.Issue> subtasks = relevantSubtasks(issue.subtasks());
        if (!subtasks.isEmpty()) {
            problem = withFallback(problem, related("subtarefa", subtasks, Which.PROBLEM));
            solution = withFallback(solution, related("subtarefa", subtasks, Which.SOLUTION));
            criteria = withFallback(criteria, related("subtarefa", subtasks, Which.CRITERIA));
        }
        if (issue.parent() != null) {
            List<JiraTextSourcesRequest.Issue> parent = List.of(issue.parent());
            problem = withFallback(problem, related("pai", parent, Which.PROBLEM));
            solution = withFallback(solution, related("pai", parent, Which.SOLUTION));
            criteria = withFallback(criteria, related("pai", parent, Which.CRITERIA));
        }
        return new IssueSources(issue.key(), problem, solution, criteria);
    }

    // ---- Fontes da própria issue ----

    private record Own(Field problem, Field solution, Field criteria) {
    }

    private Own own(JiraTextSourcesRequest.Issue issue) {
        String description = nz(issue.description());
        List<JiraTextSourcesRequest.Comment> comments = issue.comments() == null ? List.of() : issue.comments();

        List<Candidate> problem = new ArrayList<>(fromComments(comments, HEADER_PROBLEM));
        problem.add(new Candidate("descricao", "descricao", "Descrição, seção “Problema”", section(description, HEADER_PROBLEM), true));
        problem.add(new Candidate("descricao-inicio", "descricao", "Descrição, início do texto", preamble(description), false));

        List<Candidate> solution = new ArrayList<>(fromComments(comments, HEADER_SOLUTION));
        solution.add(new Candidate("descricao", "descricao", "Descrição, seção “Solução”", section(description, HEADER_SOLUTION), true));

        List<Candidate> criteria = new ArrayList<>();
        criteria.add(new Candidate("campo", "campo", "Campo “Critérios de aceite” do Jira", stripHtml(nz(issue.criteriaField())), true));
        criteria.add(new Candidate("descricao", "descricao", "Descrição, seção de critérios", section(description, HEADER_CRITERIA), true));
        criteria.add(new Candidate("descricao-linhas", "descricao", "Descrição, linhas de teste", guessCriteria(description), false));

        return new Own(classify(problem), classify(solution), classify(criteria));
    }

    private List<Candidate> fromComments(List<JiraTextSourcesRequest.Comment> comments, Pattern header) {
        List<Candidate> list = new ArrayList<>();
        for (int i = 0; i < comments.size(); i++) {
            JiraTextSourcesRequest.Comment c = comments.get(i);
            if (c == null || c.body() == null) continue;
            list.add(new Candidate("comentario-" + i, "comentario", commentLabel(c.author(), c.created()), section(c.body(), header), true));
        }
        return list;
    }

    // ---- Classificação ----

    static Field classify(List<Candidate> candidates) {
        List<Candidate> list = dedupe(candidates);
        List<Candidate> explicit = list.stream().filter(Candidate::explicit).toList();
        if (explicit.size() == 1) return new Field(CERTA, explicit.get(0).id(), list);
        if (explicit.size() > 1 || !list.isEmpty()) return new Field(REVISAR, null, list);
        return new Field(VAZIO, null, List.of());
    }

    /** Texto repetido em duas fontes conta uma vez; a explícita ganha do palpite. */
    private static List<Candidate> dedupe(List<Candidate> candidates) {
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : candidates) {
            if (c.text() == null || c.text().isBlank()) continue;
            int same = -1;
            for (int i = 0; i < out.size(); i++) {
                if (norm(out.get(i).text()).equals(norm(c.text()))) { same = i; break; }
            }
            if (same < 0) out.add(c);
            else if (c.explicit() && !out.get(same).explicit()) out.set(same, c);
        }
        return out;
    }

    static String value(Field f) {
        if (f == null || f.sourceId() == null) return "";
        return f.candidates().stream().filter(c -> c.id().equals(f.sourceId())).map(Candidate::text).findFirst().orElse("");
    }

    /** Soma fontes de outras issues só quando esta não tem texto explícito para o campo. */
    private static Field withFallback(Field own, List<Candidate> extra) {
        if (own.candidates().stream().anyMatch(Candidate::explicit)) return own;
        List<Candidate> extras = extra.stream().filter(c -> c.text() != null && !c.text().isBlank()).toList();
        if (extras.isEmpty()) return own;
        List<Candidate> all = new ArrayList<>(own.candidates());
        all.addAll(extras);
        return classify(all);
    }

    private enum Which { PROBLEM, SOLUTION, CRITERIA }

    private List<Candidate> related(String kind, List<JiraTextSourcesRequest.Issue> issues, Which which) {
        List<Candidate> list = new ArrayList<>();
        for (JiraTextSourcesRequest.Issue r : issues) {
            Own o = own(r);
            Field f = switch (which) {
                case PROBLEM -> o.problem;
                case SOLUTION -> o.solution;
                case CRITERIA -> o.criteria;
            };
            String key = nz(r.key());
            String label = "pai".equals(kind) ? "Issue pai · " + key : "Subtarefa · " + key;
            list.add(new Candidate(kind + "-" + key, kind, label, value(f), true));
        }
        return list;
    }

    /** As subtarefas de codificação/desenvolvimento valem sozinhas quando existem, para a de teste não gerar conflito à toa. */
    private List<JiraTextSourcesRequest.Issue> relevantSubtasks(List<JiraTextSourcesRequest.Issue> subtasks) {
        if (subtasks == null || subtasks.isEmpty()) return List.of();
        List<JiraTextSourcesRequest.Issue> sorted = new ArrayList<>(subtasks);
        sorted.sort(Comparator.comparingInt((JiraTextSourcesRequest.Issue s) -> score(s)).reversed());
        List<JiraTextSourcesRequest.Issue> dev = sorted.stream().filter(JiraTextSourceService::isDevSubtask).toList();
        return dev.isEmpty() ? sorted : dev;
    }

    private int score(JiraTextSourcesRequest.Issue s) {
        Own o = own(s);
        return (isDevSubtask(s) ? 10 : 0) + (!value(o.solution).isEmpty() ? 5 : 0) + (!value(o.problem).isEmpty() ? 3 : 0);
    }

    private static boolean isDevSubtask(JiraTextSourcesRequest.Issue s) {
        String text = (nz(s.type()) + " " + nz(s.title())).toLowerCase(Locale.ROOT);
        return text.contains("codifica") || text.contains("desenvolv") || text.contains("dev") || text.contains("coding");
    }

    // ---- Extração de seções (porta de jiraService.ts) ----

    private static Pattern headerPattern(List<String> aliases) {
        return Pattern.compile(HEADER_LEAD + "(?:" + String.join("|", aliases) + ")" + HEADER_GLUE + HEADER_TAIL, FLAGS);
    }

    /** Junta TODAS as ocorrências da mesma categoria (duas seções de critérios, por exemplo). */
    static String section(String text, Pattern header) {
        if (text == null || text.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        int from = 0;
        for (int loops = 0; from < text.length() && loops < MAX_LOOPS; loops++) {
            String rest = text.substring(from);
            Matcher start = header.matcher(rest);
            if (!start.find()) break;
            int afterStart = from + start.end();
            Matcher end = HEADER_ALL.matcher(text.substring(afterStart));
            int absEnd = end.find() ? afterStart + end.start() : text.length();
            parts.add(text.substring(afterStart, absEnd));
            from = absEnd;
        }
        return stripHtml(String.join("\n\n", parts));
    }

    /** Sem cabeçalho de problema: o que vem antes do primeiro cabeçalho conhecido (ou o texto inteiro). */
    static String preamble(String text) {
        if (text == null || text.isEmpty()) return "";
        Matcher first = HEADER_ALL.matcher(text);
        String pre = first.find() ? text.substring(0, first.start()) : text;
        return stripHtml(pre);
    }

    /** Sem cabeçalho de critério: bloco de linhas em Gherkin ou lista com marcador, fora das seções já reconhecidas. */
    static String guessCriteria(String text) {
        if (text == null || text.isEmpty()) return "";
        List<String> claimed = concat(OTHER, PROBLEM, SOLUTION);
        String without = stripKnownSections(text, headerPattern(claimed));
        List<String> hits = new ArrayList<>();
        for (String line : stripHtml(without).split("\n", -1)) {
            if (GHERKIN_LINE.matcher(line).find() || BULLET_LINE.matcher(line).find()) hits.add(line);
        }
        return String.join("\n", hits);
    }

    private static String stripKnownSections(String text, Pattern header) {
        String result = text;
        List<int[]> spans = new ArrayList<>();
        int from = 0;
        for (int loops = 0; from < result.length() && loops < MAX_LOOPS; loops++) {
            Matcher m = header.matcher(result.substring(from));
            if (!m.find()) break;
            int absStart = from + m.start();
            int afterHeader = from + m.end();
            Matcher end = HEADER_ALL.matcher(result.substring(afterHeader));
            int absEnd = end.find() ? afterHeader + end.start() : result.length();
            spans.add(new int[]{absStart, absEnd});
            from = absEnd;
        }
        for (int i = spans.size() - 1; i >= 0; i--) {
            result = result.substring(0, spans.get(i)[0]) + result.substring(spans.get(i)[1]);
        }
        return result;
    }

    // ---- Texto ----

    /** Tira tags HTML e marcação wiki do Jira, decodifica entidades e preserva quebra de linha. */
    static String stripHtml(String html) {
        if (html == null || html.isEmpty()) return "";
        String text = BR.matcher(html).replaceAll("\n");
        text = BLOCK_END.matcher(text).replaceAll("\n");
        text = ANY_TAG.matcher(text).replaceAll("");
        text = text.replace("&nbsp;", " ").replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
        text = NUM_ENTITY.matcher(text).replaceAll(m -> {
            try {
                return Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1))));
            } catch (NumberFormatException e) {
                return "";
            }
        });
        text = stripWiki(text);
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            lines[i] = SPACES.matcher(lines[i]).replaceAll(" ").strip();
        }
        return BLANK_RUNS.matcher(String.join("\n", lines)).replaceAll("\n\n").strip();
    }

    private static String stripWiki(String text) {
        String t = WIKI_HEADING.matcher(text).replaceAll("");
        t = WIKI_COLOR.matcher(t).replaceAll("$1");
        t = WIKI_BLOCK.matcher(t).replaceAll("$1");
        t = WIKI_BOLD.matcher(t).replaceAll("$1");
        return WIKI_RULE.matcher(t).replaceAll("");
    }

    static String commentLabel(String author, String created) {
        String date = shortDate(created);
        return "Comentário" + (author != null && !author.isBlank() ? " de " + author : "") + (date.isEmpty() ? "" : ", " + date);
    }

    /** "2026-10-14T10:00:00.000-0300" -> "14/10" (a data como o Jira escreveu, sem converter fuso). */
    static String shortDate(String iso) {
        if (iso == null || iso.length() < 10) return "";
        Matcher m = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})").matcher(iso);
        return m.find() ? m.group(3) + "/" + m.group(2) : "";
    }

    private static String norm(String s) {
        return ANY_SPACE_RUN.matcher(s).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    @SafeVarargs
    private static List<String> concat(List<String>... lists) {
        List<String> out = new ArrayList<>();
        for (List<String> l : lists) out.addAll(l);
        return List.copyOf(out);
    }
}
