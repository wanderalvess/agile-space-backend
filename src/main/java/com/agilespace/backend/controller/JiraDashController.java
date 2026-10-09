package com.agilespace.backend.controller;

import com.agilespace.backend.domain.JiraDashSnapshot;
import com.agilespace.backend.dto.SaveJiraDashSnapshotRequest;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.JiraDashSnapshotService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// Cache compartilhado de dados do JiraDash. Leitura aberta a qualquer
// autenticado (não há conceito de squad real aqui — ver JiraDashSnapshot);
// escrita também aberta a qualquer autenticado, de propósito: se qualquer
// líder/agilista clicar "Atualizar", o resultado deve valer pra todo mundo.
@RestController
@RequestMapping("/api/jiradash")
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class JiraDashController {

    private final JiraDashSnapshotService service;

    @GetMapping("/snapshot")
    public ResponseEntity<JiraDashSnapshot> get(@RequestParam String jql) {
        if (jql == null || jql.isBlank() || jql.length() > SnapshotValidation.MAX_JQL_CHARS) {
            return ResponseEntity.badRequest().build();
        }
        return service.get(jql)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/snapshot")
    public ResponseEntity<JiraDashSnapshot> save(@RequestBody SaveJiraDashSnapshotRequest body, HttpServletRequest request) {
        String invalid = SnapshotValidation.check(body);
        if (invalid != null) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, invalid);
        }
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        String userName = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_EMAIL);
        return ResponseEntity.ok(service.save(body.getJql(), body.getPayload(), userId, userName));
    }

    /** Limites do cache compartilhado: qualquer autenticado grava, então o formato e o tamanho são conferidos. */
    static final class SnapshotValidation {
        static final int MAX_JQL_CHARS = 4000;
        static final int MAX_PAYLOAD_BYTES = 12 * 1024 * 1024;

        private SnapshotValidation() {}

        static String check(SaveJiraDashSnapshotRequest body) {
            if (body == null || body.getJql() == null || body.getJql().isBlank()) return "Informe a consulta JQL do snapshot.";
            if (body.getJql().length() > MAX_JQL_CHARS) return "A consulta JQL é grande demais.";
            var payload = body.getPayload();
            if (payload == null || !payload.isObject() || !payload.path("allIssues").isArray()) {
                return "Snapshot inválido: faltam as issues (allIssues).";
            }
            if (payload.toString().length() > MAX_PAYLOAD_BYTES) return "Snapshot grande demais para o cache compartilhado.";
            return null;
        }
    }
}
