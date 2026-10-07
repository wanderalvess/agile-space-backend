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
        boolean hasValidAdminKey = adminKey != null && !adminKey.isBlank() && adminKey.equals(adminKeyHeader);

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
        User saved = service.saveUser(user, isAdmin);
        if (saved == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{uid}/squads")
    public ResponseEntity<List<SquadMember>> getSquadsForUser(@PathVariable String uid) {
        List<SquadMember> squads = service.getSquadsForUser(uid);
        return ResponseEntity.ok(squads);
    }

    /** Retorna a config Jira do usuário — exige que o chamador autenticado seja o dono do recurso ou ADMIN. */
    @GetMapping("/{userId}/jira-config")
    public ResponseEntity<?> getJiraConfig(@PathVariable String userId, HttpServletRequest request) {
        if (!isOwnerOrAdmin(userId, request)) {
            return forbidden("Acesso negado: você só pode consultar sua própria configuração Jira.");
        }
        UserJiraConfig config = service.getJiraConfig(userId);
        if (config == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(config);
    }

    /** Salva a config Jira — exige que o chamador autenticado seja o dono do recurso ou ADMIN. */
    @PostMapping("/{userId}/jira-config")
    public ResponseEntity<?> saveJiraConfig(
            @PathVariable String userId,
            @RequestBody UserJiraConfig config,
            HttpServletRequest request) {
        if (!isOwnerOrAdmin(userId, request)) {
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

    /** Retorna a config TDN do usuário — exige que o chamador autenticado seja o dono do recurso ou ADMIN. */
    @GetMapping("/{userId}/tdn-config")
    public ResponseEntity<?> getTdnConfig(@PathVariable String userId, HttpServletRequest request) {
        if (!isOwnerOrAdmin(userId, request)) {
            return forbidden("Acesso negado: você só pode consultar sua própria configuração TDN.");
        }
        UserTdnConfig config = service.getTdnConfig(userId);
        if (config == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(config);
    }

    /** Salva a config TDN — exige que o chamador autenticado seja o dono do recurso ou ADMIN. */
    @PostMapping("/{userId}/tdn-config")
    public ResponseEntity<?> saveTdnConfig(
            @PathVariable String userId,
            @RequestBody UserTdnConfig config,
            HttpServletRequest request) {
        if (!isOwnerOrAdmin(userId, request)) {
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
