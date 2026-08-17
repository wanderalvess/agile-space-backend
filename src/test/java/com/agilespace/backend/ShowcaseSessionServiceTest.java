package com.agilespace.backend;

import com.agilespace.backend.domain.ShowcaseSession;
import com.agilespace.backend.repository.ShowcaseSessionRepository;
import com.agilespace.backend.service.ShowcaseSessionService;
import com.agilespace.backend.websocket.ShowcaseWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class ShowcaseSessionServiceTest {

    @Mock
    private ShowcaseSessionRepository repository;

    @Mock
    private ShowcaseWebSocketHandler webSocketHandler;

    @InjectMocks
    private ShowcaseSessionService service;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testGetSessionById() {
        ShowcaseSession session = ShowcaseSession.builder()
                .id("session-456")
                .name("Review Sprint 45")
                .status("active")
                .build();

        when(repository.findById("session-456")).thenReturn(Optional.of(session));

        ShowcaseSession result = service.getSession("session-456");

        assertNotNull(result);
        assertEquals("Review Sprint 45", result.getName());
        verify(repository, times(1)).findById("session-456");
    }

    @Test
    public void testGetLatestSessions() {
        when(repository.findLatestSessions(any(Pageable.class))).thenReturn(Arrays.asList(new ShowcaseSession()));
        List<ShowcaseSession> result = service.getLatestSessions(5);
        assertEquals(1, result.size());
    }

    @Test
    public void testSaveSessionGeneratesIdAndTriggersBroadcast() {
        ShowcaseSession session = ShowcaseSession.builder()
                .name("New Review")
                .status("planning")
                .tasks(new ArrayList<>())
                .members(new ArrayList<>())
                .build();

        when(repository.save(any(ShowcaseSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ShowcaseSession saved = service.saveSession(session);

        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        assertEquals("New Review", saved.getName());

        verify(webSocketHandler, times(1)).broadcastEvent(eq(saved.getId()), eq("SESSION_UPDATED"), any());
        verify(repository, times(1)).save(session);
    }
}
