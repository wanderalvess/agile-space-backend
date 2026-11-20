package com.agilespace.backend.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V95: cargo canônico e preenchido a partir do Jira. Normaliza só as duas famílias de grafia, preenche quem está sem
 * cargo com a role do roster, nunca toca em cargo definido (fora a normalização) nem em users.role. Sem Docker é ignorado.
 */
@Testcontainers(disabledWithoutDocker = true)
class UsersJobTitleMigrationTest {

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

    private static void user(JdbcTemplate jdbc, String id, String role, String jobTitle, String jiraAccountId) {
        jdbc.update("INSERT INTO users (id, email, name, role, job_title, jira_account_id) VALUES (?, ?, ?, ?, ?, ?)",
                id, id + "@x.com", id, role, jobTitle, jiraAccountId);
    }

    private static void roster(JdbcTemplate jdbc, String dbId, String squad, String claimedBy, String jiraAccountId, String role, String updatedAt) {
        jdbc.update("INSERT INTO squad_members (db_id, squad_id, jira_account_id, claimed_by_uid, role, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                dbId, squad, jiraAccountId, claimedBy, role, updatedAt);
    }

    private static String cargo(JdbcTemplate jdbc, String id) {
        return jdbc.queryForObject("SELECT job_title FROM users WHERE id = ?", String.class, id);
    }

    @Test
    void normalizaPreencheNaoTocaEmDefinidoNemEmRole_eEIdempotente() {
        DriverManagerDataSource ds = freshDb();
        flyway(ds, "94").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // cargos existentes: duas grafias a normalizar, uma já canônica, liderança e um valor parecido que NÃO mapeia
        user(jdbc, "dev", "MEMBER", "Developer", null);
        user(jdbc, "qa", "MEMBER", "  qa ", null);
        user(jdbc, "qa2", "MEMBER", "Analista de QA", null);
        user(jdbc, "lider", "ADMIN", "Agile Master", null);
        user(jdbc, "senior", "MEMBER", "Desenvolvedor Sênior", null);
        // sem cargo, com roster: nulo e em branco; várias linhas (vale a mais recente); linha sem role ignorada
        user(jdbc, "nulo", "ADMIN", null, null);
        user(jdbc, "branco", "MEMBER", "   ", null);
        user(jdbc, "varias", "MEMBER", null, null);
        user(jdbc, "semrole", "MEMBER", null, null);
        user(jdbc, "semroster", "MEMBER", null, null);
        user(jdbc, "porjira", "MEMBER", null, "jira-porjira");
        user(jdbc, "definido", "MEMBER", "Tech Lead", null);
        roster(jdbc, "S1_nulo", "S1", "nulo", "j-nulo", "Developer", "2026-01-01T00:00:00Z");
        roster(jdbc, "S1_branco", "S1", "branco", "j-branco", "QA", "2026-01-01T00:00:00Z");
        roster(jdbc, "S1_varias", "S1", "varias", "j-varias", "QA", "2026-01-01T00:00:00Z");
        roster(jdbc, "S2_varias", "S2", "varias", "j-varias", "Developer", "2026-06-01T00:00:00Z");
        roster(jdbc, "S1_semrole", "S1", "semrole", "j-semrole", "  ", "2026-01-01T00:00:00Z");
        roster(jdbc, "S1_definido", "S1", "definido", "j-definido", "Developer", "2026-01-01T00:00:00Z");
        roster(jdbc, "S1_porjira", "S1", null, "jira-porjira", "Analista de QA", "2026-01-01T00:00:00Z");

        flyway(ds, "95").migrate();

        assertEquals("Desenvolvedor(a)", cargo(jdbc, "dev"));
        assertEquals("Analista de QA", cargo(jdbc, "qa"));
        assertEquals("Analista de QA", cargo(jdbc, "qa2"));
        assertEquals("Agile Master", cargo(jdbc, "lider"));
        assertEquals("Desenvolvedor Sênior", cargo(jdbc, "senior"), "comparação exata, não 'contém'");
        assertEquals("Desenvolvedor(a)", cargo(jdbc, "nulo"));
        assertEquals("Analista de QA", cargo(jdbc, "branco"));
        assertEquals("Desenvolvedor(a)", cargo(jdbc, "varias"), "várias linhas: vale a mais recente por updated_at");
        assertNull(cargo(jdbc, "semrole"), "linha sem role é ignorada");
        assertNull(cargo(jdbc, "semroster"));
        assertEquals("Analista de QA", cargo(jdbc, "porjira"), "sem claimed_by_uid, casa pela conta do Jira");
        assertEquals("Tech Lead", cargo(jdbc, "definido"), "cargo definido nunca é sobrescrito pelo roster");

        // nível de acesso e papéis por squad intactos
        assertEquals("ADMIN", jdbc.queryForObject("SELECT role FROM users WHERE id = 'nulo'", String.class));
        assertEquals("ADMIN", jdbc.queryForObject("SELECT role FROM users WHERE id = 'lider'", String.class));
        assertEquals("Developer", jdbc.queryForObject("SELECT role FROM squad_members WHERE db_id = 'S1_nulo'", String.class));

        // idempotente: rodar o mesmo SQL de novo não muda nada
        String antes = jdbc.queryForObject("SELECT string_agg(id || '=' || coalesce(job_title, '-'), ',' ORDER BY id) FROM users", String.class);
        jdbc.execute("DELETE FROM flyway_schema_history WHERE version = '95'");
        flyway(ds, "95").migrate();
        String depois = jdbc.queryForObject("SELECT string_agg(id || '=' || coalesce(job_title, '-'), ',' ORDER BY id) FROM users", String.class);
        assertEquals(antes, depois);
    }
}
