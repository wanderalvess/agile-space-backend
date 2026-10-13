package com.agilespace.backend.controller;

import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.domain.UserJiraConfig;
import com.agilespace.backend.domain.UserTdnConfig;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

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
     * Valida que o chamador é o dono do recurso ou ADMIN, usando o userId extraído do JWT
     * pelo JwtAuthenticationFilter (nunca um header enviado pelo cliente: X-Caller-Id era
     * apenas um valor arbitrário do request, não validado contra o token, e permitia a
     * qualquer um se passar por outro usuário só trocando o header).
     */
    private boolean isOwnerOrAdmin(String resourceUserId, HttpServletRequest request) {
        String authUserId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        String authRole = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
        if ("ADMIN".equalsIgnoreCase(authRole)) {
            return true;
        }
        return authUserId != null && authUserId.equals(resourceUserId);
    }

    /** Dono do recurso (admin NÃO conta): quem pode ler/gravar o token em claro. */
    private boolean isOwner(String resourceUserId, HttpServletRequest request) {
        String authUserId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        return authUserId != null && authUserId.equals(resourceUserId);
    }

    private ResponseEntity<?> forbidden(String message) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("{\"error\": \"" + message + "\"}");
    }

    // ---------- Endpoints ----------

    /** Lista todos os usuários — permite administradores autenticados ou requisições com X-Admin-Key válido. */
    @GetMapping
    public ResponseEntity<?> getAllUsers(
            HttpServletRequest request,
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKeyHeader) {
        String authRole = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
        boolean isAdmin = "ADMIN".equalsIgnoreCase(authRole);
        boolean hasValidAdminKey = adminKey != null && !adminKey.isBlank() && adminKeyHeader != null
                && MessageDigest.isEqual(adminKey.getBytes(StandardCharsets.UTF_8), adminKeyHeader.getBytes(StandardCharsets.UTF_8));

        if (!isAdmin && !hasValidAdminKey) {
            return forbidden("Acesso restrito a administradores.");
        }
        return ResponseEntity.ok(service.getAllUsers());
    }

    /** Retorna um usuário — o chamador só pode consultar o próprio perfil, a menos que seja ADMIN. */
    @GetMapping("/{id}")
    public ResponseEntity<?> getUser(@PathVariable String id, HttpServletRequest request) {
        if (!isOwnerOrAdmin(id, request)) {
            return forbidden("Você só pode consultar o seu próprio perfil.");
        }
        User user = service.getUser(id);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(user);
    }

    /** Cria/atualiza um perfil — o chamador só pode gravar o próprio id, a menos que seja ADMIN
     *  (usado pelo painel /admin para editar outros usuários), impedindo que um usuário comum
     *  sobrescreva o perfil de outro trocando o id no corpo da requisição. */
    @PostMapping
    public ResponseEntity<?> saveUser(@RequestBody User user, HttpServletRequest request) {
        String authUserId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        String authRole = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
        if (authUserId == null) {
            return forbidden("Sessão inválida.");
        }
        boolean isAdmin = "ADMIN".equalsIgnoreCase(authRole);
        if (!isAdmin && user.getId() != null && !user.getId().isBlank() && !user.getId().equals(authUserId)) {
            return forbidden("Você só pode salvar o seu próprio perfil.");
        }
        if (user.getId() == null || user.getId().isBlank()) {
            user.setId(authUserId);
        }
        User saved = service.saveUser(user, isAdmin, authUserId);
        if (saved == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(saved);
    }

    /** Vínculos de squad de uma conta — só a própria pessoa ou ADMIN (antes qualquer login consultava qualquer id). */
    @GetMapping("/{uid}/squads")
    public ResponseEntity<?> getSquadsForUser(@PathVariable String uid, HttpServletRequest request) {
        if (!isOwnerOrAdmin(uid, request)) {
            return forbidden("Você só pode consultar os seus próprios vínculos de squad.");
        }
        List<SquadMember> squads = service.getSquadsForUser(uid);
        return ResponseEntity.ok(squads);
    }

    /**
     * Config Jira. O dono recebe o necessário para a tela dele (inclui o token: o navegador chama o Jira com ele).
     * ADMIN lendo OUTRA pessoa recebe só {configured, domain}: o token de terceiros nunca sai do servidor.
     */
    @GetMapping("/{userId}/jira-config")
    public ResponseEntity<?> getJiraConfig(@PathVariable String userId, HttpServletRequest request) {
        if (!isOwnerOrAdmin(userId, request)) {
            return forbidden("Acesso negado: você só pode consultar sua própria configuração Jira.");
        }
        UserJiraConfig config = service.getJiraConfig(userId);
        if (config == null) {
            return ResponseEntity.notFound().build();
        }
        if (!isOwner(userId, request)) {
            return ResponseEntity.ok(Map.of("configured", true, "domain", config.getDomain() == null ? "" : config.getDomain()));
        }
        return ResponseEntity.ok(config);
    }

    /** Salva a config Jira — só o dono (o admin não grava token em nome de outra pessoa). */
    @PostMapping("/{userId}/jira-config")
    public ResponseEntity<?> saveJiraConfig(
            @PathVariable String userId,
            @RequestBody UserJiraConfig config,
            HttpServletRequest request) {
        if (!isOwner(userId, request)) {
            return forbidden("Acesso negado: você só pode salvar sua própria configuração Jira.");
        }
        config.setUserId(userId);
        UserJiraConfig saved = service.saveJiraConfig(config);
        return ResponseEntity.ok(saved);
    }

    /** Exclui a config Jira — exige que o chamador autenticado seja o dono do recurso ou ADMIN. */
    @DeleteMapping("/{userId}/jira-config")
    public ResponseEntity<?> deleteJiraConfig(@PathVariable String userId, HttpServletRequest request) {
        if (!isOwnerOrAdmin(userId, request)) {
            return forbidden("Acesso negado: você só pode remover sua própria configuração Jira.");
        }
        service.deleteJiraConfig(userId);
        return ResponseEntity.noContent().build();
    }

    /** Config TDN: dono recebe tudo; ADMIN lendo OUTRA pessoa recebe só {configured, baseUrl, space, label}, sem o token. */
    @GetMapping("/{userId}/tdn-config")
    public ResponseEntity<?> getTdnConfig(@PathVariable String userId, HttpServletRequest request) {
        if (!isOwnerOrAdmin(userId, request)) {
            return forbidden("Acesso negado: você só pode consultar sua própria configuração TDN.");
        }
        UserTdnConfig config = service.getTdnConfig(userId);
        if (config == null) {
            return ResponseEntity.notFound().build();
        }
        if (!isOwner(userId, request)) {
            Map<String, Object> masked = new java.util.LinkedHashMap<>();
            masked.put("configured", true);
            masked.put("baseUrl", config.getBaseUrl() == null ? "" : config.getBaseUrl());
            masked.put("space", config.getSpace() == null ? "" : config.getSpace());
            masked.put("label", config.getLabel() == null ? "" : config.getLabel());
            return ResponseEntity.ok(masked);
        }
        return ResponseEntity.ok(config);
    }

    /** Salva a config TDN — só o dono. */
    @PostMapping("/{userId}/tdn-config")
    public ResponseEntity<?> saveTdnConfig(
            @PathVariable String userId,
            @RequestBody UserTdnConfig config,
            HttpServletRequest request) {
        if (!isOwner(userId, request)) {
            return forbidden("Acesso negado: você só pode salvar sua própria configuração TDN.");
        }
        config.setUserId(userId);
        UserTdnConfig saved = service.saveTdnConfig(config);
        return ResponseEntity.ok(saved);
    }

    /** Exclui a config TDN — exige que o chamador autenticado seja o dono do recurso ou ADMIN. */
    @DeleteMapping("/{userId}/tdn-config")
    public ResponseEntity<?> deleteTdnConfig(@PathVariable String userId, HttpServletRequest request) {
        if (!isOwnerOrAdmin(userId, request)) {
            return forbidden("Acesso negado: você só pode remover sua própria configuração TDN.");
        }
        service.deleteTdnConfig(userId);
        return ResponseEntity.noContent().build();
    }
}
