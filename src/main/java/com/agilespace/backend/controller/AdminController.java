package com.agilespace.backend.controller;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.domain.GlobalAnnouncement;
import com.agilespace.backend.domain.PasswordResetRequest;
import com.agilespace.backend.dto.UnifiedSessionDto;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.AdminService;
import com.agilespace.backend.service.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private AdminService service;

    @Autowired
    private PasswordResetService passwordResetService;

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
    public ResponseEntity<List<GlobalAnnouncement>> getAnnouncements() {
        return ResponseEntity.ok(service.getAnnouncements());
    }

    @PostMapping("/announcements")
    public ResponseEntity<GlobalAnnouncement> createAnnouncement(@RequestBody GlobalAnnouncement announcement) {
        return ResponseEntity.ok(service.createAnnouncement(announcement));
    }

    @DeleteMapping("/announcements/{id}")
    public ResponseEntity<Void> deleteAnnouncement(@PathVariable String id) {
        service.deleteAnnouncement(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audit-logs")
    public ResponseEntity<List<AuditLog>> getAuditLogs() {
        return ResponseEntity.ok(service.getAuditLogs());
    }

    @PostMapping("/audit-logs")
    public ResponseEntity<AuditLog> logAction(@RequestParam String action,
                                              @RequestParam String performedBy,
                                              @RequestBody(required = false) String details) {
        return ResponseEntity.ok(service.logAction(action, performedBy, details));
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
}
