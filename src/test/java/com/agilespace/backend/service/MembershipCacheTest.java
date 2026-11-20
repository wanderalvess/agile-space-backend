package com.agilespace.backend.service;

import com.agilespace.backend.dto.MembershipDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class MembershipCacheTest {

    private static MembershipCache.Snapshot snap(String project) {
        return new MembershipCache.Snapshot(List.of(new MembershipDto(project, project, "", "", "Developer", "DEVELOPER",
                false, true, "SQUAD", null, List.of())), false);
    }

    @AfterEach
    void cleanSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void hitDepoisDeGravar_eMissParaOutraImpressao() {
        MembershipCache cache = new MembershipCache(60);
        assertTrue(cache.get("u1", "fp").isEmpty());
        cache.put("u1", "fp", snap("SQ1"));

        assertEquals("SQ1", cache.get("u1", "fp").orElseThrow().memberships().get(0).projectId());
        assertTrue(cache.get("u1", "fp-outro").isEmpty(), "e-mail/conta do Jira mudou: entrada ignorada");
        assertEquals(1, cache.hits());
    }

    @Test
    void expiraDepoisDoTtl() {
        MembershipCache cache = new MembershipCache(60);
        AtomicLong now = new AtomicLong(1_000);
        cache.clock = now::get;
        cache.put("u1", "fp", snap("SQ1"));

        now.addAndGet(59_999);
        assertTrue(cache.get("u1", "fp").isPresent());
        now.addAndGet(1);
        assertTrue(cache.get("u1", "fp").isEmpty(), "com 60 s completos a entrada expirou");
    }

    @Test
    void ttlZeroDesliga() {
        MembershipCache cache = new MembershipCache(0);
        cache.put("u1", "fp", snap("SQ1"));
        assertEquals(0, cache.size());
        assertTrue(cache.get("u1", "fp").isEmpty());
    }

    @Test
    void evictELimpaUmUsuario_evictAllLimpaTudo() {
        MembershipCache cache = new MembershipCache(60);
        cache.put("u1", "fp", snap("SQ1"));
        cache.put("u2", "fp", snap("SQ2"));

        cache.evict("u1");
        assertTrue(cache.get("u1", "fp").isEmpty());
        assertTrue(cache.get("u2", "fp").isPresent());

        cache.evictAll();
        assertEquals(0, cache.size());
    }

    @Test
    void gravacaoSemTransacaoLimpaNaHora() {
        MembershipCache cache = new MembershipCache(60);
        cache.put("u1", "fp", snap("SQ1"));

        cache.evictAllOnWrite();

        assertEquals(0, cache.size());
    }

    @Test
    void gravacaoDentroDeTransacao_limpaAgora_naoLeNemPopulaAteOFimELimpaDeNovo() {
        MembershipCache cache = new MembershipCache(60);
        cache.put("u1", "fp", snap("SQ1"));
        TransactionSynchronizationManager.initSynchronization();

        cache.evictAllOnWrite();
        assertEquals(0, cache.size(), "a própria transação enxerga o que gravou");

        cache.put("u1", "fp", snap("SQ-NAO-CONFIRMADO"));
        assertEquals(0, cache.size(), "dado ainda não confirmado não é publicado");

        // outro thread repovoa com o estado confirmado antes do commit: esta transação não deve lê-lo
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, syncs.size(), "uma única sincronização por transação, mesmo com muitas gravações");
        cache.evictAllOnWrite();
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());

        syncs.get(0).afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        TransactionSynchronizationManager.clearSynchronization();

        cache.put("u1", "fp", snap("SQ1"));
        assertTrue(cache.get("u1", "fp").isPresent(), "depois do fim da transação o cache volta a funcionar");
    }
}
