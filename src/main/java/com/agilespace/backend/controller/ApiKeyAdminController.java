package com.agilespace.backend.controller;

import com.agilespace.backend.domain.ApiKey;
import com.agilespace.backend.domain.ApiKeyScope;
import com.agilespace.backend.repository.ApiKeyRepository;
import com.agilespace.backend.security.ApiKeyHashing;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CRUD de API keys pra ADMIN/LEAD — /api/admin/** já exige esse papel via
 * JwtAuthenticationFilter, nenhum gate extra necessário aqui. Visão global:
 * lista/revoga as chaves de todo mundo, não só as próprias (diferente de
 * ApiKeyController, o self-service em /api/api-keys). A chave crua só existe
 * na resposta do POST (uma vez); só o hash SHA-256 é persistido (ver
 * ApiKeyAuthenticationFilter, que valida do mesmo jeito).
 *
 * Chave criada por aqui sempre ganha todos os escopos e nenhuma squad (mesmo
 * acesso total de sempre) — mas agora de forma explícita (ownerRole + scopes),
 * não mais via ownerRole null. Chave com ownerRole null significa hoje "criada
 * antes deste campo existir" (ver ApiKey.hasFullAccessGrandfathered) — uma
 * chave nova cair nesse mesmo bucket por omissão era o gap real, não a semântica
 * pretendida.
 */
@RestController
@RequestMapping("/api/admin/api-keys")
@RequiredArgsConstructor
public class ApiKeyAdminController {

    private final ApiKeyRepository apiKeyRepository;

    @GetMapping
    public ResponseEntity<List<ApiKey>> listKeys() {
        return ResponseEntity.ok(apiKeyRepository.findAllByOrderByCreatedAtDesc());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createKey(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        String rawKey = ApiKeyHashing.generateRawKey();

        ApiKey saved = apiKeyRepository.save(ApiKey.builder()
                .name(name.trim())
                .keyHash(ApiKeyHashing.sha256Hex(rawKey))
                .ownerUserId((String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID))
                .ownerRole((String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE))
                .scopes(Arrays.stream(ApiKeyScope.values()).map(Enum::name).collect(Collectors.toSet()))
                .build());

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", saved.getId(),
                "name", saved.getName(),
                "rawKey", rawKey,
                "scopes", saved.getScopes(),
                "createdAt", saved.getCreatedAt()
        ));
    }

    @PostMapping("/{id}/revoke")
    public ResponseEntity<Void> revokeKey(@PathVariable("id") UUID id) {
        return apiKeyRepository.findById(id).map(key -> {
            key.setRevokedAt(LocalDateTime.now());
            apiKeyRepository.save(key);
            return ResponseEntity.ok().<Void>build();
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
