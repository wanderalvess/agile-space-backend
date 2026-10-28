package com.agilespace.backend.service;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Preenche o cargo ({@code users.job_title}) de quem ainda não tem um, a partir da role que o Jira trouxe para a
 * pessoa. Nunca sobrescreve cargo já definido (o da administração vale) e nunca toca em {@code users.role}.
 * Cada preenchimento vai para a auditoria (USER_JOBTITLE_FILLED_FROM_JIRA).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CargoPreenchimento {

    static final String ACTION = "USER_JOBTITLE_FILLED_FROM_JIRA";

    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;

    /** Preenche, salva e audita. Retorna se gravou algo. {@code origem} descreve o caminho (ex.: "Sou eu"). */
    public boolean preencherSeVazio(User user, String roleDoJira, String origem) {
        if (!CargoNames.fillIfBlank(user, roleDoJira)) return false;
        userRepository.save(user);
        registrar(user, origem);
        return true;
    }

    /** Só a auditoria, para quem já gravou o cargo no próprio fluxo (ex.: a sincronização do Jira). */
    public void registrar(User user, String origem) {
        log.info("Cargo de {} preenchido pelo Jira: {} ({})", user.getId(), user.getJobTitle(), origem);
        auditLogRepository.save(AuditLog.builder()
                .id(UUID.randomUUID().toString())
                .action(ACTION)
                .performedBy("SISTEMA")
                .details("Conta " + user.getEmail() + " (cargo: (vazio) -> " + user.getJobTitle() + "; origem=" + origem + ")")
                .createdAt(LocalDateTime.now())
                .build());
    }
}
