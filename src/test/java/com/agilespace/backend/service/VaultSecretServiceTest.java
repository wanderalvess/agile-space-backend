package com.agilespace.backend.service;

import com.agilespace.backend.domain.VaultSecret;
import com.agilespace.backend.repository.VaultSecretRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("VaultSecretService - Cofre Efêmero de Segredos (One-Time / Expiráveis)")
class VaultSecretServiceTest {

    @Mock
    private VaultSecretRepository repository;

    @InjectMocks
    private VaultSecretService service;

    @Nested
    @DisplayName("Armazenamento de Segredos")
    class StoreSecretTests {

        @Test
        @DisplayName("Deve salvar segredo de uso único ('once') sem data de expiração fixa")
        void shouldSaveOneTimeSecret() {
            VaultSecret secret = VaultSecret.builder().payload("senha-super-secreta").expirationType("once").build();
            when(repository.save(any(VaultSecret.class))).thenAnswer(i -> i.getArgument(0));

            VaultSecret saved = service.save(secret);

            assertNotNull(saved.getId(), "Deve gerar UUID");
            assertNull(saved.getExpiresAt(), "Segredo 'once' não tem expiração por relógio");
            assertFalse(saved.isBurned());
            verify(repository).save(secret);
        }

        @Test
        @DisplayName("Deve calcular data de expiração para segredo de 1 hora")
        void shouldSave1hSecretWithExpiresAt() {
            VaultSecret secret = VaultSecret.builder().payload("token-temporario").expirationType("1h").build();
            when(repository.save(any(VaultSecret.class))).thenAnswer(i -> i.getArgument(0));

            VaultSecret saved = service.save(secret);

            assertNotNull(saved.getExpiresAt(), "Deve calcular data limite de 1h");
            assertTrue(saved.getExpiresAt().isAfter(LocalDateTime.now()));
        }

        @Test
        @DisplayName("Deve calcular data de expiração para segredo de 24 horas")
        void shouldSave24hSecretWithExpiresAt() {
            VaultSecret secret = VaultSecret.builder().payload("certificado-24h").expirationType("24h").build();
            when(repository.save(any(VaultSecret.class))).thenAnswer(i -> i.getArgument(0));

            VaultSecret saved = service.save(secret);

            assertNotNull(saved.getExpiresAt(), "Deve calcular data limite de 24h");
        }
    }

    @Nested
    @DisplayName("Consumo e Auto-Destruição de Segredos")
    class ConsumeSecretTests {

        @Test
        @DisplayName("Deve revelar segredo 'once' e destruí-lo imediatamente do banco")
        void shouldRevealAndBurnOneTimeSecret() {
            VaultSecret secret = VaultSecret.builder()
                    .id("sec-123")
                    .payload("segredo-confidencial")
                    .expirationType("once")
                    .isBurned(false)
                    .build();

            when(repository.findById("sec-123")).thenReturn(Optional.of(secret));

            VaultSecret revealed = service.getAndProcessExpiration("sec-123");

            assertNotNull(revealed);
            assertEquals("segredo-confidencial", revealed.getPayload());
            verify(repository, times(1)).delete(secret);
        }

        @Test
        @DisplayName("Deve retornar null e excluir segredo expirado pelo tempo")
        void shouldReturnNullAndPurgeExpiredSecret() {
            VaultSecret secret = VaultSecret.builder()
                    .id("sec-exp")
                    .expirationType("1h")
                    .expiresAt(LocalDateTime.now().minusHours(2))
                    .isBurned(false)
                    .build();

            when(repository.findById("sec-exp")).thenReturn(Optional.of(secret));

            VaultSecret revealed = service.getAndProcessExpiration("sec-exp");

            assertNull(revealed, "Segredo expirado não pode ser retornado");
            verify(repository, times(1)).delete(secret);
        }

        @Test
        @DisplayName("Deve retornar null quando segredo procurado não existir")
        void shouldReturnNullWhenNotFound() {
            when(repository.findById("fantasma")).thenReturn(Optional.empty());

            VaultSecret result = service.getAndProcessExpiration("fantasma");

            assertNull(result);
        }
    }
}
