package com.agilespace.backend.service;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V82 (Etapa 5, passo 2 do vínculo): apaga {@code users.squad_id} com backup. Contra um Postgres real, aplicando as migrações de
 * verdade até a V81, populando {@code users} e só então a V82. Cada teste usa um banco novo dentro do mesmo container. Sem Docker
 * é ignorado.
 */
@Testcontainers(disabledWithoutDocker = true)
class RetireUsersSquadIdMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static DriverManagerDataSource freshDb() {
        String name = "mig_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource admin = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        new JdbcTemplate(admin).execute("CREATE DATABASE " + name);
        String url = POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Flyway flyway(DriverManagerDataSource ds, String target) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration").outOfOrder(true).target(target).load();
    }

    private static void user(JdbcTemplate jdbc, String id, String squadId, String defaultProject) {
        jdbc.update("INSERT INTO users (id, email, name, role, is_active, is_guest, squad_id, default_project_id) "
                + "VALUES (?, ?, ?, 'MEMBER', TRUE, FALSE, ?, ?)", id, id + "@totvs.com.br", "Pessoa " + id, squadId, defaultProject);
    }

    /** Preenchido, nulo, divergente do default_project_id, só o default, vazio e com espaços/caixa diferentes. */
    private static void seed(JdbcTemplate jdbc) {
        user(jdbc, "u-cheio", "SQ1", "SQ1");
        user(jdbc, "u-nulo", null, null);
        user(jdbc, "u-diverge", "SQ1", "SQ2");
        user(jdbc, "u-so-default", null, "SQ3");
        user(jdbc, "u-vazio", "", "");
        user(jdbc, "u-caixa", " Compras ", "COMPRAS");
    }

    private static boolean hasColumn(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name = 'users' AND column_name = 'squad_id'", Integer.class) == 1;
    }

    private static String script() throws Exception {
        return new String(new ClassPathResource("db/migration/V82__retire_users_squad_id.sql").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void dependenciasDaColuna_naoExistemEmV81_porIssoODropNaoPrecisaDeCascade() {
        DriverManagerDataSource ds = freshDb();
        flyway(ds, "81").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        String attnum = "(SELECT attnum FROM pg_attribute WHERE attrelid = 'public.users'::regclass AND attname = 'squad_id')";
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pg_index WHERE indrelid = 'public.users'::regclass AND " + attnum + " = ANY(indkey)", Integer.class), "índice");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pg_constraint WHERE conrelid = 'public.users'::regclass AND " + attnum + " = ANY(conkey)", Integer.class), "constraint/FK de users");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pg_constraint WHERE confrelid = 'public.users'::regclass AND " + attnum + " = ANY(confkey)", Integer.class), "FK de outra tabela para a coluna");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pg_depend d JOIN pg_rewrite r ON r.oid = d.objid WHERE d.refobjid = 'public.users'::regclass AND d.refobjsubid = " + attnum, Integer.class), "view");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pg_trigger WHERE tgrelid = 'public.users'::regclass AND NOT tgisinternal", Integer.class), "trigger");
    }

    @Test
    void v82_copiaTodosOsUsuariosParaOBackup_eApagaAColuna_semMexerNoResto() {
        DriverManagerDataSource ds = freshDb();
        flyway(ds, "81").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seed(jdbc);
        List<Map<String, Object>> usersBefore = jdbc.queryForList(
                "SELECT id, email, name, role, is_active, is_guest, default_project_id FROM users ORDER BY id");
        List<Map<String, Object>> expectedBackup = jdbc.queryForList("SELECT id, squad_id, default_project_id FROM users ORDER BY id");
        assertTrue(hasColumn(jdbc));

        flyway(ds, "82").migrate();

        assertFalse(hasColumn(jdbc), "a coluna foi apagada");
        assertEquals(usersBefore, jdbc.queryForList("SELECT id, email, name, role, is_active, is_guest, default_project_id FROM users ORDER BY id"),
                "users mantém todo o resto");
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM users_squad_legacy_backup", Integer.class), "uma linha por usuário");
        assertEquals(expectedBackup, jdbc.queryForList("SELECT id, squad_id, default_project_id FROM users_squad_legacy_backup ORDER BY id"),
                "valores idênticos, inclusive nulos, vazios e caixa/espaços");
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(backed_up_at) FROM users_squad_legacy_backup", Integer.class));
    }

    @Test
    void v82_comBackupIncompleto_abortaENadaEApagado() {
        DriverManagerDataSource ds = freshDb();
        flyway(ds, "81").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seed(jdbc);
        // Cenário controlado: o backup "perde" uma linha (trigger que descarta a gravação de u-diverge).
        jdbc.execute("CREATE TABLE users_squad_legacy_backup (id VARCHAR(255) PRIMARY KEY, squad_id VARCHAR(255), "
                + "default_project_id VARCHAR(255), backed_up_at TIMESTAMP NOT NULL DEFAULT NOW())");
        jdbc.execute("CREATE FUNCTION drop_one() RETURNS trigger AS $$ BEGIN IF NEW.id = 'u-diverge' THEN RETURN NULL; END IF; RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER t_drop_one BEFORE INSERT ON users_squad_legacy_backup FOR EACH ROW EXECUTE FUNCTION drop_one()");

        FlywayException ex = assertThrows(FlywayException.class, () -> flyway(ds, "82").migrate());
        assertTrue(String.valueOf(ex).contains("backup de users.squad_id incompleto"), "aborta com a EXCEPTION de conferência: " + ex);

        assertTrue(hasColumn(jdbc), "NADA foi apagado: a coluna continua");
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
        assertEquals("SQ1", jdbc.queryForObject("SELECT squad_id FROM users WHERE id = 'u-diverge'", String.class), "dados originais intactos");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '82' AND success", Integer.class),
                "V82 não consta como aplicada");
    }

    @Test
    void v82_reexecutadaSemAColuna_naoFalha_eNaoApagaOBackup() throws Exception {
        DriverManagerDataSource ds = freshDb();
        flyway(ds, "81").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seed(jdbc);
        flyway(ds, "82").migrate();
        List<Map<String, Object>> backup = jdbc.queryForList("SELECT * FROM users_squad_legacy_backup ORDER BY id");

        assertDoesNotThrow(() -> jdbc.execute(script()), "segunda execução em base que já não tem a coluna");

        assertFalse(hasColumn(jdbc));
        assertEquals(backup, jdbc.queryForList("SELECT * FROM users_squad_legacy_backup ORDER BY id"), "backup intocado");
    }

    @Test
    void reversao_restauraOsValoresAPartirDoBackup() {
        DriverManagerDataSource ds = freshDb();
        flyway(ds, "81").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seed(jdbc);
        List<Map<String, Object>> original = jdbc.queryForList("SELECT id, squad_id FROM users ORDER BY id");
        flyway(ds, "82").migrate();
        assertFalse(hasColumn(jdbc));

        jdbc.execute("ALTER TABLE users ADD COLUMN squad_id VARCHAR(255)");
        jdbc.execute("UPDATE users u SET squad_id = b.squad_id FROM users_squad_legacy_backup b WHERE b.id = u.id");

        assertEquals(original, jdbc.queryForList("SELECT id, squad_id FROM users ORDER BY id"), "valores originais de volta, nulos inclusive");
    }
}
