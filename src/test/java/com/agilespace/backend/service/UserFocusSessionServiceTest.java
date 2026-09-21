package com.agilespace.backend.service;

import com.agilespace.backend.domain.UserFocusSession;
import com.agilespace.backend.repository.UserFocusSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserFocusSessionService - Sessões Pomodoro e Registro de Foco")
class UserFocusSessionServiceTest {

    @Mock
    private UserFocusSessionRepository repository;

    @InjectMocks
    private UserFocusSessionService service;

    @Nested
    @DisplayName("Salvamento")
    class SaveTests {

        @Test
        @DisplayName("Deve vincular userId e gerar ID e data de criação")
        void shouldBindUserIdAndGenerateIdAndTimestamp() {
            UserFocusSession session = UserFocusSession.builder()
                    .durationMinutes(25)
                    .taskCategory("Codificação")
                    .build();

            when(repository.save(any(UserFocusSession.class))).thenAnswer(i -> i.getArgument(0));

            UserFocusSession saved = service.saveSession("user-123", session);

            assertEquals("user-123", saved.getUserId());
            assertNotNull(saved.getId());
            assertNotNull(saved.getCreatedAt());
            verify(repository, times(1)).save(session);
        }
    }

    @Nested
    @DisplayName("Consulta")
    class QueryTests {

        @Test
        @DisplayName("Deve retornar sessões do usuário ordenadas por data de criação")
        void shouldReturnSessionsByUserId() {
            UserFocusSession s1 = UserFocusSession.builder().id("1").userId("user-123").durationMinutes(25).build();
            UserFocusSession s2 = UserFocusSession.builder().id("2").userId("user-123").durationMinutes(50).build();

            when(repository.findByUserIdOrderByCreatedAtDesc("user-123")).thenReturn(Arrays.asList(s1, s2));

            List<UserFocusSession> result = service.getSessions("user-123");

            assertEquals(2, result.size());
        }
    }
}
