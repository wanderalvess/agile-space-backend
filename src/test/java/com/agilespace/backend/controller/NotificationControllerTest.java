package com.agilespace.backend.controller;

import com.agilespace.backend.dto.UnreadCountDto;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class NotificationControllerTest {

    @Mock private NotificationService service;
    @InjectMocks private NotificationController controller;
    private HttpServletRequest request;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("ana");
    }

    @Test
    public void listAndCountUseTokenUser() {
        when(service.list("ana", "FOR_YOU", 30)).thenReturn(List.of());
        when(service.unreadCount("ana")).thenReturn(UnreadCountDto.of(1, 2, 3));
        assertEquals(HttpStatus.OK, controller.list("FOR_YOU", 30, request).getStatusCode());
        assertEquals(6, controller.unreadCount(request).getBody().total());
    }

    @Test
    public void readAndReadAll() {
        assertEquals(HttpStatus.NO_CONTENT, controller.read("n1", request).getStatusCode());
        verify(service).markRead("ana", "n1");
        assertEquals(HttpStatus.NO_CONTENT, controller.readAll(null, request).getStatusCode());
        verify(service).markAllRead("ana", null);
    }

    @Test
    public void readOthersNotificationIs404() {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND)).when(service).markRead("ana", "alheia");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.read("alheia", request));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }
}
