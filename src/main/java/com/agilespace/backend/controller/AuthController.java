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
     * Renova a sessão: devolve um token novo enquanto o teto absoluto da sessão (7 dias) não foi atingido.
     * O token atual precisa estar válido; quem ficou ausente além da validade entra de novo.
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponseDto> refresh(HttpServletRequest request) {
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        Object start = request.getAttribute(JwtAuthenticationFilter.ATTR_AUTH_AT);
        long authAt = start instanceof Long l ? l : 0L;
        return ResponseEntity.ok(authService.refresh(userId, authAt));
    }

    /** Troca a própria senha (logado). Devolve um token novo; as outras sessões caem. */
    @PostMapping("/change-password")
    public ResponseEntity<AuthResponseDto> changePassword(
            @Valid @RequestBody com.agilespace.backend.dto.ChangePasswordRequestDto body,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        return ResponseEntity.ok(authService.changePassword(userId, body.getCurrentPassword(), body.getNewPassword()));
    }

    /** Pública: conclui a redefinição com o token do link gerado pelo administrador. */
    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody com.agilespace.backend.dto.ResetPasswordRequestDto body) {
        passwordResetService.completeReset(body.getToken(), body.getNewPassword());
        return ResponseEntity.noContent().build();
    }

    /** Encerra a sessão no servidor: todos os tokens emitidos até agora para esta conta deixam de valer. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        authService.logout(userId);
        return ResponseEntity.noContent().build();
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
