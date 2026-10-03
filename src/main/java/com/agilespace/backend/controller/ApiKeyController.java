package com.agilespace.backend.controller;

import com.agilespace.backend.domain.ApiKey;
import com.agilespace.backend.domain.ApiKeyScope;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.ApiKeyRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.ApiKeyHashing;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Self-service de API key: qualquer usuário autenticado por JWT cria/lista/
 * revoga só as próprias chaves — fora de /api/admin, então não passa pelo
 * gate de ADMIN/LEAD do JwtAuthenticationFilter (qualquer papel autenticado
 * chega aqui). Escopo oferecido na criação é limitado pelo papel do chamador
 * (ver allowedScopesFor) — MEMBER só consegue os dois escopos de leitura menos
 * sensíveis, e squad:read é sempre travado na própria squad, nunca escolhível.
 *
 * /api/admin/api-keys (ApiKeyAdminController) continua existindo, pra visão
 * global de ADMIN/LEAD sobre as chaves de todo mundo — os dois controllers
 * coexistem, este aqui não substitui aquele.
 */
@RestController
@RequestMapping("/api/api-keys")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyRepository apiKeyRepository;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<List<ApiKey>> listMine(HttpServletRequest request) {
        String ownerUserId = callerId(request);
        return ResponseEntity.ok(apiKeyRepository.findAllByOwnerUserIdOrderByCreatedAtDesc(ownerUserId));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String ownerUserId = callerId(request);
        String role = callerRole(request);
        boolean privileged = isPrivileged(role);

        String name = body.get("name") instanceof String s ? s.trim() : "";
        if (name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Campo obrigatório: name."));
        }

        Set<String> requestedScopes = extractScopes(body.get("scopes"));
        if (requestedScopes.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Informe ao menos um escopo (scopes)."));
        }
        for (String scope : requestedScopes) {
            if (!ApiKeyScope.isValid(scope)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Escopo inválido: " + scope));
            }
        }
        Set<String> normalizedScopes = requestedScopes.stream().map(String::toUpperCase).collect(Collectors.toSet());

        Set<String> allowed = allowedScopesFor(privileged);
        if (!allowed.containsAll(normalizedScopes)) {
            Set<String> denied = new HashSet<>(normalizedScopes);
            denied.removeAll(allowed);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "Escopo não permitido pro seu papel: " + String.join(", ", denied)));
        }

        String squadId;
        if (privileged) {
            // ADMIN/LEAD escolhe livremente (null = sem restrição, igual toda chave deles hoje).
            Object squadIdRaw = body.get("squadId");
            squadId = squadIdRaw instanceof String s && !s.isBlank() ? s.trim() : null;
        } else if (normalizedScopes.contains(ApiKeyScope.SQUAD_READ.name())) {
            // Não-privilegiado nunca escolhe squad — é sempre a própria, e precisa já ter uma
            // (senão squadId ficaria null, que pro enforcement significa SEM restrição —
            // o oposto do que uma chave de MEMBER devia ganhar).
            User caller = userRepository.findById(ownerUserId).orElse(null);
            if (caller == null || caller.getSquadId() == null || caller.getSquadId().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Vincule-se a uma squad antes de gerar uma chave com escopo SQUAD_READ."));
            }
            squadId = caller.getSquadId();
        } else {
            squadId = null;
        }

        String rawKey = ApiKeyHashing.generateRawKey();
        ApiKey saved = apiKeyRepository.save(ApiKey.builder()
                .name(name)
                .keyHash(ApiKeyHashing.sha256Hex(rawKey))
                .ownerUserId(ownerUserId)
                .ownerRole(role)
                .scopes(normalizedScopes)
                .squadId(squadId)
                .build());

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", saved.getId(),
                "name", saved.getName(),
                "rawKey", rawKey,
                "scopes", saved.getScopes(),
                "squadId", saved.getSquadId() != null ? saved.getSquadId() : "",
                "createdAt", saved.getCreatedAt()
        ));
    }

    @PostMapping("/{id}/revoke")
    public ResponseEntity<Void> revoke(@PathVariable("id") UUID id, HttpServletRequest request) {
        String ownerUserId = callerId(request);
        ApiKey key = apiKeyRepository.findById(id).orElse(null);
        if (key == null) {
            return ResponseEntity.notFound().build();
        }
        if (!Objects.equals(ownerUserId, key.getOwnerUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Você só pode revogar as próprias chaves.");
        }
        key.setRevokedAt(LocalDateTime.now());
        apiKeyRepository.save(key);
        return ResponseEntity.ok().build();
    }

    private static String callerId(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
    }

    private static String callerRole(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
    }

    private static boolean isPrivileged(String role) {
        return "ADMIN".equalsIgnoreCase(role) || "LEAD".equalsIgnoreCase(role);
    }

    /** MEMBER só pode auto-emitir os escopos de leitura menos sensíveis. */
    private static Set<String> allowedScopesFor(boolean privileged) {
        if (privileged) {
            return Arrays.stream(ApiKeyScope.values()).map(Enum::name).collect(Collectors.toSet());
        }
        return Set.of(ApiKeyScope.KNOWLEDGE_READ.name(), ApiKeyScope.SQUAD_READ.name());
    }

    private static Set<String> extractScopes(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(Object::toString).map(String::trim)
                    .filter(s -> !s.isBlank()).collect(Collectors.toSet());
        }
        if (raw instanceof String s) {
            return Arrays.stream(s.split(",")).map(String::trim).filter(t -> !t.isBlank()).collect(Collectors.toSet());
        }
        return Set.of();
    }
}
