package com.agilespace.backend.architecture;

import com.agilespace.backend.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Trava de regressão da Etapa 5 do vínculo (passo 1): {@code users.squad_id} foi aposentada. A coluna continua no banco até o
 * passo 2 (backup + DROP COLUMN), mas NENHUM código de produção pode ler ou gravá-la, exceto o SQL de diagnóstico de legado
 * do {@code MembershipReportService} (marcado com {@code LEGACY-SQUAD-ID-DIAGNOSTIC}, a remover junto com a coluna).
 *
 * <p>Duas travas: (1) a entidade {@code User} não tem o campo nem os acessores (reflexão: se alguém reinserir
 * {@code getSquadId()}/{@code setSquadId()}/{@code squadId}, falha); (2) varredura de {@code src/main/java} (sem comentários) atrás
 * de SQL/JPQL que cite a coluna de {@code users}.
 */
class UsersSquadIdColumnRetiredTest {

    private static final Path MAIN_JAVA = Path.of("src", "main", "java");

    /** Único arquivo autorizado a citar users.squad_id (SQL nativo de diagnóstico do legado). */
    private static final Set<String> DIAGNOSTIC_ALLOWLIST = Set.of("MembershipReportService.java");
    private static final String DIAGNOSTIC_MARKER = "LEGACY-SQUAD-ID-DIAGNOSTIC";

    /** users.squad_id, u.squad_id, usr.squad_id (SQL) e u.squadId / user.squadId (JPQL). */
    private static final Pattern USERS_COLUMN = Pattern.compile(
            "\\b(users|u|usr)\\s*\\.\\s*squad_id\\b|\\b(u|usr|user)\\s*\\.\\s*squadId\\b", Pattern.CASE_INSENSITIVE);
    /** INSERT/UPDATE direto na tabela users citando a coluna. */
    private static final Pattern WRITES_USERS_COLUMN = Pattern.compile(
            "(update\\s+users\\s+set[^;\"]*\\bsquad_id\\b)|(insert\\s+into\\s+users\\s*\\([^)]*\\bsquad_id\\b)", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("A entidade User não tem squadId/getSquadId/setSquadId nem coluna squad_id mapeada")
    void userEntityHasNoSquadIdAccessors() {
        assertTrue(Arrays.stream(User.class.getDeclaredFields()).noneMatch(f -> f.getName().equalsIgnoreCase("squadId")),
                "campo squadId voltou para User");
        assertTrue(Arrays.stream(User.class.getMethods()).noneMatch(m -> m.getName().equals("getSquadId") || m.getName().equals("setSquadId")),
                "getSquadId()/setSquadId() voltaram para User");
        boolean mapsColumn = Arrays.stream(User.class.getDeclaredFields())
                .map(f -> f.getAnnotation(jakarta.persistence.Column.class))
                .anyMatch(c -> c != null && "squad_id".equalsIgnoreCase(c.name()));
        assertFalse(mapsColumn, "User mapeia a coluna squad_id de novo");
    }

    @Test
    @DisplayName("Nenhum código de produção (fora do diagnóstico de legado) cita users.squad_id em SQL/JPQL")
    void noProductionCodeTouchesTheColumn() throws IOException {
        List<String> violations = new ArrayList<>();
        boolean diagnosticStillMarked = false;
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String raw = Files.readString(file, StandardCharsets.UTF_8);
                String name = file.getFileName().toString();
                if (DIAGNOSTIC_ALLOWLIST.contains(name)) {
                    diagnosticStillMarked |= raw.contains(DIAGNOSTIC_MARKER);
                    continue;
                }
                String code = stripComments(raw);
                if (USERS_COLUMN.matcher(code).find() || WRITES_USERS_COLUMN.matcher(code).find()) {
                    violations.add(MAIN_JAVA.relativize(file).toString());
                }
            }
        }
        assertTrue(violations.isEmpty(), "users.squad_id foi aposentada (Etapa 5): remova a leitura/gravação em " + violations);
        assertTrue(diagnosticStillMarked, "o SQL de diagnóstico de legado precisa continuar marcado com " + DIAGNOSTIC_MARKER
                + " (ou ser removido junto com o DROP COLUMN, atualizando esta lista)");
    }

    @Test
    @DisplayName("O detector pega os usos proibidos e ignora comentários (a trava não é cega)")
    void detectorWorks() {
        assertTrue(USERS_COLUMN.matcher("SELECT u.squad_id FROM users u").find());
        assertTrue(USERS_COLUMN.matcher("SELECT u FROM User u WHERE UPPER(u.squadId) IN :ids").find());
        assertTrue(WRITES_USERS_COLUMN.matcher("UPDATE users SET squad_id = ? WHERE id = ?").find());
        assertTrue(WRITES_USERS_COLUMN.matcher("INSERT INTO users (id, email, squad_id) VALUES (?, ?, ?)").find());
        assertFalse(USERS_COLUMN.matcher(stripComments("// users.squad_id é história\n/* u.squad_id */ int x;")).find());
        assertFalse(USERS_COLUMN.matcher("SELECT sm.squad_id FROM squad_members sm").find());
    }

    /** Remove comentários de bloco/Javadoc e de linha (suficiente para a varredura; strings com // só reduzem a detecção). */
    static String stripComments(String source) {
        String noBlock = source.replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder out = new StringBuilder();
        for (String line : noBlock.split("\n", -1)) {
            int idx = line.indexOf("//");
            out.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return out.toString();
    }
}
