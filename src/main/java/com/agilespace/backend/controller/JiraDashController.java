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
        return service.get(jql)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/snapshot")
    public ResponseEntity<JiraDashSnapshot> save(@RequestBody SaveJiraDashSnapshotRequest body, HttpServletRequest request) {
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        String userName = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_EMAIL);
        return ResponseEntity.ok(service.save(body.getJql(), body.getPayload(), userId, userName));
    }
}
