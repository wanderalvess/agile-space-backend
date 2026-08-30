package com.agilespace.backend;

import com.agilespace.backend.domain.UserFocusSession;
import com.agilespace.backend.repository.UserFocusSessionRepository;
import com.agilespace.backend.controller.UserFocusSessionController;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class UserFocusSessionControllerTest {

    @Mock
    private UserFocusSessionRepository repository;

    @InjectMocks
    private UserFocusSessionController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    private HttpServletRequest requestAs(String userId, String role) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn(userId);
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE)).thenReturn(role);
        return request;
    }

    @Test
    public void testSaveSessionBindsUserIdAndGeneratesId() {
        UserFocusSession session = UserFocusSession.builder()
                .durationMinutes(25)
                .taskCategory("Codificação")
                .build();

        when(repository.save(any(UserFocusSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<UserFocusSession> response = controller.saveSession("user-123", session, requestAs("user-123", "MEMBER"));
        UserFocusSession saved = response.getBody();

        assertNotNull(saved);
        assertEquals("user-123", saved.getUserId());
        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        verify(repository, times(1)).save(session);
    }

    @Test
    public void testGetSessionsByUserId() {
        UserFocusSession s1 = UserFocusSession.builder().id("1").userId("user-123").durationMinutes(25).build();
        UserFocusSession s2 = UserFocusSession.builder().id("2").userId("user-123").durationMinutes(50).build();

        when(repository.findByUserIdOrderByCreatedAtDesc("user-123")).thenReturn(Arrays.asList(s1, s2));

        ResponseEntity<List<UserFocusSession>> response = controller.getSessions("user-123", requestAs("user-123", "MEMBER"));
        List<UserFocusSession> list = response.getBody();

        assertNotNull(list);
        assertEquals(2, list.size());
        verify(repository, times(1)).findByUserIdOrderByCreatedAtDesc("user-123");
    }

    @Test
    public void testGetSessionsRejectsAnotherUser() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getSessions("user-123", requestAs("intruso", "MEMBER")));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(repository, never()).findByUserIdOrderByCreatedAtDesc(anyString());
    }

    @Test
    public void testSaveSessionRejectsAnotherUser() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.saveSession("user-123", UserFocusSession.builder().build(), requestAs("intruso", "MEMBER")));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(repository, never()).save(any());
    }

    @Test
    public void testAdminCanReadAnotherUserSessions() {
        when(repository.findByUserIdOrderByCreatedAtDesc("user-123")).thenReturn(List.of());

        assertNotNull(controller.getSessions("user-123", requestAs("admin1", "ADMIN")).getBody());
    }
}
