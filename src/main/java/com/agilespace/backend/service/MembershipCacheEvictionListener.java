package com.agilespace.backend.service;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.springframework.stereotype.Component;

/**
 * Listener JPA de {@code ProjectMemberRole} e {@code ProjectConfig}: toda gravação ou remoção dessas entidades, de
 * qualquer serviço (importação do Jira, claim, convite, gestão do time, cadastro...), invalida o cache de vínculos.
 * Um ponto só, para que uma gravação nova não esqueça de invalidar.
 */
@Component
public class MembershipCacheEvictionListener {

    private final MembershipCache cache;

    public MembershipCacheEvictionListener(MembershipCache cache) {
        this.cache = cache;
    }

    @PostPersist
    @PostUpdate
    @PostRemove
    public void onChange(Object entity) {
        cache.evictAllOnWrite();
    }
}
