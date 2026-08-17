package com.agilespace.backend;

import com.agilespace.backend.domain.UserFocusSession;
import com.agilespace.backend.repository.UserFocusSessionRepository;
import com.agilespace.backend.controller.UserFocusSessionController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;

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

    @Test
    public void testSaveSessionBindsUserIdAndGeneratesId() {
        UserFocusSession session = UserFocusSession.builder()
                .durationMinutes(25)
                .taskCategory("Codificação")
                .build();

        when(repository.save(any(UserFocusSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<UserFocusSession> response = controller.saveSession("user-123", session);
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

        ResponseEntity<List<UserFocusSession>> response = controller.getSessions("user-123");
        List<UserFocusSession> list = response.getBody();

        assertNotNull(list);
        assertEquals(2, list.size());
        verify(repository, times(1)).findByUserIdOrderByCreatedAtDesc("user-123");
    }
}
