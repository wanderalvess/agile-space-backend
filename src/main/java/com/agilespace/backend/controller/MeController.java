package com.agilespace.backend.controller;

import com.agilespace.backend.dto.MembershipsResponseDto;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.MembershipService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Dados da própria pessoa logada. Hoje só os vínculos (docs/membership-api.md). Qualquer usuário autenticado vê apenas
 * os seus: o id vem do token validado pelo JwtAuthenticationFilter, nunca de parâmetro.
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeController {

    private final MembershipService membershipService;

    @GetMapping("/memberships")
    public ResponseEntity<MembershipsResponseDto> memberships(HttpServletRequest request) {
        Object userId = request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        if (!(userId instanceof String id) || id.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida ou expirada");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(MembershipsResponseDto.CONTRACT_HEADER, MembershipsResponseDto.CONTRACT_VERSION)
                .body(membershipService.responseFor(id));
    }
}
