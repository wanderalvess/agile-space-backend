package com.agilespace.backend.controller;

import com.agilespace.backend.domain.UserFocusSession;
import com.agilespace.backend.repository.UserFocusSessionRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserFocusSessionController - Sessões Pomodoro e Registro de Foco")
class UserFocusSessionControllerTest {

    @Mock
    private UserFocusSessionRepository repository;

    @InjectMocks
    private UserFocusSessionController controller;

    private HttpServletRequest mockRequest(String userId, String role) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn(userId);
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE)).thenReturn(role);
        return request;
    }

    @Nested
    @DisplayName("Salvamento de Sessão de Foco")
    class SaveSessionTests {

        @Test
        @DisplayName("Deve vincular userId do path e gerar ID ao salvar sessão própria")
        void shouldSaveSessionForMatchingUser() {
            UserFocusSession session = UserFocusSession.builder()
                    .durationMinutes(25)
                    .taskCategory("Codificação")
                    .build();

            when(repository.save(any(UserFocusSession.class))).thenAnswer(i -> i.getArgument(0));

            ResponseEntity<UserFocusSession> response = controller.saveSession("user-123", session, mockRequest("user-123", "MEMBER"));
            UserFocusSession saved = response.getBody();

            assertNotNull(saved);
            assertEquals("user-123", saved.getUserId());
            assertNotNull(saved.getId(), "ID deve ser preenchido");
            assertNotNull(saved.getCreatedAt(), "CreatedAt deve ser preenchido");
            verify(repository, times(1)).save(session);
        }

        @Test
        @DisplayName("Deve rejeitar com HTTP 403 tentativa de salvar sessão para outro usuário")
        void shouldRejectSaveSessionForDifferentUser() {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                    controller.saveSession("user-123", UserFocusSession.builder().build(), mockRequest("intruso", "MEMBER"))
            );

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Listagem de Sessões de Foco")
    class GetSessionsTests {

        @Test
        @DisplayName("Deve retornar histórico de sessões do usuário ordenado decrescente")
        void shouldReturnUserSessionsWhenAuthorized() {
            UserFocusSession s1 = UserFocusSession.builder().id("1").userId("user-123").durationMinutes(25).build();
            UserFocusSession s2 = UserFocusSession.builder().id("2").userId("user-123").durationMinutes(50).build();

            when(repository.findByUserIdOrderByCreatedAtDesc("user-123")).thenReturn(Arrays.asList(s1, s2));

            ResponseEntity<List<UserFocusSession>> response = controller.getSessions("user-123", mockRequest("user-123", "MEMBER"));
            List<UserFocusSession> list = response.getBody();

            assertNotNull(list);
            assertEquals(2, list.size());
            verify(repository, times(1)).findByUserIdOrderByCreatedAtDesc("user-123");
        }

        @Test
        @DisplayName("Deve rejeitar com HTTP 403 leitura de sessões de outro usuário")
        void shouldRejectReadForDifferentUser() {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                    controller.getSessions("user-123", mockRequest("intruso", "MEMBER"))
            );

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
            verify(repository, never()).findByUserIdOrderByCreatedAtDesc(anyString());
        }

        @Test
        @DisplayName("Deve permitir ao ADMIN consultar sessões de qualquer usuário")
        void shouldAllowAdminToReadAnyUserSessions() {
            when(repository.findByUserIdOrderByCreatedAtDesc("user-123")).thenReturn(List.of());

            ResponseEntity<List<UserFocusSession>> response = controller.getSessions("user-123", mockRequest("admin-boss", "ADMIN"));

            assertNotNull(response.getBody());
            verify(repository).findByUserIdOrderByCreatedAtDesc("user-123");
        }
    }
}
