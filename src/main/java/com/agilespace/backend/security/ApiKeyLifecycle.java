package com.agilespace.backend.security;

import com.agilespace.backend.domain.ApiKey;
import com.agilespace.backend.domain.ApiKeyScope;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.ApiKeyRepository;
import com.agilespace.backend.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validade, renovação e rotação de chaves de API.
 *
 * Toda chave nova vence em {@link #DEFAULT_DAYS} dias (30, 90 ou 180 à escolha; "sem expiração" só para
 * ADMIN). Chave sem {@code expiresAt} é a exceção explícita do admin. As chaves que já existiam na
 * migration V56 ganharam 90 dias a partir do deploy.
 *
 * Renovar reavalia o dono: o papel e os escopos são recalculados com o papel ATUAL da pessoa, então o
 * acesso que foi dado há meses (ou o "acesso total" das chaves legadas) não é congelado além da validade.
 */
@Component
public class ApiKeyLifecycle {

    public static final int DEFAULT_DAYS = 90;
    public static final Set<Integer> ALLOWED_DAYS = Set.of(30, 90, 180);

    private final ApiKeyRepository apiKeyRepository;
    private final UserRepository userRepository;

    public ApiKeyLifecycle(ApiKeyRepository apiKeyRepository, UserRepository userRepository) {
        this.apiKeyRepository = apiKeyRepository;
        this.userRepository = userRepository;
    }

    /** Autorização por squad via vínculos (Etapa 2). Opcional: sem ela só vale users.squad_id, como sempre. */
    private com.agilespace.backend.service.SquadAccessService squadAccessService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSquadAccessService(com.agilespace.backend.service.SquadAccessService squadAccessService) {
        this.squadAccessService = squadAccessService;
    }

    /**
     * Interpreta o pedido de validade. {@code raw} pode ser nulo (padrão 90), número/texto 30, 90 ou 180,
     * ou "never"/"sem-expiracao"/0 (sem expiração, só ADMIN). Devolve a data de vencimento, ou null para
     * "sem expiração".
     */
    public static LocalDateTime resolveExpiry(Object raw, boolean callerIsAdmin, LocalDateTime now) {
        if (raw == null || raw.toString().isBlank()) {
            return now.plusDays(DEFAULT_DAYS);
        }
        String text = raw.toString().trim().toLowerCase();
        boolean never = text.equals("never") || text.equals("sem-expiracao") || text.equals("0") || text.equals("-1");
        if (never) {
            if (!callerIsAdmin) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Só administradores podem criar chave sem expiração.");
            }
            return null;
        }
        int days;
        try {
            days = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Validade inválida: use 30, 90 ou 180 dias.");
        }
        if (!ALLOWED_DAYS.contains(days)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Validade inválida: use 30, 90 ou 180 dias.");
        }
        return now.plusDays(days);
    }

    /** Escopos que um papel pode ter (MEMBER: só leitura de conhecimento e da própria squad). */
    public static Set<String> allowedScopesFor(boolean privileged) {
        if (privileged) {
            return Arrays.stream(ApiKeyScope.values()).map(Enum::name).collect(Collectors.toSet());
        }
        return Set.of(ApiKeyScope.KNOWLEDGE_READ.name(), ApiKeyScope.SQUAD_READ.name());
    }

    public static boolean isPrivileged(String role) {
        return "ADMIN".equalsIgnoreCase(role) || "LEAD".equalsIgnoreCase(role);
    }

    /**
     * Estende a chave por {@code rawDays} (padrão 90) e reavalia dono, papel e escopos.
     * Recusa chave revogada e dono inativo. Sem dono registrado (chave muito antiga) só estende.
     */
    public ApiKey renew(ApiKey key, Object rawDays, boolean callerIsAdmin) {
        if (key.getRevokedAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chave revogada não pode ser renovada.");
        }
        LocalDateTime now = LocalDateTime.now();
        key.setExpiresAt(resolveExpiry(rawDays, callerIsAdmin, now));

        reevaluateOwner(key);
        return apiKeyRepository.save(key);
    }

    /** Recalcula papel e escopos da chave com o papel ATUAL do dono (ver renew). */
    private void reevaluateOwner(ApiKey key) {
        if (key.getOwnerUserId() != null) {
            User owner = userRepository.findById(key.getOwnerUserId()).orElse(null);
            if (owner == null || !owner.isActive()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O dono da chave não existe mais ou está inativo.");
            }
            boolean privileged = isPrivileged(owner.getRole());
            key.setOwnerRole(owner.getRole());
            Set<String> allowed = allowedScopesFor(privileged);
            Set<String> current = key.getScopes() == null ? new HashSet<>() : new HashSet<>(key.getScopes());
            if (current.isEmpty()) {
                // Chave legada sem escopos gravados: ganha tudo o que o papel atual permite.
                current.addAll(allowed);
            }
            current.retainAll(allowed);
            key.setScopes(current);
            if (!privileged && current.contains(ApiKeyScope.SQUAD_READ.name())
                    && (key.getSquadId() == null || key.getSquadId().isBlank())) {
                // SQUAD_READ sem restrição é privilégio de ADMIN/LEAD: trava na squad atual da pessoa.
                // Etapa 2 (app.membership.authz): em new a "squad atual" é a ativa validada contra os vínculos.
                String squad = squadAccessService != null ? squadAccessService.activeSquadFor("apikey", owner) : owner.getDefaultProjectId();
                key.setSquadId(squad);
                if ((squad == null || squad.isBlank()) && squadAccessService != null
                        && squadAccessService.authzMode() == com.agilespace.backend.service.AuthzMode.NEW) {
                    // Sem vínculo nenhum não há squad para travar: a chave perde o escopo em vez de ficar sem restrição.
                    current.remove(ApiKeyScope.SQUAD_READ.name());
                    key.setScopes(current);
                }
            }
        }
    }

    /** Cria uma chave nova igual à antiga (nome, escopos, squad) e revoga a antiga. A chave crua só existe no retorno. */
    public Rotated rotate(ApiKey old, Object rawDays, boolean callerIsAdmin) {
        if (old.getRevokedAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chave revogada não pode ser rotacionada.");
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiry = resolveExpiry(rawDays, callerIsAdmin, now);
        String rawKey = ApiKeyHashing.generateRawKey();
        ApiKey fresh = ApiKey.builder()
                .name(old.getName())
                .keyHash(ApiKeyHashing.sha256Hex(rawKey))
                .ownerUserId(old.getOwnerUserId())
                .ownerRole(old.getOwnerRole())
                .scopes(old.getScopes() == null ? new HashSet<>() : new HashSet<>(old.getScopes()))
                .squadId(old.getSquadId())
                .expiresAt(expiry)
                .build();
        reevaluateOwner(fresh);
        fresh = apiKeyRepository.save(fresh);
        old.setRevokedAt(now);
        apiKeyRepository.save(old);
        return new Rotated(fresh, rawKey);
    }

    public record Rotated(ApiKey key, String rawKey) {
    }
}
