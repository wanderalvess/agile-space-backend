package com.agilespace.backend.controller;

import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.domain.UserJiraConfig;
import com.agilespace.backend.domain.UserTdnConfig;
import com.agilespace.backend.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserService service;

    /** Chave de admin para endpoints internos (listagem de todos os usuários).
     *  Definida via APP_ADMIN_KEY no ambiente. Sem ela, GET /users retorna 403. */
    @Value("${app.security.admin-key:}")
    private String adminKey;

    // ---------- Helpers ----------

    /**
     * Valida que o chamador é o dono do recurso: o header X-Caller-Id deve estar
     * presente e ser igual ao userId do path.  Evita que um usuário autenticado
     * acesse/modifique dados de outro simplesmente trocando o path parameter.
     */
    private boolean isOwner(String userId, String callerIdHeader) {
        if (callerIdHeader == null || callerIdHeader.isBlank()) return false;
        return callerIdHeader.trim().equals(userId);
    }

    private ResponseEntity<?> forbidden(String message) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("{\"error\": \"" + message + "\"}");
    }

    // ---------- Endpoints ----------

    /** Lista todos os usuários — restrito: exige header X-Admin-Key correto. */
    @GetMapping
    public ResponseEntity<?> getAllUsers(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKeyHeader) {
        if (adminKey == null || adminKey.isBlank() || !adminKey.equals(adminKeyHeader)) {
            return forbidden("Acesso restrito. Header X-Admin-Key ausente ou inválido.");
        }
        return ResponseEntity.ok(service.getAllUsers());
    }

    @GetMapping("/{id}")
    public ResponseEntity<User> getUser(@PathVariable String id) {
        User user = service.getUser(id);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(user);
    }

    @PostMapping
    public ResponseEntity<User> saveUser(@RequestBody User user) {
        User saved = service.saveUser(user);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{uid}/squads")
    public ResponseEntity<List<SquadMember>> getSquadsForUser(@PathVariable String uid) {
        List<SquadMember> squads = service.getSquadsForUser(uid);
        return ResponseEntity.ok(squads);
    }

    /** Retorna a config Jira do usuário — exige que X-Caller-Id == userId (dono do recurso). */
    @GetMapping("/{userId}/jira-config")
    public ResponseEntity<?> getJiraConfig(
            @PathVariable String userId,
            @RequestHeader(value = "X-Caller-Id", required = false) String callerIdHeader) {
        if (!isOwner(userId, callerIdHeader)) {
            return forbidden("Acesso negado: você só pode consultar sua própria configuração Jira.");
        }
        UserJiraConfig config = service.getJiraConfig(userId);
        if (config == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(config);
    }

    /** Salva a config Jira — exige que X-Caller-Id == userId (dono do recurso). */
    @PostMapping("/{userId}/jira-config")
    public ResponseEntity<?> saveJiraConfig(
            @PathVariable String userId,
            @RequestBody UserJiraConfig config,
            @RequestHeader(value = "X-Caller-Id", required = false) String callerIdHeader) {
        if (!isOwner(userId, callerIdHeader)) {
            return forbidden("Acesso negado: você só pode salvar sua própria configuração Jira.");
        }
        config.setUserId(userId);
        UserJiraConfig saved = service.saveJiraConfig(config);
        return ResponseEntity.ok(saved);
    }

    /** Exclui a config Jira — exige que X-Caller-Id == userId (dono do recurso). */
    @DeleteMapping("/{userId}/jira-config")
    public ResponseEntity<?> deleteJiraConfig(
            @PathVariable String userId,
            @RequestHeader(value = "X-Caller-Id", required = false) String callerIdHeader) {
        if (!isOwner(userId, callerIdHeader)) {
            return forbidden("Acesso negado: você só pode remover sua própria configuração Jira.");
        }
        service.deleteJiraConfig(userId);
        return ResponseEntity.noContent().build();
    }

    /** Retorna a config TDN do usuário — exige que X-Caller-Id == userId (dono do recurso). */
    @GetMapping("/{userId}/tdn-config")
    public ResponseEntity<?> getTdnConfig(
            @PathVariable String userId,
            @RequestHeader(value = "X-Caller-Id", required = false) String callerIdHeader) {
        if (!isOwner(userId, callerIdHeader)) {
            return forbidden("Acesso negado: você só pode consultar sua própria configuração TDN.");
        }
        UserTdnConfig config = service.getTdnConfig(userId);
        if (config == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(config);
    }

    /** Salva a config TDN — exige que X-Caller-Id == userId (dono do recurso). */
    @PostMapping("/{userId}/tdn-config")
    public ResponseEntity<?> saveTdnConfig(
            @PathVariable String userId,
            @RequestBody UserTdnConfig config,
            @RequestHeader(value = "X-Caller-Id", required = false) String callerIdHeader) {
        if (!isOwner(userId, callerIdHeader)) {
            return forbidden("Acesso negado: você só pode salvar sua própria configuração TDN.");
        }
        config.setUserId(userId);
        UserTdnConfig saved = service.saveTdnConfig(config);
        return ResponseEntity.ok(saved);
    }

    /** Exclui a config TDN — exige que X-Caller-Id == userId (dono do recurso). */
    @DeleteMapping("/{userId}/tdn-config")
    public ResponseEntity<?> deleteTdnConfig(
            @PathVariable String userId,
            @RequestHeader(value = "X-Caller-Id", required = false) String callerIdHeader) {
        if (!isOwner(userId, callerIdHeader)) {
            return forbidden("Acesso negado: você só pode remover sua própria configuração TDN.");
        }
        service.deleteTdnConfig(userId);
        return ResponseEntity.noContent().build();
    }
}

