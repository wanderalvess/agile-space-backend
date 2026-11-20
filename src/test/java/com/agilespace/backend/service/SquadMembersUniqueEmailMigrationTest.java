package com.agilespace.backend.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V85: índice único parcial (squad_id, lower(trim(email))) em squad_members. Contra Postgres real: base limpa cria o índice e o
 * enforça; base com duplicatas NÃO falha, NÃO apaga nada, não cria o índice e expõe o relatório. Sem Docker é ignorado.
 */
@Testcontainers(disabledWithoutDocker = true)
class SquadMembersUniqueEmailMigrationTest {

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

    private static void member(JdbcTemplate jdbc, String squad, String account, String name, String email) {
        jdbc.update("INSERT INTO squad_members (db_id, squad_id, jira_account_id, display_name, email) VALUES (?, ?, ?, ?, ?)",
                squad + "_" + account, squad, account, name, email);
    }

    private static boolean indexExists(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'ux_squad_members_squad_email'", Integer.class) == 1;
    }

    @Test
    void baseSemDuplicatas_criaOIndice_eBloqueiaMesmoEmailComCaixaEEspacosDiferentes() {
        DriverManagerDataSource ds = freshDb();
        flyway(ds, "82").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        member(jdbc, "SQ1", "a1", "Ana", "ana@x.com");
        member(jdbc, "SQ1", "b1", "Sem email 1", null);
        member(jdbc, "SQ1", "b2", "Sem email 2", null);
        member(jdbc, "SQ1", "b3", "Vazio", "  ");
        member(jdbc, "SQ1", "b4", "Vazio 2", "");

        flyway(ds, "85").migrate();

        assertTrue(indexExists(jdbc));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM squad_members_duplicate_report", Integer.class));
        member(jdbc, "SQ2", "a1", "Ana", "ana@x.com"); // outra squad pode
        assertThrows(DuplicateKeyException.class, () -> member(jdbc, "SQ1", "manual-sq1-ana", "Ana", " ANA@x.com "));
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM squad_members", Integer.class));
    }

    @Test
    void baseComDuplicatas_naoFalha_naoApagaNada_naoCriaIndice_eListaORelatorio() {
        DriverManagerDataSource ds = freshDb();
        flyway(ds, "82").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        member(jdbc, "SQ1", "jira-ana", "Ana Lima", "ana@x.com");
        member(jdbc, "SQ1", "manual-sq1-ana-lima-abc", "Ana Lima", "Ana@X.com ");
        member(jdbc, "SQ1", "bia", "Bia", "bia@x.com");
        member(jdbc, "SQ2", "ana2", "Ana Lima", "ana@x.com");

        assertDoesNotThrow(() -> flyway(ds, "85").migrate());

        assertFalse(indexExists(jdbc));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM squad_members", Integer.class), "nada foi apagado");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM squad_members_duplicate_report", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT linhas FROM squad_members_duplicate_report WHERE squad_id = 'SQ1'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '85' AND success", Integer.class));
    }
}
