package com.agilespace.backend.controller;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.LinkRequestCreateRequest;
import com.agilespace.backend.dto.LinkRequestCreatedDto;
import com.agilespace.backend.dto.LinkRequestDto;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.LinkRequestService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Pedido de vínculo do próprio usuário (docs/link-requests-api.md). Exige sessão; o solicitante vem do token. */
@RestController
@RequestMapping("/api/link-requests")
@RequiredArgsConstructor
public class LinkRequestController {

    private final LinkRequestService linkRequestService;
    private final UserRepository userRepository;

    @PostMapping
    public ResponseEntity<LinkRequestCreatedDto> create(@RequestBody LinkRequestCreateRequest body, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(linkRequestService.create(currentUser(request), body));
    }

    @GetMapping("/mine")
    public ResponseEntity<List<LinkRequestDto>> mine(HttpServletRequest request) {
        return ResponseEntity.ok(linkRequestService.mine(currentUser(request).getId()));
    }

    private User currentUser(HttpServletRequest request) {
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida ou expirada");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida ou expirada"));
    }
}
