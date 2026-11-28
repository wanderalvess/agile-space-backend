package com.agilespace.backend.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Divide o conteúdo de um documento em trechos por seção. Cabeçalhos Markdown (# ... ######) definem o
 * caminho ("A > B > C") e nunca ficam no meio de um trecho; dentro de cada seção os parágrafos são agrupados
 * até ~{@value #TARGET_CHARS} caracteres (o modelo de embedding usado hoje é de frases curtas: trechos grandes
 * diluem o sinal). Blocos de código cercados por ``` não são partidos no meio quando cabem em
 * {@value #MAX_CHARS}; maiores que isso são cortados por linhas. Texto sem cabeçalhos (ex.: .txt do TDN) é
 * tratado como uma única seção. Puro e determinístico: o mesmo texto gera sempre os mesmos trechos.
 */
public final class KnowledgeChunker {

    public static final int TARGET_CHARS = 800;
    public static final int MAX_CHARS = 1200;
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");

    private KnowledgeChunker() {
    }

    public record Chunk(int ordinal, String headingPath, String text) {
    }

    public static List<Chunk> chunk(String content) {
        List<Chunk> out = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return out;
        }
        String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        String[] stack = new String[7];
        List<String> blocks = new ArrayList<>();
        StringBuilder block = new StringBuilder();
        boolean inFence = false;
        String path = "";

        for (String line : lines) {
            String trimmed = line.stripTrailing();
            if (trimmed.stripLeading().startsWith("```")) {
                inFence = !inFence;
                appendLine(block, trimmed);
                continue;
            }
            if (inFence) {
                appendLine(block, trimmed);
                continue;
            }
            Matcher m = HEADING.matcher(trimmed);
            if (m.matches()) {
                endBlock(blocks, block);
                emit(out, path, blocks);
                int level = m.group(1).length();
                stack[level] = m.group(2).trim();
                for (int i = level + 1; i < stack.length; i++) {
                    stack[i] = null;
                }
                path = joinPath(stack);
                continue;
            }
            if (trimmed.isBlank()) {
                endBlock(blocks, block);
            } else {
                appendLine(block, trimmed);
            }
        }
        endBlock(blocks, block);
        emit(out, path, blocks);
        return out;
    }

    private static void appendLine(StringBuilder block, String line) {
        if (block.length() > 0) {
            block.append('\n');
        }
        block.append(line);
    }

    private static void endBlock(List<String> blocks, StringBuilder block) {
        if (block.length() > 0) {
            String s = block.toString().strip();
            if (!s.isEmpty()) {
                blocks.add(s);
            }
            block.setLength(0);
        }
    }

    private static String joinPath(String[] stack) {
        StringBuilder sb = new StringBuilder();
        for (String s : stack) {
            if (s != null) {
                if (sb.length() > 0) {
                    sb.append(" > ");
                }
                sb.append(s);
            }
        }
        return sb.length() > 1000 ? sb.substring(0, 1000) : sb.toString();
    }

    /** Agrupa os blocos da seção em trechos e esvazia a lista. */
    private static void emit(List<Chunk> out, String path, List<String> blocks) {
        if (blocks.isEmpty()) {
            return;
        }
        StringBuilder cur = new StringBuilder();
        for (String b : blocks) {
            for (String piece : splitLarge(b)) {
                if (cur.length() > 0 && cur.length() + 2 + piece.length() > TARGET_CHARS) {
                    out.add(new Chunk(out.size(), path, cur.toString()));
                    cur.setLength(0);
                }
                if (cur.length() > 0) {
                    cur.append("\n\n");
                }
                cur.append(piece);
            }
        }
        if (cur.length() > 0) {
            out.add(new Chunk(out.size(), path, cur.toString()));
        }
        blocks.clear();
    }

    /** Bloco que passa de MAX_CHARS é cortado em linhas; linha longa demais é cortada no último espaço. */
    private static List<String> splitLarge(String block) {
        List<String> parts = new ArrayList<>();
        if (block.length() <= MAX_CHARS) {
            parts.add(block);
            return parts;
        }
        StringBuilder cur = new StringBuilder();
        for (String line : block.split("\n", -1)) {
            String rest = line;
            while (rest.length() > MAX_CHARS) {
                int cut = rest.lastIndexOf(' ', MAX_CHARS);
                if (cut < MAX_CHARS / 2) {
                    cut = MAX_CHARS;
                }
                flushInto(parts, cur);
                parts.add(rest.substring(0, cut).strip());
                rest = rest.substring(cut).stripLeading();
            }
            if (cur.length() > 0 && cur.length() + 1 + rest.length() > MAX_CHARS) {
                flushInto(parts, cur);
            }
            if (cur.length() > 0) {
                cur.append('\n');
            }
            cur.append(rest);
        }
        flushInto(parts, cur);
        parts.removeIf(String::isBlank);
        return parts;
    }

    private static void flushInto(List<String> parts, StringBuilder cur) {
        if (cur.length() > 0) {
            parts.add(cur.toString());
            cur.setLength(0);
        }
    }
}
