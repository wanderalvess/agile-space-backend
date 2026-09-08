package com.agilespace.backend.service;

import com.agilespace.backend.domain.ShowcaseSession;
import com.agilespace.backend.repository.ShowcaseSessionRepository;
import com.agilespace.backend.websocket.ShowcaseWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ShowcaseSessionService - Gestão de Sessões de Demonstração e Aceite de Itens")
class ShowcaseSessionServiceTest {

    @Mock
    private ShowcaseSessionRepository repository;

    @Mock
    private ShowcaseWebSocketHandler webSocketHandler;

    @InjectMocks
    private ShowcaseSessionService service;

    private ShowcaseSession sampleSession;

    @BeforeEach
    void setUp() {
        sampleSession = ShowcaseSession.builder()
                .id("session-456")
                .name("Review e Showcase da Sprint 45")
                .status("active")
                .createdBy("user-po")
                .createdAt(LocalDateTime.of(2026, 9, 1, 14, 0))
                .tasks(new ArrayList<>())
                .members(new ArrayList<>())
                .build();
    }

    @Nested
    @DisplayName("Consulta de Sessões")
    class QuerySessionTests {

        @Test
        @DisplayName("Deve retornar os detalhes da sessão quando ID existir")
        void shouldReturnSessionWhenIdExists() {
            when(repository.findById("session-456")).thenReturn(Optional.of(sampleSession));

            ShowcaseSession result = service.getSession("session-456");

            assertNotNull(result);
            assertEquals("session-456", result.getId());
            assertEquals("Review e Showcase da Sprint 45", result.getName());
            assertEquals("active", result.getStatus());
            verify(repository).findById("session-456");
        }

        @Test
        @DisplayName("Deve retornar null quando a sessão procurada não existir no banco")
        void shouldReturnNullWhenSessionNotFound() {
            when(repository.findById("session-inexistente")).thenReturn(Optional.empty());

            ShowcaseSession result = service.getSession("session-inexistente");

            assertNull(result);
        }

        @Test
        @DisplayName("Deve buscar sessões recentes respeitando limite seguro")
        void shouldReturnLatestSessionsWithLimit() {
            when(repository.findLatestSessions(any(Pageable.class)))
                    .thenReturn(Collections.singletonList(sampleSession));

            List<ShowcaseSession> sessions = service.getLatestSessions(10);

            assertNotNull(sessions);
            assertEquals(1, sessions.size());
            assertEquals("session-456", sessions.get(0).getId());
            verify(repository).findLatestSessions(any(Pageable.class));
        }

        @Test
        @DisplayName("Deve usar limite padrão de 50 quando limite fornecido for zero ou negativo")
        void shouldDefaultTo50WhenLimitIsZeroOrNegative() {
            when(repository.findLatestSessions(any(Pageable.class)))
                    .thenReturn(Collections.singletonList(sampleSession));

            List<ShowcaseSession> sessions = service.getLatestSessions(0);

            assertNotNull(sessions);
            assertEquals(1, sessions.size());
        }
    }

    @Nested
    @DisplayName("Criação e Atualização de Sessão")
    class SaveSessionTests {

        @Test
        @DisplayName("Deve criar nova sessão gerando UUID, atribuindo criador e transmitindo via WebSocket")
        void shouldCreateNewSessionAndBroadcastEvent() {
            ShowcaseSession newSession = ShowcaseSession.builder()
                    .name("Nova Apresentação de Entrega")
                    .status("planning")
                    .build();

            when(repository.save(any(ShowcaseSession.class))).thenAnswer(i -> i.getArgument(0));

            ShowcaseSession saved = service.saveSession(newSession, "user-author");

            assertNotNull(saved.getId(), "UUID gerado não pode ser nulo");
            assertEquals("user-author", saved.getCreatedBy());
            assertNotNull(saved.getCreatedAt());
            assertEquals("Nova Apresentação de Entrega", saved.getName());

            verify(repository).save(newSession);
            verify(webSocketHandler).broadcastEvent(eq(saved.getId()), eq("SESSION_UPDATED"), eq(saved));
        }

        @Test
        @DisplayName("Deve preservar criador e data de criação originais ao atualizar sessão existente")
        void shouldPreserveOriginalMetadataWhenUpdatingExistingSession() {
            LocalDateTime originalCreatedAt = LocalDateTime.of(2026, 8, 20, 10, 0);
            ShowcaseSession existing = ShowcaseSession.builder()
                    .id("session-456")
                    .name("Nome Antigo")
                    .createdBy("criador-original")
                    .createdAt(originalCreatedAt)
                    .build();

            when(repository.findById("session-456")).thenReturn(Optional.of(existing));
            when(repository.save(any(ShowcaseSession.class))).thenAnswer(i -> i.getArgument(0));

            ShowcaseSession updatePayload = ShowcaseSession.builder()
                    .id("session-456")
                    .name("Nome Atualizado")
                    .status("completed")
                    .build();

            ShowcaseSession result = service.saveSession(updatePayload, "outro-editor");

            assertEquals("criador-original", result.getCreatedBy(), "Criador original deve ser preservado");
            assertEquals(originalCreatedAt, result.getCreatedAt(), "Data de criação original deve ser preservada");
            assertEquals("Nome Atualizado", result.getName());
            assertEquals("completed", result.getStatus());

            verify(webSocketHandler).broadcastEvent(eq("session-456"), eq("SESSION_UPDATED"), eq(result));
        }
    }
}
