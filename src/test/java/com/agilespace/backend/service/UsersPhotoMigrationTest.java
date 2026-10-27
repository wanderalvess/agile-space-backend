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

/** V94: foto de perfil. Aditiva: usuários existentes ficam intactos, com as duas colunas NULL. Sem Docker é ignorado. */
@Testcontainers(disabledWithoutDocker = true)
class UsersPhotoMigrationTest {

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

    @Test
    void adicionaColunasNulaveis_eNaoMexeNosUsuariosExistentes() {
        DriverManagerDataSource ds = freshDb();
        flyway(ds, "93").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO users (id, email, name) VALUES ('u1', 'a@x.com', 'Ana')");

        flyway(ds, "94").migrate();

        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'users' AND column_name IN ('photo_key', 'photo_updated_at') AND is_nullable = 'YES'",
                Integer.class));
        assertEquals("timestamp with time zone", jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_name = 'users' AND column_name = 'photo_updated_at'", String.class));
        assertNull(jdbc.queryForObject("SELECT photo_key FROM users WHERE id = 'u1'", String.class));
        assertEquals("Ana", jdbc.queryForObject("SELECT name FROM users WHERE id = 'u1'", String.class));
    }
}
