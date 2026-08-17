package com.agilespace.backend;

import com.agilespace.backend.domain.VaultSecret;
import com.agilespace.backend.repository.VaultSecretRepository;
import com.agilespace.backend.service.VaultSecretService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class VaultSecretServiceTest {

    @Mock
    private VaultSecretRepository repository;

    @InjectMocks
    private VaultSecretService service;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testSaveSecretGeneratesIdOnce() {
        VaultSecret secret = VaultSecret.builder().payload("payload").expirationType("once").build();
        when(repository.save(any(VaultSecret.class))).thenAnswer(i -> i.getArgument(0));

        VaultSecret saved = service.save(secret);

        assertNotNull(saved.getId());
        assertNull(saved.getExpiresAt());
        assertFalse(saved.isBurned());
    }

    @Test
    public void testSaveSecretGeneratesId1h() {
        VaultSecret secret = VaultSecret.builder().payload("payload").expirationType("1h").build();
        when(repository.save(any(VaultSecret.class))).thenAnswer(i -> i.getArgument(0));

        VaultSecret saved = service.save(secret);

        assertNotNull(saved.getExpiresAt());
    }

    @Test
    public void testSaveSecretGeneratesId24h() {
        VaultSecret secret = VaultSecret.builder().payload("payload").expirationType("24h").build();
        when(repository.save(any(VaultSecret.class))).thenAnswer(i -> i.getArgument(0));

        VaultSecret saved = service.save(secret);

        assertNotNull(saved.getExpiresAt());
    }

    @Test
    public void testRevealSecretOnceDestroysIt() {
        VaultSecret secret = VaultSecret.builder()
                .id("test-id")
                .payload("confidential_payload")
                .expirationType("once")
                .isBurned(false)
                .build();

        when(repository.findById("test-id")).thenReturn(Optional.of(secret));

        VaultSecret revealed = service.getAndProcessExpiration("test-id");

        assertNotNull(revealed);
        verify(repository, times(1)).delete(secret);
    }

    @Test
    public void testGetSecretExpiredReturnsNullAndDeletes() {
        VaultSecret secret = VaultSecret.builder()
                .id("expired-id")
                .expirationType("1h")
                .expiresAt(LocalDateTime.now().minusHours(1))
                .isBurned(false)
                .build();

        when(repository.findById("expired-id")).thenReturn(Optional.of(secret));

        VaultSecret revealed = service.getAndProcessExpiration("expired-id");

        assertNull(revealed);
        verify(repository, times(1)).delete(secret);
    }

    @Test
    public void testGetSecretNotFoundReturnsNull() {
        when(repository.findById("n1")).thenReturn(Optional.empty());
        VaultSecret result = service.getAndProcessExpiration("n1");
        assertNull(result);
    }

    @Test
    public void testGetSecretBurnedReturnsNullAndDeletes() {
        VaultSecret secret = VaultSecret.builder().id("b1").isBurned(true).build();
        when(repository.findById("b1")).thenReturn(Optional.of(secret));

        VaultSecret result = service.getAndProcessExpiration("b1");

        assertNull(result);
        verify(repository).delete(secret);
    }
}
