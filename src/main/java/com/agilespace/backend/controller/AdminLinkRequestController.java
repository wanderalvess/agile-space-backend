package com.agilespace.backend.controller;

import com.agilespace.backend.dto.AdminLinkRequestDto;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.LinkRequestService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Fila de pedidos de vínculo para o admin (docs/link-requests-api.md). O role=ADMIN de /api/admin/** é exigido pelo
 * JwtAuthenticationFilter; quem decidiu vem sempre do token, nunca do corpo.
 */
@RestController
@RequestMapping("/api/admin/link-requests")
@RequiredArgsConstructor
public class AdminLinkRequestController {

    /** Corpo opcional de approve: a linha do roster que o admin confirma (sobrescreve a do pedido). */
    public record ApproveBody(String memberId) { }

    /** Corpo opcional de reject. */
    public record RejectBody(String reason) { }

    private final LinkRequestService linkRequestService;

    @GetMapping
    public ResponseEntity<List<AdminLinkRequestDto>> list(@RequestParam(required = false) String status) {
        return ResponseEntity.ok(linkRequestService.adminList(status));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<AdminLinkRequestDto> approve(@PathVariable String id,
                                                       @RequestBody(required = false) ApproveBody body,
                                                       HttpServletRequest request) {
        return ResponseEntity.ok(linkRequestService.approve(id, body == null ? null : body.memberId(),
                adminId(request), adminLabel(request)));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<AdminLinkRequestDto> reject(@PathVariable String id,
                                                      @RequestBody(required = false) RejectBody body,
                                                      HttpServletRequest request) {
        return ResponseEntity.ok(linkRequestService.reject(id, body == null ? null : body.reason(),
                adminId(request), adminLabel(request)));
    }

    private static String adminId(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
    }

    /** E-mail do token (ou userId, sem e-mail), como o resto do painel admin. */
    private static String adminLabel(HttpServletRequest request) {
        String email = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_EMAIL);
        if (email != null && !email.isBlank()) return email;
        String id = adminId(request);
        return id != null ? id : "ADMIN";
    }
}
