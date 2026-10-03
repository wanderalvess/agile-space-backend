package com.agilespace.backend.controller;

import com.agilespace.backend.dto.AuthResponseDto;
import com.agilespace.backend.dto.ForgotPasswordRequestDto;
import com.agilespace.backend.dto.LoginRequestDto;
import com.agilespace.backend.dto.RegisterRequestDto;
import com.agilespace.backend.service.PasswordResetService;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;

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
     * Solicitação de redefinição de senha (evento de auditoria para o gestor aprovar).
     * Resposta é sempre genérica, independente do e-mail existir ou não, para não
     * permitir enumeração de contas cadastradas.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@Valid @RequestBody ForgotPasswordRequestDto request) {
        passwordResetService.requestReset(request.getEmail());
        return ResponseEntity.ok(Map.of("message", "Se o e-mail estiver cadastrado, uma solicitação de redefinição foi registrada para aprovação."));
    }

    /**
     * Retorna os dados da sessão autenticada a partir do usuário validado pelo JwtAuthenticationFilter.
     */
    @GetMapping("/me")
    public ResponseEntity<AuthResponseDto> getMe(HttpServletRequest request) {
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        return ResponseEntity.ok(authService.getMe(userId));
    }

    /**
     * Alterna o projeto ativo do usuário autenticado.
     */
    @PostMapping("/switch-project")
    public ResponseEntity<AuthResponseDto> switchProject(
            HttpServletRequest request,
            @RequestParam String projectId) {
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        return ResponseEntity.ok(authService.switchActiveProject(userId, projectId));
    }
}
