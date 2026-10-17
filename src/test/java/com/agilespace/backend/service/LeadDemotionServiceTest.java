package com.agilespace.backend.service;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.LeadDemotionDto;
import com.agilespace.backend.dto.LeadRestoreDto;
import com.agilespace.backend.repository.ApiKeyRepository;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.LeadPolicy;
import com.agilespace.backend.security.UserSessionGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Etapa 4: a ferramenta de rebaixamento (dry-run por padrão, só quem tem vínculo, lote, backup, auditoria, sessão,
 * forceDemote) e a restauração. O SQL em si (vínculo por conta/e-mail/Jira e a tabela de backup) é exercitado contra um
 * Postgres real em {@code LeadDemotionIntegrationTest}; aqui o JdbcTemplate é um mock que devolve as linhas de liderança.
 */
@DisplayName("LeadDemotionService - rebaixar LEAD com vínculo, dry-run, lote, forceDemote e restauração")
class LeadDemotionServiceTest {

    private UserRepository users;
    private ApiKeyRepository keys;
    private AuditLogRepository audit;
    private JdbcTemplate jdbc;
    private UserSessionGuard guard;
    private LeadDemotionService service;

    private final List<User> store = new ArrayList<>();
    /** user_id -> project_up das linhas de liderança que o SQL devolveria */
    private final List<String[]> leadershipRows = new ArrayList<>();
    private final List<String> backupIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        keys = mock(ApiKeyRepository.class);
        audit = mock(AuditLogRepository.class);
        jdbc = mock(JdbcTemplate.class);
        guard = mock(UserSessionGuard.class);
        service = new LeadDemotionService(users, keys, audit, jdbc, LeadPolicy.OFF);
        service.setSessionGuard(guard);

        when(users.findById(anyString())).thenAnswer(i -> store.stream().filter(u -> u.getId().equals(i.getArgument(0))).findFirst());
        when(users.findByRoleIgnoreCase("LEAD")).thenAnswer(i -> store.stream().filter(u -> "LEAD".equalsIgnoreCase(u.getRole())).toList());
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        when(keys.countActiveByOwner(anyString(), any())).thenReturn(0L);
        doAnswer(i -> {
            RowCallbackHandler handler = i.getArgument(1);
            for (String[] row : leadershipRows) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString("user_id")).thenReturn(row[0]);
                when(rs.getString("project_up")).thenReturn(row[1]);
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class));
        when(jdbc.queryForList(anyString(), eq(String.class))).thenAnswer(i -> List.copyOf(backupIds));
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class))).thenAnswer(i -> {
            String id = (String) i.getArguments()[2];
            return backupIds.contains(id) ? List.of(id) : List.<String>of();
        });

        user("admin", "ADMIN", "adm@totvs.com.br");
        user("a-ana", "LEAD", "ana@totvs.com.br");
        user("b-bia", "LEAD", "bia@totvs.com.br");
        user("c-caio", "LEAD", "caio@totvs.com.br");
        user("d-dora", "LEAD", "dora@totvs.com.br");
        user("m-maria", "MEMBER", "maria@totvs.com.br");
        leadershipRows.add(new String[] {"a-ana", "SQ1"});
        leadershipRows.add(new String[] {"a-ana", "SQ2"});
        leadershipRows.add(new String[] {"a-ana", "SQ1"});
        leadershipRows.add(new String[] {"c-caio", "SQ3"});
        leadershipRows.add(new String[] {"m-maria", "SQ1"}); // MEMBER com liderança: fora do universo
        // b-bia e d-dora: LEAD sem nenhuma liderança
    }

    private User user(String id, String role, String email) {
        User u = User.builder().id(id).email(email).name("Pessoa " + id).role(role).active(true).build();
        store.add(u);
        return u;
    }

    private User get(String id) {
        return store.stream().filter(u -> u.getId().equals(id)).findFirst().orElseThrow();
    }

    private static LeadDemotionDto.Item item(LeadDemotionDto dto, String id) {
        return dto.users().stream().filter(i -> i.userId().equals(id)).findFirst().orElseThrow();
    }

    // ---------- dry-run ----------

    @Test
    @DisplayName("Dry-run (padrão): classifica, não grava nada e responde o que o apply faria")
    void dryRunWritesNothing() {
        LeadDemotionDto dto = service.demote(false, 20, null, false, "admin");

        assertEquals("DRY_RUN", dto.mode());
        assertEquals("off", dto.leadLevel());
        assertEquals(4, dto.summary().leadUsers());
        assertEquals(2, dto.summary().hasLeadershipLink());
        assertEquals(2, dto.summary().noLeadershipLink());
        assertEquals(2, dto.summary().wouldDemote());
        assertEquals(0, dto.summary().demoted());
        assertEquals(2, dto.summary().keptNoLeadershipLink());

        LeadDemotionDto.Item ana = item(dto, "a-ana");
        assertEquals("HAS_LEADERSHIP_LINK", ana.classification());
        assertEquals(List.of("SQ1", "SQ2"), ana.leadershipSquads(), "sem repetição, ordenado");
        assertEquals("WOULD_DEMOTE", ana.action());
        assertEquals("NO_LEADERSHIP_LINK", item(dto, "b-bia").classification());
        assertEquals("KEPT_NO_LEADERSHIP_LINK", item(dto, "b-bia").action());
        assertTrue(dto.users().stream().noneMatch(i -> i.userId().equals("m-maria") || i.userId().equals("admin")));

        verify(users, never()).save(any());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(audit, never()).save(any());
        verify(guard, never()).evict(anyString());
        assertEquals("LEAD", get("a-ana").getRole());
    }

    // ---------- apply ----------

    @Test
    @DisplayName("Apply: rebaixa SOMENTE quem tem vínculo, grava backup, audita e derruba o cache de sessão")
    void applyDemotesOnlyThoseWithLink() {
        LeadDemotionDto dto = service.demote(true, 20, null, false, "admin");

        assertEquals("APPLIED", dto.mode());
        assertEquals(2, dto.summary().demoted());
        assertEquals("DEMOTED", item(dto, "a-ana").action());
        assertEquals("DEMOTED", item(dto, "c-caio").action());
        assertEquals("KEPT_NO_LEADERSHIP_LINK", item(dto, "b-bia").action());
        assertEquals("MEMBER", get("a-ana").getRole());
        assertEquals("MEMBER", get("c-caio").getRole());
        assertEquals("LEAD", get("b-bia").getRole(), "sem vínculo: não rebaixa");
        assertEquals("LEAD", get("d-dora").getRole());

        verify(jdbc, times(2)).update(contains("users_role_lead_backup"), any(Object[].class));
        ArgumentCaptor<AuditLog> logs = ArgumentCaptor.forClass(AuditLog.class);
        verify(audit, times(2)).save(logs.capture());
        assertTrue(logs.getAllValues().stream().allMatch(l -> "LEAD_DEMOTED".equals(l.getAction()) && "adm@totvs.com.br".equals(l.getPerformedBy())));
        assertTrue(logs.getAllValues().get(0).getDetails().contains("LEAD -> MEMBER"));
        verify(guard).evict("a-ana");
        verify(guard).evict("c-caio");
        verify(guard, never()).evict("b-bia");
        assertTrue(dto.warnings().stream().anyMatch(w -> w.contains("sem nenhum vínculo")), "avisa sobre quem ficou");
    }

    @Test
    @DisplayName("Apply respeita o lote: só os N primeiros elegíveis; o resto é OVER_LIMIT para a próxima rodada")
    void applyRespectsBatchLimit() {
        LeadDemotionDto first = service.demote(true, 1, null, false, "admin");

        assertEquals(1, first.summary().demoted());
        assertEquals(1, first.summary().overLimit());
        assertEquals("DEMOTED", item(first, "a-ana").action());
        assertEquals("OVER_LIMIT", item(first, "c-caio").action());
        assertEquals("LEAD", get("c-caio").getRole());

        LeadDemotionDto second = service.demote(true, 1, null, false, "admin");
        assertEquals(1, second.summary().demoted(), "a rodada seguinte pega o próximo");
        assertEquals("MEMBER", get("c-caio").getRole());
        assertEquals(1, second.summary().leadUsers() - second.summary().noLeadershipLink(), "só o Caio sobrou com vínculo");
    }

    @Test
    @DisplayName("Dry-run com lote também marca OVER_LIMIT (o admin vê o que cabe em cada rodada)")
    void dryRunShowsOverLimit() {
        LeadDemotionDto dto = service.demote(false, 1, null, false, "admin");

        assertEquals(1, dto.summary().wouldDemote());
        assertEquals(1, dto.summary().overLimit());
    }

    // ---------- userId e forceDemote ----------

    @Test
    @DisplayName("userId restringe a uma conta; NO_LEADERSHIP_LINK sem forceDemote não é rebaixada nem com apply")
    void singleUserWithoutForce() {
        LeadDemotionDto dto = service.demote(true, 20, "b-bia", false, "admin");

        assertEquals(1, dto.summary().leadUsers());
        assertEquals("KEPT_NO_LEADERSHIP_LINK", dto.users().get(0).action());
        assertEquals("LEAD", get("b-bia").getRole());
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("forceDemote + userId: o admin rebaixa conscientemente uma conta sem liderança (auditado como LEAD_DEMOTED_FORCED)")
    void forceDemote() {
        LeadDemotionDto dry = service.demote(false, 20, "b-bia", true, "admin");
        assertEquals("WOULD_DEMOTE_FORCED", dry.users().get(0).action());
        assertEquals("LEAD", get("b-bia").getRole(), "dry-run não grava");

        LeadDemotionDto dto = service.demote(true, 20, "b-bia", true, "admin");

        assertEquals("DEMOTED_FORCED", dto.users().get(0).action());
        assertEquals("MEMBER", get("b-bia").getRole());
        ArgumentCaptor<AuditLog> log = ArgumentCaptor.forClass(AuditLog.class);
        verify(audit).save(log.capture());
        assertEquals("LEAD_DEMOTED_FORCED", log.getValue().getAction());
        verify(jdbc).update(contains("users_role_lead_backup"), any(Object[].class));
        verify(guard).evict("b-bia");
    }

    @Test
    @DisplayName("Erros: forceDemote sem userId, limit fora de 1..200, usuário inexistente e conta que não é LEAD")
    void errors() {
        assertEquals(400, status(() -> service.demote(true, 20, null, true, "admin")));
        assertEquals(400, status(() -> service.demote(true, 0, null, false, "admin")));
        assertEquals(400, status(() -> service.demote(true, 201, null, false, "admin")));
        assertEquals(404, status(() -> service.demote(true, 20, "nao-existe", false, "admin")));
        assertEquals(409, status(() -> service.demote(true, 20, "m-maria", false, "admin")));
        assertEquals(409, status(() -> service.demote(true, 20, "admin", true, "admin")), "ADMIN nunca é rebaixado por aqui");
        verify(users, never()).save(any());
    }

    private static int status(Runnable r) {
        try {
            r.run();
            return 200;
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        }
    }

    // ---------- chaves de API e modo ----------

    @Test
    @DisplayName("Avisa quantas chaves de API ativas o usuário tem e que a renovação corta os escopos; não altera as chaves")
    void apiKeysWarning() {
        when(keys.countActiveByOwner(eq("a-ana"), any())).thenReturn(2L);

        LeadDemotionDto dto = service.demote(true, 20, null, false, "admin");

        LeadDemotionDto.Item ana = item(dto, "a-ana");
        assertEquals(2, ana.activeApiKeys());
        assertTrue(ana.apiKeysNote().contains("2 chave(s) de API ativa(s)"));
        assertTrue(ana.apiKeysNote().contains("renovação corta os escopos de liderança"));
        assertNull(item(dto, "c-caio").apiKeysNote());
        assertEquals(0, item(dto, "c-caio").activeApiKeys());
        verify(keys, never()).save(any());
    }

    @Test
    @DisplayName("Com lead-level=legacy avisa que rebaixar REMOVE o acesso global; o resultado diz o modo em vigor")
    void warnsWhenLegacy() {
        LeadDemotionService legacy = new LeadDemotionService(users, keys, audit, jdbc, LeadPolicy.LEGACY);

        LeadDemotionDto dto = legacy.demote(false, 20, null, false, "admin");

        assertEquals("legacy", dto.leadLevel());
        assertTrue(dto.warnings().stream().anyMatch(w -> w.contains("REMOVE o acesso global")));
    }

    // ---------- restore ----------

    @Test
    @DisplayName("Restore (todos): volta LEAD de quem está MEMBER, pula quem já é LEAD, ADMIN promovido e conta apagada")
    void restoreAll() {
        service.demote(true, 20, null, false, "admin"); // ana e caio -> MEMBER
        backupIds.addAll(List.of("a-ana", "c-caio", "b-bia", "fantasma"));
        user("x-xuxa", "ADMIN", "xuxa@totvs.com.br");
        backupIds.add("x-xuxa");
        reset(audit, guard);

        LeadRestoreDto dto = service.restore(null, "admin");

        assertEquals(2, dto.restored());
        assertEquals("LEAD", get("a-ana").getRole());
        assertEquals("LEAD", get("c-caio").getRole());
        Map<String, String> reasons = new java.util.HashMap<>();
        dto.users().forEach(i -> reasons.put(i.userId(), i.result() + (i.reason() == null ? "" : ":" + i.reason())));
        assertEquals("SKIPPED:ALREADY_LEAD", reasons.get("b-bia"));
        assertEquals("SKIPPED:CURRENT_ROLE_NOT_MEMBER", reasons.get("x-xuxa"));
        assertEquals("SKIPPED:USER_NOT_FOUND", reasons.get("fantasma"));
        assertEquals("ADMIN", get("x-xuxa").getRole(), "nunca sobrescreve um ADMIN");
        verify(audit, atLeast(2)).save(argThat(l -> "LEAD_RESTORED".equals(l.getAction())));
        verify(guard).evict("a-ana");
    }

    @Test
    @DisplayName("Restore (um usuário): restaura só ele; fora do backup devolve NOT_IN_BACKUP; é idempotente")
    void restoreOne() {
        service.demote(true, 20, null, false, "admin");
        backupIds.addAll(List.of("a-ana", "c-caio"));

        LeadRestoreDto one = service.restore("a-ana", "admin");
        assertEquals(1, one.restored());
        assertEquals("LEAD", get("a-ana").getRole());
        assertEquals("MEMBER", get("c-caio").getRole(), "os outros ficam como estavam");

        LeadRestoreDto again = service.restore("a-ana", "admin");
        assertEquals(0, again.restored());
        assertEquals("ALREADY_LEAD", again.users().get(0).reason());

        LeadRestoreDto missing = service.restore("m-maria", "admin");
        assertEquals("NOT_IN_BACKUP", missing.users().get(0).reason());
        assertEquals("USER_NOT_FOUND", service.restore("fantasma", "admin").users().get(0).reason());
    }
}
