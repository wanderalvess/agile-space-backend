package com.agilespace.backend.service;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.domain.PasswordResetRequest;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.repository.PasswordResetRequestRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.PasswordUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class PasswordResetServiceTest {

    @Mock
    private PasswordResetRequestRepository resetRequestRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @InjectMocks
    private PasswordResetService service;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        lenient().when(resetRequestRepository.save(any(PasswordResetRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private User user(String email) {
        return User.builder().id("u1").email(email).name("Joao Silva").active(true).build();
    }

    // ---------- requestReset ----------

    @Test
    public void testRequestResetNormalizesEmailAndPersistsPending() {
        when(userRepository.findByEmail("joao.silva@empresa.com.br")).thenReturn(Optional.of(user("joao.silva@empresa.com.br")));

        PasswordResetRequest result = service.requestReset("  Joao.Silva@Empresa.com.BR ");

        assertEquals("joao.silva@empresa.com.br", result.getUserEmail());
        assertEquals("Joao Silva", result.getUserName());
        assertEquals("PENDING", result.getStatus());
        assertNotNull(result.getId());
        assertNotNull(result.getRequestedAt());
        assertNull(result.getTempPassword());
    }

    @Test
    public void testRequestResetForUnknownEmailFollowsSamePathToPreventEnumeration() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        PasswordResetRequest result = service.requestReset("fantasma@empresa.com.br");

        // Mesmo caminho: persiste solicitacao e grava auditoria, apenas sem nome resolvido.
        assertEquals("PENDING", result.getStatus());
        assertNull(result.getUserName());
        verify(resetRequestRepository).save(any(PasswordResetRequest.class));
        verify(auditLogRepository).save(any(AuditLog.class));
    }

    @Test
    public void testRequestResetWritesAuditLog() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(user("joao.silva@empresa.com.br")));

        service.requestReset("joao.silva@empresa.com.br");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog audit = captor.getValue();

        assertEquals("PASSWORD_RESET_REQUESTED", audit.getAction());
        assertEquals("joao.silva@empresa.com.br", audit.getPerformedBy());
        assertNotNull(audit.getCreatedAt());
    }

    // ---------- getAllRequests ----------

    @Test
    public void testGetAllRequestsDelegatesToRepositoryOrderedByDate() {
        PasswordResetRequest req = PasswordResetRequest.builder().id("r1").build();
        when(resetRequestRepository.findAllByOrderByRequestedAtDesc()).thenReturn(List.of(req));

        assertEquals(1, service.getAllRequests().size());
        verify(resetRequestRepository).findAllByOrderByRequestedAtDesc();
    }

    // ---------- approveReset ----------

    private PasswordResetRequest pendingRequest() {
        return PasswordResetRequest.builder()
                .id("r1")
                .userEmail("joao.silva@empresa.com.br")
                .userName("Joao Silva")
                .status("PENDING")
                .requestedAt(LocalDateTime.now())
                .build();
    }

    @Test
    public void testApproveResetIssuesLinkWithoutChangingThePassword() {
        User target = user("joao.silva@empresa.com.br");
        target.setPasswordHash("hash-antigo");
        when(resetRequestRepository.findById("r1")).thenReturn(Optional.of(pendingRequest()));

        PasswordResetRequest result = service.approveReset("r1", "admin@empresa.com.br");

        assertEquals("APPROVED", result.getStatus());
        assertEquals("admin@empresa.com.br", result.getApprovedBy());
        assertNotNull(result.getApprovedAt());
        assertNull(result.getTempPassword(), "nenhuma senha em claro é criada");
        assertNotNull(result.getResetToken());
        assertTrue(result.getResetToken().length() >= 40);
        // O banco guarda só o hash do token, nunca o token.
        assertNotEquals(result.getResetToken(), result.getTokenHash());
        assertEquals(64, result.getTokenHash().length());
        assertTrue(result.getTokenExpiresAt().isAfter(LocalDateTime.now().plusMinutes(100)));
        assertTrue(result.getTokenExpiresAt().isBefore(LocalDateTime.now().plusMinutes(130)));
        // A conta e a senha atual não são tocadas: a pessoa continua entrando até usar o link.
        verify(userRepository, never()).save(any());
        assertEquals("hash-antigo", target.getPasswordHash());
    }

    @Test
    public void testApproveResetTokensAreUnique() {
        when(resetRequestRepository.findById(anyString())).thenAnswer(inv -> Optional.of(pendingRequest()));

        Set<String> generated = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            String token = service.approveReset("r1", "admin").getResetToken();
            assertTrue(token.matches("[A-Za-z0-9_-]+"), "token fora do alfabeto url-safe: " + token);
            generated.add(token);
        }
        assertEquals(20, generated.size());
    }

    @Test
    public void testApproveResetAgainReplacesThePreviousLink() {
        PasswordResetRequest approved = pendingRequest();
        approved.setStatus("APPROVED");
        approved.setTokenHash("hash-do-link-antigo");
        when(resetRequestRepository.findById("r1")).thenReturn(Optional.of(approved));

        PasswordResetRequest result = service.approveReset("r1", "admin");

        assertNotEquals("hash-do-link-antigo", result.getTokenHash());
        assertNull(result.getUsedAt());
    }

    @Test
    public void testApproveResetDefaultsApprovedByToAdminWhenNull() {
        when(resetRequestRepository.findById("r1")).thenReturn(Optional.of(pendingRequest()));

        assertEquals("ADMIN", service.approveReset("r1", null).getApprovedBy());
    }

    @Test
    public void testApproveResetUnknownRequestReturnsNotFound() {
        when(resetRequestRepository.findById("ghost")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.approveReset("ghost", "admin"));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    public void testApproveResetWritesAuditLogWithoutTheToken() {
        when(resetRequestRepository.findById("r1")).thenReturn(Optional.of(pendingRequest()));

        PasswordResetRequest result = service.approveReset("r1", "admin@empresa.com.br");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog audit = captor.getValue();

        assertEquals("PASSWORD_RESET_LINK_ISSUED", audit.getAction());
        assertEquals("admin@empresa.com.br", audit.getPerformedBy());
        assertFalse(audit.getDetails().contains(result.getResetToken()), "o token nunca vai para o log de auditoria");
    }

    // ---------- completeReset ----------

    private String issueLink(PasswordResetRequest req) {
        when(resetRequestRepository.findById(req.getId())).thenReturn(Optional.of(req));
        String token = service.approveReset(req.getId(), "admin").getResetToken();
        when(resetRequestRepository.findByTokenHash(req.getTokenHash())).thenReturn(Optional.of(req));
        return token;
    }

    @Test
    public void testCompleteResetSetsThePasswordInvalidatesSessionsAndBurnsTheLink() {
        PasswordResetRequest req = pendingRequest();
        String token = issueLink(req);
        User target = user("joao.silva@empresa.com.br");
        target.setPasswordHash("hash-antigo");
        when(userRepository.findByEmail("joao.silva@empresa.com.br")).thenReturn(Optional.of(target));

        service.completeReset(token, "NovaSenha-123");

        assertTrue(PasswordUtil.verifyPassword("NovaSenha-123", target.getPasswordHash()));
        assertNotNull(target.getTokensValidAfter());
        assertEquals("COMPLETED", req.getStatus());
        assertNotNull(req.getUsedAt());
        assertNull(req.getTokenHash());
        verify(userRepository).save(target);
    }

    @Test
    public void testCompleteResetLinkWorksOnlyOnce() {
        PasswordResetRequest req = pendingRequest();
        String token = issueLink(req);
        String hash = req.getTokenHash();
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(user("joao.silva@empresa.com.br")));
        service.completeReset(token, "NovaSenha-123");

        // O mesmo token de novo: a linha já foi marcada como usada.
        when(resetRequestRepository.findByTokenHash(hash)).thenReturn(Optional.of(req));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.completeReset(token, "OutraSenha-456"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    public void testCompleteResetExpiredLinkIsRejected() {
        PasswordResetRequest req = pendingRequest();
        String token = issueLink(req);
        req.setTokenExpiresAt(LocalDateTime.now().minusMinutes(1));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.completeReset(token, "NovaSenha-123"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(userRepository, never()).save(any());
    }

    @Test
    public void testCompleteResetUnknownOrBlankTokenGetsTheSameGenericError() {
        when(resetRequestRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        ResponseStatusException unknown = assertThrows(ResponseStatusException.class, () -> service.completeReset("chute", "NovaSenha-123"));
        ResponseStatusException blank = assertThrows(ResponseStatusException.class, () -> service.completeReset("  ", "NovaSenha-123"));

        assertEquals(HttpStatus.BAD_REQUEST, unknown.getStatusCode());
        assertEquals(unknown.getReason(), blank.getReason());
        verify(userRepository, never()).save(any());
    }

    @Test
    public void testCompleteResetRejectsWeakPasswordBeforeTouchingTheToken() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.completeReset("qualquer", "curta"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(resetRequestRepository, never()).findByTokenHash(anyString());
    }
}
