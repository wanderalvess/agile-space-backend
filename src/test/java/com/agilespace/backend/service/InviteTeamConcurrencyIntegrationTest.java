package com.agilespace.backend.service;

import com.agilespace.backend.domain.Invite;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.InviteRepository;
import com.agilespace.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Convite de equipe (V84) contra um Postgres real: a migração sobe com o esquema validado, o aceite simultâneo nunca
 * passa do limite (UPDATE condicional + trava da linha) e a mesma conta não gasta dois usos.
 * Sem Docker o teste é ignorado, como os demais Testcontainers.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class InviteTeamConcurrencyIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private InviteService service;
    @Autowired private InviteRepository inviteRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE invite_redemptions, invites, project_member_roles, squad_members, projects, users CASCADE");
        jdbc.update("INSERT INTO projects (id, name) VALUES ('SQ', 'Squad Alfa')");
    }

    private User newUser(String id) {
        return userRepository.save(User.builder().id(id).email(id + "@totvs.com.br").name("Pessoa " + id).build());
    }

    private Invite teamInvite(Integer max) {
        return service.createTeamInvite("SQ", "Developer", max, 7, "lead@totvs.com.br", true);
    }

    @Test
    void consumeUseNaoPassaDoLimiteComVariasThreads() throws Exception {
        Invite invite = teamInvite(3);
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            fs.add(pool.submit(() -> {
                start.await();
                Integer n = new org.springframework.transaction.support.TransactionTemplate(txManager)
                        .execute(st -> inviteRepository.consumeUse(invite.getId(), LocalDateTime.now()));
                if (n != null && n == 1) won.incrementAndGet();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : fs) f.get();
        pool.shutdown();

        assertEquals(3, won.get());
        assertEquals(3, jdbc.queryForObject("SELECT uses_count FROM invites WHERE id = ?", Integer.class, invite.getId()));
    }

    @Test
    void aceiteSimultaneoNoUltimoUsoDeixaEntrarSoUmaPessoa() throws Exception {
        Invite invite = teamInvite(1);
        newUser("ana");
        newUser("bia");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger full = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (String id : new String[]{"ana", "bia"}) {
            fs.add(pool.submit(() -> {
                start.await();
                try {
                    service.acceptInvite(invite.getToken(), userRepository.findById(id).orElseThrow());
                    ok.incrementAndGet();
                } catch (ResponseStatusException e) {
                    if (e.getStatusCode() == HttpStatus.CONFLICT) full.incrementAndGet(); else throw e;
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : fs) f.get();
        pool.shutdown();

        assertEquals(1, ok.get());
        assertEquals(1, full.get());
        assertEquals(1, jdbc.queryForObject("SELECT uses_count FROM invites WHERE id = ?", Integer.class, invite.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM invite_redemptions WHERE invite_id = ?", Integer.class, invite.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM project_member_roles WHERE project_id = 'SQ' AND source = 'INVITE' AND is_leadership = false", Integer.class));
    }

    @Test
    void mesmaContaNaoGastaDoisUsosEAuditaOAceite() {
        Invite invite = teamInvite(5);
        User ana = newUser("ana");

        service.acceptInvite(invite.getToken(), ana);
        service.acceptInvite(invite.getToken(), userRepository.findById("ana").orElseThrow());

        assertEquals(1, jdbc.queryForObject("SELECT uses_count FROM invites WHERE id = ?", Integer.class, invite.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM invite_redemptions WHERE invite_id = ?", Integer.class, invite.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'INVITE_ACCEPTED' AND performed_by = 'ana'", Integer.class));
        assertEquals("SQ", userRepository.findById("ana").orElseThrow().getDefaultProjectId());
        assertEquals("PENDING", inviteRepository.findById(invite.getId()).orElseThrow().getStatus());
    }

    @Test
    void revogadoNaoAceitaMaisMasQuemEntrouContinua() {
        Invite invite = teamInvite(5);
        service.acceptInvite(invite.getToken(), newUser("ana"));
        service.revoke(invite.getId(), "SQ", "lead@totvs.com.br");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.acceptInvite(invite.getToken(), newUser("bia")));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM project_member_roles WHERE project_id = 'SQ' AND user_id = 'ana'", Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT revoked_at FROM invites WHERE id = ?", java.sql.Timestamp.class, invite.getId()));
    }
}
