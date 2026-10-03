package com.agilespace.backend.service;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.domain.PasswordResetRequest;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.repository.PasswordResetRequestRepository;
import com.agilespace.backend.repository.UserRepository;
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

    private static final String CHAR_LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String CHAR_UPPER = CHAR_LOWER.toUpperCase();
    private static final String NUMBER = "0123456789";
    private static final String PASSWORD_ALLOW_BASE = CHAR_LOWER + CHAR_UPPER + NUMBER;
    private static final int TEMP_PASSWORD_LENGTH = 16;
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

    @Transactional(readOnly = true)
    public List<PasswordResetRequest> getAllRequests() {
        return resetRequestRepository.findAllByOrderByRequestedAtDesc();
    }

    @Transactional
    public PasswordResetRequest approveReset(String requestId, String approvedBy) {
        PasswordResetRequest resetReq = resetRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Solicitação de redefinição não encontrada"));

        if ("APPROVED".equalsIgnoreCase(resetReq.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Esta solicitação já foi aprovada anteriormente");
        }

        // Gera senha temporária segura ex: Temp#7a9x
        String tempPassword = generateTempPassword();

        // Atualiza a senha no banco de dados para o usuário correspondente
        Optional<User> userOpt = userRepository.findByEmail(resetReq.getUserEmail());
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.setPasswordHash(PasswordUtil.hashPassword(tempPassword));
            userRepository.save(user);
        }

        resetReq.setStatus("APPROVED");
        resetReq.setTempPassword(tempPassword);
        resetReq.setApprovedAt(LocalDateTime.now());
        resetReq.setApprovedBy(approvedBy != null ? approvedBy : "ADMIN");
        resetReq = resetRequestRepository.save(resetReq);

        // Log de Auditoria
        AuditLog audit = AuditLog.builder()
                .id(UUID.randomUUID().toString())
                .action("PASSWORD_RESET_APPROVED")
                .performedBy(resetReq.getApprovedBy())
                .details("Senha temporária gerada e aprovada para " + resetReq.getUserEmail())
                .createdAt(LocalDateTime.now())
                .build();

        auditLogRepository.save(audit);
        log.info("Reset de senha aprovado por {} para e-mail: {}", resetReq.getApprovedBy(), resetReq.getUserEmail());

        return resetReq;
    }

    private String generateTempPassword() {
        StringBuilder sb = new StringBuilder(TEMP_PASSWORD_LENGTH);
        for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
            int rndCharAt = random.nextInt(PASSWORD_ALLOW_BASE.length());
            sb.append(PASSWORD_ALLOW_BASE.charAt(rndCharAt));
        }
        return sb.toString();
    }
}
