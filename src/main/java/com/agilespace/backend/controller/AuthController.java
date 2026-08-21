package com.agilespace.backend.controller;

import com.agilespace.backend.dto.AuthResponseDto;
import com.agilespace.backend.dto.LoginRequestDto;
import com.agilespace.backend.dto.RegisterRequestDto;
import com.agilespace.backend.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class AuthController {

    private final AuthService authService;

    /**
     * Login padrão com e-mail corporativo e senha.
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponseDto> login(@Valid @RequestBody LoginRequestDto request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /**
     * Cadastro de novo usuário.
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponseDto> register(@Valid @RequestBody RegisterRequestDto request) {
        return ResponseEntity.ok(authService.register(request));
    }

    /**
     * Retorna os dados da sessão autenticada.
     */
    @GetMapping("/me")
    public ResponseEntity<AuthResponseDto> getMe(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) String userId) {
        String token = authHeader != null ? authHeader.replace("Bearer ", "").trim() : userId;
        return ResponseEntity.ok(authService.getMe(token));
    }

    /**
     * Alterna o projeto ativo do usuário.
     */
    @PostMapping("/switch-project")
    public ResponseEntity<AuthResponseDto> switchProject(
            @RequestParam String userId,
            @RequestParam String projectId) {
        return ResponseEntity.ok(authService.switchActiveProject(userId, projectId));
    }
}
