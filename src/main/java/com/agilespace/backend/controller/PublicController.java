package com.agilespace.backend.controller;

import com.agilespace.backend.dto.AnnouncementDto;
import com.agilespace.backend.service.AdminService;
import com.agilespace.backend.service.AnnouncementService;
import com.agilespace.backend.service.InviteService;
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
public class PublicController {

    /**
     * Só o que o front de fato lê antes do login. {@code logoUrl}, {@code allowAnonymous} e {@code maintenanceMode} saíram
     * (nenhum código do backend os lê; valores já gravados em {@code system_configs} ficam no banco, sem efeito).
     */
    private static final List<String> SYSTEM_CONFIG_KEYS = List.of("companyName", "primaryColor");

    private final AdminService adminService;
    private final AnnouncementService announcementService;
    private final InviteService inviteService;

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

    /** Antes do login: só avisos para todos (audienceType=ALL) e vigentes. */
    @GetMapping("/announcements")
    public ResponseEntity<List<AnnouncementDto>> getAnnouncements() {
        return ResponseEntity.ok(announcementService.publicActive());
    }

    /**
     * Página do convite para quem ainda não entrou: squad, papel, quem convidou e validade (sem e-mail inteiro).
     * Token desconhecido responde 404.
     */
    @GetMapping("/invites/{token}")
    public ResponseEntity<Map<String, Object>> getInvitePreview(@PathVariable String token) {
        return ResponseEntity.ok(inviteService.getPublicPreview(token));
    }
}
