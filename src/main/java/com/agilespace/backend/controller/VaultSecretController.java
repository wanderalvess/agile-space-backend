package com.agilespace.backend.controller;

import com.agilespace.backend.domain.VaultSecret;
import com.agilespace.backend.service.VaultSecretService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/vault-secrets")
public class VaultSecretController {

    @Autowired
    private VaultSecretService service;

    @PostMapping
    public ResponseEntity<VaultSecret> createSecret(@RequestBody VaultSecret secret) {
        VaultSecret saved = service.save(secret);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{id}")
    public ResponseEntity<VaultSecret> getSecret(@PathVariable String id) {
        VaultSecret secret = service.getAndProcessExpiration(id);
        if (secret == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(secret);
    }
}
