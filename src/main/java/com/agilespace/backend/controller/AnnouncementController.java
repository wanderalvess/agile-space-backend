package com.agilespace.backend.controller;

import com.agilespace.backend.dto.AnnouncementDto;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.AnnouncementService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Avisos do usuário logado (docs/home-api.md §1). Admin fica em /api/admin/announcements. */
@RestController
@RequestMapping("/api/announcements")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;

    @GetMapping("/active")
    public ResponseEntity<List<AnnouncementDto>> active(HttpServletRequest request) {
        return ResponseEntity.ok(announcementService.active(userId(request), role(request)));
    }

    @GetMapping("/history")
    public ResponseEntity<List<AnnouncementDto>> history(@RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return ResponseEntity.ok(announcementService.history(userId(request), role(request), limit));
    }

    @PostMapping("/{id}/dismiss")
    public ResponseEntity<Void> dismiss(@PathVariable String id, HttpServletRequest request) {
        announcementService.dismiss(userId(request), role(request), id);
        return ResponseEntity.noContent().build();
    }

    private static String userId(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
    }

    private static String role(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
    }
}
