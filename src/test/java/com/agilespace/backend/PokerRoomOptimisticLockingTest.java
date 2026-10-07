package com.agilespace.backend;

import com.agilespace.backend.security.JwtTokenUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Trava otimista da sala de Poker contra um Postgres real (Testcontainers): as migrations rodam do
 * zero com o schema validado pelo Hibernate (cobre a V30), a versão sobe a cada gravação já na
 * resposta (saveAndFlush) e uma gravação com versão velha vira 409 sem alterar a sala. Sem Docker
 * o teste é ignorado, como o E2EAgileCycleTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
public class PokerRoomOptimisticLockingTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenUtil jwtTokenUtil;

    @Test
    public void staleVersionIsRejectedWithConflictAndDoesNotOverwriteTheRoom() throws Exception {
        String token = jwtTokenUtil.generateToken("lock-user", "admin@agilespace.com", "Admin", "ADMIN", "TESTPROJ", "Segment", "Tribe");

        // Sala nova: começa na versão 0
        mockMvc.perform(post("/api/poker")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"room-lock-1\",\"title\":\"Sala\",\"deckType\":\"fibonacci\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(0));

        // Gravação sobre a versão atual: a resposta já traz a versão incrementada (flush antes do retorno)
        mockMvc.perform(post("/api/poker")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"room-lock-1\",\"title\":\"Alterada\",\"version\":0}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));

        // Outra ação que partiu da versão 0: conflito, não 500, e a sala não muda
        mockMvc.perform(post("/api/poker")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"room-lock-1\",\"title\":\"Atrasada\",\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        mockMvc.perform(get("/api/poker/room-lock-1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Alterada"))
                .andExpect(jsonPath("$.version").value(1));

        // Cliente que não envia a versão (antigo, MCP) continua gravando, sem virar INSERT duplicado
        mockMvc.perform(post("/api/poker")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"room-lock-1\",\"title\":\"Sem versao\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Sem versao"))
                .andExpect(jsonPath("$.version").value(2));
    }
}
