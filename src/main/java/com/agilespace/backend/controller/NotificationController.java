package com.agilespace.backend.controller;

import com.agilespace.backend.dto.NotificationDto;
import com.agilespace.backend.dto.UnreadCountDto;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Notificações do próprio usuário (docs/home-api.md §2). */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<List<NotificationDto>> list(@RequestParam(required = false) String category,
                                                      @RequestParam(required = false) Integer limit,
                                                      HttpServletRequest request) {
        return ResponseEntity.ok(notificationService.list(userId(request), category, limit));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<UnreadCountDto> unreadCount(HttpServletRequest request) {
        return ResponseEntity.ok(notificationService.unreadCount(userId(request)));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(@PathVariable String id, HttpServletRequest request) {
        notificationService.markRead(userId(request), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> readAll(@RequestParam(required = false) String category, HttpServletRequest request) {
        notificationService.markAllRead(userId(request), category);
        return ResponseEntity.noContent().build();
    }

    private static String userId(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
    }
}
