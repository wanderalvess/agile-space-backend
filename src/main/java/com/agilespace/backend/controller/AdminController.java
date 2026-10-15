package com.agilespace.backend.controller;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.domain.PasswordResetRequest;
import com.agilespace.backend.dto.AnnouncementDto;
import com.agilespace.backend.dto.AnnouncementRequest;
import com.agilespace.backend.dto.UnifiedSessionDto;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.AdminService;
import com.agilespace.backend.service.AnnouncementService;
import com.agilespace.backend.service.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private AdminService service;

    @Autowired
    private PasswordResetService passwordResetService;

    @Autowired
    private AnnouncementService announcementService;

    @Autowired
    private UserRepository userRepository;

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(service.getSystemStats());
    }

    @GetMapping("/configs/{key}")
    public ResponseEntity<String> getConfig(@PathVariable String key) {
        String value = service.getConfig(key);
        if (value == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(value);
    }

    @PostMapping("/configs/{key}")
    public ResponseEntity<Void> setConfig(@PathVariable String key, @RequestBody String value) {
        service.setConfig(key, value);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/announcements")
    public ResponseEntity<List<AnnouncementDto>> getAnnouncements() {
        return ResponseEntity.ok(announcementService.adminList());
    }

    @PostMapping("/announcements")
    public ResponseEntity<AnnouncementDto> createAnnouncement(@RequestBody AnnouncementRequest announcement,
                                                              HttpServletRequest request) {
        // Autoria vem do token, nunca do corpo.
        return ResponseEntity.ok(announcementService.create(announcement, author(request),
                (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)));
    }

    @PutMapping("/announcements/{id}")
    public ResponseEntity<AnnouncementDto> updateAnnouncement(@PathVariable String id, @RequestBody AnnouncementRequest announcement,
                                                              HttpServletRequest request) {
        return ResponseEntity.ok(announcementService.update(id, announcement,
                (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)));
    }

    @DeleteMapping("/announcements/{id}")
    public ResponseEntity<Void> deleteAnnouncement(@PathVariable String id) {
        announcementService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** E-mail do token (ou userId, sem e-mail): quem publicou, nunca o que veio no corpo. */
    private static String author(HttpServletRequest request) {
        String email = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_EMAIL);
        String author = email != null && !email.isBlank() ? email : (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        return author != null ? author : "ADMIN";
    }

    @GetMapping("/audit-logs")
    public ResponseEntity<List<AuditLog>> getAuditLogs() {
        return ResponseEntity.ok(service.getAuditLogs());
    }

    @PostMapping("/audit-logs")
    public ResponseEntity<AuditLog> logAction(@RequestParam String action,
                                              @RequestParam(required = false) String performedBy,
                                              @RequestBody(required = false) String details,
                                              HttpServletRequest request) {
        // Autoria vem do token: aceitar o nome do corpo/query permitia forjar quem fez a ação no registro de auditoria.
        String email = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_EMAIL);
        String author = email != null && !email.isBlank() ? email : (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        return ResponseEntity.ok(service.logAction(action, author != null ? author : "ADMIN", details));
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<UnifiedSessionDto>> getSessions() {
        return ResponseEntity.ok(service.getSessions());
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> deleteSession(@PathVariable String id, @RequestParam(required = false) String type) {
        service.deleteSession(id, type);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/password-resets")
    public ResponseEntity<List<PasswordResetRequest>> getPasswordResets() {
        return ResponseEntity.ok(passwordResetService.getAllRequests());
    }

    @PostMapping("/password-resets/{id}/approve")
    public ResponseEntity<PasswordResetRequest> approvePasswordReset(
            @PathVariable String id,
            HttpServletRequest request) {
        String approvedBy = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_EMAIL);
        return ResponseEntity.ok(passwordResetService.approveReset(id, approvedBy));
    }

    /**
     * Redefinição de senha iniciada pelo admin, para a pessoa que não consegue (ou não quer) usar o
     * "Esqueci a senha": gera e aprova o pedido de uma vez e devolve o link de redefinição (resetToken, uma vez só),
     * que o admin repassa fora do sistema. Mesmo caminho do fluxo normal: auditoria, link vale 2 h e um uso, e a
     * senha atual só muda quando a pessoa define a nova pelo link.
     */
    @PostMapping("/users/{id}/reset-password")
    public ResponseEntity<PasswordResetRequest> resetUserPassword(@PathVariable String id, HttpServletRequest request) {
        User target = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado"));
        String approvedBy = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_EMAIL);
        PasswordResetRequest pending = passwordResetService.requestReset(target.getEmail());
        return ResponseEntity.ok(passwordResetService.approveReset(pending.getId(), approvedBy));
    }
}
