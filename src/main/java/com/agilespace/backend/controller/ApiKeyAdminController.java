package com.agilespace.backend.controller;

import com.agilespace.backend.domain.ApiKey;
import com.agilespace.backend.repository.ApiKeyRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD de API keys da Base de Conhecimento — /api/admin/** já exige
 * role=ADMIN/LEAD via JwtAuthenticationFilter, nenhum gate extra necessário
 * aqui. A chave crua só existe na resposta do POST (uma vez); só o hash
 * SHA-256 é persistido (ver ApiKeyAuthenticationFilter, que valida do mesmo jeito).
 */
@RestController
@RequestMapping("/api/admin/api-keys")
@RequiredArgsConstructor
public class ApiKeyAdminController {

    private static final SecureRandom RANDOM = new SecureRandom();

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

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String rawKey = "ask_" + HexFormat.of().formatHex(bytes);
        String keyHash = sha256Hex(rawKey);

        ApiKey saved = apiKeyRepository.save(ApiKey.builder()
                .name(name.trim())
                .keyHash(keyHash)
                .ownerUserId((String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID))
                .build());

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", saved.getId(),
                "name", saved.getName(),
                "rawKey", rawKey,
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

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
