package com.agilespace.backend.controller;

import com.agilespace.backend.domain.GlobalAnnouncement;
import com.agilespace.backend.domain.PasswordResetRequest;
import com.agilespace.backend.dto.ForgotPasswordRequestDto;
import com.agilespace.backend.service.AdminService;
import com.agilespace.backend.service.PasswordResetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoints lidos antes do login (branding, anúncio global) — isentos de JWT
 * em JwtAuthenticationFilter.PUBLIC_PATHS, ao contrário de /api/admin/** que
 * exige role=ADMIN. Somente leitura; escrita continua só pelo admin.
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class PublicController {

    private static final List<String> SYSTEM_CONFIG_KEYS = List.of(
            "companyName", "primaryColor", "logoUrl", "allowAnonymous", "maintenanceMode"
    );

    private final AdminService adminService;
    private final PasswordResetService passwordResetService;

    @GetMapping("/system-config")
    public ResponseEntity<Map<String, String>> getSystemConfig() {
        Map<String, String> config = new LinkedHashMap<>();
        for (String key : SYSTEM_CONFIG_KEYS) {
            String value = adminService.getConfig(key);
            if (value != null) {
                config.put(key, value);
            }
        }
        return ResponseEntity.ok(config);
    }

    @GetMapping("/announcements")
    public ResponseEntity<List<GlobalAnnouncement>> getAnnouncements() {
        return ResponseEntity.ok(adminService.getAnnouncements());
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<PasswordResetRequest> forgotPassword(@Valid @RequestBody ForgotPasswordRequestDto request) {
        return ResponseEntity.ok(passwordResetService.requestReset(request.getEmail()));
    }
}
