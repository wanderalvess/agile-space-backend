package com.agilespace.backend.service.sprintplan;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Encadeamento da Distribuição (E4), puro e testável: Codificação, depois Code Review DEV, depois QA, dentro da mesma
 * tarefa. Cada nível só começa no dia do fim do nível anterior ou depois. Sem Code Review DEV datado, o QA segue o fim
 * da Codificação. Quando o fim de um nível muda, os níveis seguintes acompanham pelo mesmo delta, em dias úteis (seg-sex
 * fora feriados), nos dois sentidos, preservando a duração.
 */
public final class SprintPlanChain {

    private SprintPlanChain() {}

    public enum Level { CODING, CODE_REVIEW, QA }

    public static final class Node {
        public final String key;
        public final Level level;
        public LocalDate start;
        public LocalDate end;

        public Node(String key, Level level, LocalDate start, LocalDate end) {
            this.key = key;
            this.level = level;
            this.start = start;
            this.end = end;
        }

        boolean dated() {
            return start != null && end != null;
        }
    }

    public static Level levelOf(String cls, boolean codeReview) {
        if ("QA".equals(cls)) return Level.QA;
        return codeReview ? Level.CODE_REVIEW : Level.CODING;
    }

    /** Antecessoras que valem para o nível: CR datado, senão Codificação datada (para o QA); Codificação (para o CR). */
    static List<Node> effectivePredecessors(Level level, List<Node> nodes) {
        if (level == Level.CODING) return List.of();
        List<Node> coding = byLevel(nodes, Level.CODING);
        if (level == Level.CODE_REVIEW) return datedOrAll(coding);
        List<Node> cr = byLevel(nodes, Level.CODE_REVIEW);
        if (cr.stream().anyMatch(Node::dated)) return datedOrAll(cr);
        return datedOrAll(coding);
    }

    private static List<Node> datedOrAll(List<Node> list) {
        List<Node> dated = list.stream().filter(Node::dated).toList();
        return dated.isEmpty() ? list : dated;
    }

    /** Menor início permitido para o nó: o maior fim entre as antecessoras datadas; null se não há. */
    public static LocalDate minStart(Node n, List<Node> nodes) {
        LocalDate max = null;
        for (Node p : effectivePredecessors(n.level, nodes)) {
            if (p == n || p.end == null) continue;
            if (max == null || p.end.isAfter(max)) max = p.end;
        }
        return max;
    }

    /** Chave da antecessora que define o mínimo (a de maior fim; sem datas, a primeira); null para Codificação. */
    public static String startsAfterKey(Node n, List<Node> nodes) {
        Node best = null;
        for (Node p : effectivePredecessors(n.level, nodes)) {
            if (p == n) continue;
            if (best == null || (p.end != null && (best.end == null || p.end.isAfter(best.end)))) best = p;
        }
        return best == null ? null : best.key;
    }

    /** Maior fim entre os nós datados do nível; null se nenhum. */
    static LocalDate maxEnd(List<Node> nodes, Level level) {
        LocalDate max = null;
        for (Node n : nodes) {
            if (n.level != level || !n.dated()) continue;
            if (max == null || n.end.isAfter(max)) max = n.end;
        }
        return max;
    }

    /**
     * Aplica as novas datas ao nó editado e propaga o delta (em dias úteis) do fim aos níveis seguintes.
     * Devolve os nós que mudaram (o editado primeiro).
     */
    public static List<Node> applyWithCascade(List<Node> nodes, Node edited, LocalDate newStart, LocalDate newEnd,
                                              Set<LocalDate> holidays) {
        LocalDate oldCoding = maxEnd(nodes, Level.CODING);
        LocalDate oldCr = maxEnd(nodes, Level.CODE_REVIEW);
        LocalDate oldQaSource = qaSource(oldCr, oldCoding);
        boolean hadCr = oldCr != null;

        List<Node> changed = new ArrayList<>();
        edited.start = newStart;
        edited.end = newEnd;
        changed.add(edited);

        // Codificação -> Code Review
        LocalDate newCoding = maxEnd(nodes, Level.CODING);
        if (oldCoding != null && newCoding != null) {
            int delta = workingDelta(oldCoding, newCoding, holidays);
            if (delta != 0) shiftLevel(nodes, Level.CODE_REVIEW, edited, delta, holidays, changed);
        }
        // Code Review (ou Codificação, sem CR) -> QA
        LocalDate newCr = maxEnd(nodes, Level.CODE_REVIEW);
        boolean hasCr = newCr != null;
        LocalDate newQaSource = qaSource(newCr, maxEnd(nodes, Level.CODING));
        if (hadCr == hasCr && oldQaSource != null && newQaSource != null) {
            int delta = workingDelta(oldQaSource, newQaSource, holidays);
            if (delta != 0) shiftLevel(nodes, Level.QA, edited, delta, holidays, changed);
        }
        return changed;
    }

    private static LocalDate qaSource(LocalDate cr, LocalDate coding) {
        return cr != null ? cr : coding;
    }

    private static void shiftLevel(List<Node> nodes, Level level, Node edited, int delta, Set<LocalDate> holidays,
                                   List<Node> changed) {
        for (Node n : nodes) {
            if (n.level != level || n == edited || !n.dated()) continue;
            n.start = shift(n.start, delta, holidays);
            n.end = shift(n.end, delta, holidays);
            changed.add(n);
        }
    }

    private static List<Node> byLevel(List<Node> nodes, Level level) {
        return nodes.stream().filter(n -> n.level == level).toList();
    }

    static boolean isWorking(LocalDate d, Set<LocalDate> holidays) {
        return SprintPlanCalculator.isWeekday(d) && !holidays.contains(d);
    }

    /** Dias úteis com sinal de {@code from} até {@code to}: conta os úteis em (from, to]; negativo se to antes de from. */
    public static int workingDelta(LocalDate from, LocalDate to, Set<LocalDate> holidays) {
        if (from.equals(to)) return 0;
        int n = 0;
        if (to.isAfter(from)) {
            for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) if (isWorking(d, holidays)) n++;
            return n;
        }
        for (LocalDate d = to.plusDays(1); !d.isAfter(from); d = d.plusDays(1)) if (isWorking(d, holidays)) n--;
        return n;
    }

    /** Move {@code n} dias úteis (n pode ser negativo), pulando fins de semana e feriados. */
    public static LocalDate shift(LocalDate date, int n, Set<LocalDate> holidays) {
        int step = n > 0 ? 1 : -1;
        LocalDate d = date;
        for (int left = Math.abs(n); left > 0; ) {
            d = d.plusDays(step);
            if (isWorking(d, holidays)) left--;
        }
        return d;
    }
}
