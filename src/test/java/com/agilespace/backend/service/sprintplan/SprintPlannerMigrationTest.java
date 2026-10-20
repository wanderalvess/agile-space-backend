package com.agilespace.backend.service.sprintplan;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V86/V87 (Sprint Planner) contra Postgres real: um plano por squad+sprint, versão começa em 0, CHECK de datas e
 * cascata ao apagar o plano. Sem Docker é ignorado.
 */
@Testcontainers(disabledWithoutDocker = true)
class SprintPlannerMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void v86eV87_criamAsTabelas_comUnicidadeChecksECascata() {
        DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").outOfOrder(true).target("87").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        jdbc.update("INSERT INTO squads (id, name) VALUES ('SQ1', 'Squad 1')");
        plan(jdbc, "P1", "S1");
        plan(jdbc, "P0", null);
        plan(jdbc, "P00", null); // sem sprint do Jira pode haver mais de um rascunho
        assertThrows(DuplicateKeyException.class, () -> plan(jdbc, "P2", "S1"));
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM sprint_plans WHERE id = 'P1'", Long.class));
        assertEquals("RASCUNHO", jdbc.queryForObject("SELECT status FROM sprint_plans WHERE id = 'P1'", String.class));

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO sprint_plan_absences (id, plan_id, jira_account_id, start_date, end_date, created_by) "
                        + "VALUES ('A0', 'P1', 'ana', '2026-11-05', '2026-11-04', 'u')"));

        jdbc.update("INSERT INTO sprint_plan_person (plan_id, jira_account_id, focus_factor_pct) VALUES ('P1', 'ana', 70.5)");
        jdbc.update("INSERT INTO sprint_plan_reserve_types (id, plan_id, label) VALUES ('R1', 'P1', 'Suporte')");
        jdbc.update("INSERT INTO sprint_plan_absences (id, plan_id, jira_account_id, start_date, end_date, created_by) "
                + "VALUES ('A1', 'P1', 'ana', '2026-11-04', '2026-11-04', 'u')");
        jdbc.update("INSERT INTO sprint_plan_holidays (plan_id, day, label) VALUES ('P1', '2026-11-02', 'Finados')");
        jdbc.update("INSERT INTO sprint_plan_items (id, plan_id, parent_key, sort_order, added_by) VALUES ('I1', 'P1', 'ABC-1', 0, 'u')");
        jdbc.update("INSERT INTO sprint_plan_assignments (id, plan_id, item_id, subtask_key, class) VALUES ('G1', 'P1', 'I1', 'ABC-2', 'DEV')");
        assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                "INSERT INTO sprint_plan_items (id, plan_id, parent_key, sort_order, added_by) VALUES ('I2', 'P1', 'ABC-1', 1, 'u')"));

        jdbc.update("DELETE FROM sprint_plans WHERE id = 'P1'");
        for (String t : new String[]{"sprint_plan_person", "sprint_plan_reserve_types", "sprint_plan_absences",
                "sprint_plan_holidays", "sprint_plan_items", "sprint_plan_assignments"}) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + t, Integer.class), t + " deve sair em cascata");
        }
    }

    private static void plan(JdbcTemplate jdbc, String id, String sprint) {
        jdbc.update("INSERT INTO sprint_plans (id, squad_id, jira_sprint_id, sprint_name, start_date, end_date, created_by) "
                + "VALUES (?, 'SQ1', ?, 'Sprint', '2026-11-02', '2026-11-13', 'u')", id, sprint);
    }
}
