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
    public void testApproveResetGeneratesTempPasswordAndRehashesUserPassword() {
        User target = user("joao.silva@empresa.com.br");
        target.setPasswordHash("hash-antigo");
        when(resetRequestRepository.findById("r1")).thenReturn(Optional.of(pendingRequest()));
        when(userRepository.findByEmail("joao.silva@empresa.com.br")).thenReturn(Optional.of(target));

        PasswordResetRequest result = service.approveReset("r1", "admin@empresa.com.br");

        assertEquals("APPROVED", result.getStatus());
        assertEquals("admin@empresa.com.br", result.getApprovedBy());
        assertNotNull(result.getApprovedAt());
        assertEquals(16, result.getTempPassword().length());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        String newHash = captor.getValue().getPasswordHash();

        assertNotEquals("hash-antigo", newHash);
        assertTrue(newHash.startsWith("pbkdf2:sha256:"));
        // A senha temporaria entregue ao gestor tem que ser a que realmente autentica.
        assertTrue(PasswordUtil.verifyPassword(result.getTempPassword(), newHash));
    }

    @Test
    public void testApproveResetTempPasswordIsAlphanumericAndUnique() {
        when(resetRequestRepository.findById(anyString())).thenAnswer(inv -> Optional.of(pendingRequest()));
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        Set<String> generated = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            String temp = service.approveReset("r1", "admin").getTempPassword();
            assertTrue(temp.matches("[A-Za-z0-9]{16}"), "senha temporaria fora do alfabeto esperado: " + temp);
            generated.add(temp);
        }
        assertEquals(20, generated.size(), "senhas temporarias devem ser sempre distintas");
    }

    @Test
    public void testApproveResetDefaultsApprovedByToAdminWhenNull() {
        when(resetRequestRepository.findById("r1")).thenReturn(Optional.of(pendingRequest()));
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        assertEquals("ADMIN", service.approveReset("r1", null).getApprovedBy());
    }

    @Test
    public void testApproveResetForUnknownUserStillApprovesWithoutSavingUser() {
        when(resetRequestRepository.findById("r1")).thenReturn(Optional.of(pendingRequest()));
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        PasswordResetRequest result = service.approveReset("r1", "admin");

        assertEquals("APPROVED", result.getStatus());
        verify(userRepository, never()).save(any());
    }

    @Test
    public void testApproveResetUnknownRequestReturnsNotFound() {
        when(resetRequestRepository.findById("ghost")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.approveReset("ghost", "admin"));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(userRepository, never()).save(any());
    }

    @Test
    public void testApproveResetTwiceIsRejected() {
        PasswordResetRequest approved = pendingRequest();
        approved.setStatus("approved");
        when(resetRequestRepository.findById("r1")).thenReturn(Optional.of(approved));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.approveReset("r1", "admin"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(userRepository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    public void testApproveResetWritesAuditLog() {
        when(resetRequestRepository.findById("r1")).thenReturn(Optional.of(pendingRequest()));
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        service.approveReset("r1", "admin@empresa.com.br");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog audit = captor.getValue();

        assertEquals("PASSWORD_RESET_APPROVED", audit.getAction());
        assertEquals("admin@empresa.com.br", audit.getPerformedBy());
        // A senha temporaria nunca pode vazar para o log de auditoria.
        assertFalse(audit.getDetails().contains("Temp"));
    }
}
