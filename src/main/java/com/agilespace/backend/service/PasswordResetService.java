package com.agilespace.backend.service;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.domain.PasswordResetRequest;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.repository.PasswordResetRequestRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.ApiKeyHashing;
import com.agilespace.backend.security.PasswordUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class PasswordResetService {

    private final PasswordResetRequestRepository resetRequestRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;

    /** Opcional (fora do construtor): limpa o cache de estado da conta quando as sessões são invalidadas. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.agilespace.backend.security.UserSessionGuard sessionGuard;

    private static final SecureRandom random = new SecureRandom();

    /**
     * Registra a solicitação para auditoria/aprovação do gestor. Sempre executa o mesmo
     * caminho (persiste + audita) independente do e-mail existir, para não permitir que a
     * resposta do endpoint público seja usada para enumerar contas cadastradas.
     */
    @Transactional
    public PasswordResetRequest requestReset(String email) {
        String cleanEmail = email.trim().toLowerCase();
        Optional<User> userOpt = userRepository.findByEmail(cleanEmail);

        String userName = userOpt.map(User::getName).orElse(null);

        // Pedido repetido enquanto há um pendente não cria outra linha: o endpoint é público e
        // sem isso daria para lotar a fila do admin.
        Optional<PasswordResetRequest> pending = resetRequestRepository.findByStatusOrderByRequestedAtDesc("PENDING").stream()
                .filter(r -> cleanEmail.equalsIgnoreCase(r.getUserEmail()))
                .findFirst();
        if (pending.isPresent()) {
            return pending.get();
        }

        PasswordResetRequest resetReq = PasswordResetRequest.builder()
                .id(UUID.randomUUID().toString())
                .userEmail(cleanEmail)
                .userName(userName)
                .status("PENDING")
                .requestedAt(LocalDateTime.now())
                .build();

        resetReq = resetRequestRepository.save(resetReq);

        // Cria log na auditoria
        AuditLog audit = AuditLog.builder()
                .id(UUID.randomUUID().toString())
                .action("PASSWORD_RESET_REQUESTED")
                .performedBy(cleanEmail)
                .details("Solicitação de redefinição de senha registrada para " + cleanEmail)
                .createdAt(LocalDateTime.now())
                .build();

        auditLogRepository.save(audit);
        log.info("Solicitação de reset de senha criada para e-mail: {}", cleanEmail);

        return resetReq;
    }

    /** O link de redefinição vale por 2 horas e só funciona uma vez. */
    static final long RESET_LINK_MINUTES = 120;
    private static final int MIN_PASSWORD = 8;
    private static final int MAX_PASSWORD = 128;

    @Transactional
    public List<PasswordResetRequest> getAllRequests() {
        List<PasswordResetRequest> all = resetRequestRepository.findAllByOrderByRequestedAtDesc();
        for (PasswordResetRequest r : all) {
            if (r.getTempPassword() != null) { // legado: senha temporária em claro não existe mais
                r.setTempPassword(null);
                resetRequestRepository.save(r);
            }
        }
        return all;
    }

    /**
     * Aprova o pedido e gera o link de redefinição. A senha atual NÃO muda e nenhuma senha em claro é criada:
     * o token vai só na resposta (campo resetToken, uma vez) e o banco guarda apenas o hash. Gerar de novo
     * invalida o link anterior.
     */
    @Transactional
    public PasswordResetRequest approveReset(String requestId, String approvedBy) {
        PasswordResetRequest resetReq = resetRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Solicitação de redefinição não encontrada"));

        String rawToken = generateToken();
        resetReq.setStatus("APPROVED");
        resetReq.setTempPassword(null);
        resetReq.setTokenHash(ApiKeyHashing.sha256Hex(rawToken));
        resetReq.setTokenExpiresAt(LocalDateTime.now().plusMinutes(RESET_LINK_MINUTES));
        resetReq.setUsedAt(null);
        resetReq.setApprovedAt(LocalDateTime.now());
        resetReq.setApprovedBy(approvedBy != null ? approvedBy : "ADMIN");
        resetReq = resetRequestRepository.save(resetReq);
        resetReq.setResetToken(rawToken);

        auditLogRepository.save(AuditLog.builder()
                .id(UUID.randomUUID().toString())
                .action("PASSWORD_RESET_LINK_ISSUED")
                .performedBy(resetReq.getApprovedBy())
                .details("Link de redefinição gerado para " + resetReq.getUserEmail())
                .createdAt(LocalDateTime.now())
                .build());
        log.info("Link de redefinição gerado por {} para e-mail: {}", resetReq.getApprovedBy(), resetReq.getUserEmail());

        return resetReq;
    }

    /**
     * Conclui a redefinição com o token do link: a pessoa define a própria senha. Qualquer falha (token
     * desconhecido, vencido ou já usado) responde igual, para o endpoint público não ajudar quem adivinha.
     */
    @Transactional
    public void completeReset(String rawToken, String newPassword) {
        if (newPassword == null || newPassword.length() < MIN_PASSWORD || newPassword.length() > MAX_PASSWORD) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Senha deve ter entre " + MIN_PASSWORD + " e " + MAX_PASSWORD + " caracteres");
        }
        PasswordResetRequest req = (rawToken == null || rawToken.isBlank()) ? null
                : resetRequestRepository.findByTokenHash(ApiKeyHashing.sha256Hex(rawToken.trim())).orElse(null);
        if (req == null || req.getUsedAt() != null || !"APPROVED".equalsIgnoreCase(req.getStatus())
                || req.getTokenExpiresAt() == null || req.getTokenExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Link de redefinição inválido ou expirado");
        }
        User user = userRepository.findByEmail(req.getUserEmail())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Link de redefinição inválido ou expirado"));
        user.setPasswordHash(PasswordUtil.hashPassword(newPassword));
        // Quem tinha a senha antiga (ou um token roubado) perde as sessões abertas.
        user.setTokensValidAfter(java.time.Instant.now().getEpochSecond());
        userRepository.save(user);
        if (sessionGuard != null) {
            sessionGuard.evict(user.getId());
        }
        req.setUsedAt(LocalDateTime.now());
        req.setStatus("COMPLETED");
        req.setTokenHash(null);
        resetRequestRepository.save(req);

        auditLogRepository.save(AuditLog.builder()
                .id(UUID.randomUUID().toString())
                .action("PASSWORD_RESET_COMPLETED")
                .performedBy(user.getEmail())
                .details("Senha redefinida pelo link para " + user.getEmail())
                .createdAt(LocalDateTime.now())
                .build());
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
