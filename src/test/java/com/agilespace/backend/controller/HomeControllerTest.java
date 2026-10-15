package com.agilespace.backend.controller;

import com.agilespace.backend.dto.HomeDto;
import com.agilespace.backend.dto.UnreadCountDto;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.HomeService;
import com.agilespace.backend.service.LiveSessionService;
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

public class HomeControllerTest {

    @Mock private HomeService homeService;
    @Mock private LiveSessionService liveSessionService;
    @InjectMocks private HomeController controller;
    private HttpServletRequest request;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("ana");
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE)).thenReturn("MEMBER");
    }

    @Test
    public void homeUsesTokenIdentity() {
        HomeDto dto = new HomeDto(List.of(), UnreadCountDto.of(0, 0, 0), false, List.of(), List.of(), List.of());
        when(homeService.home("ana", "MEMBER", "SQA")).thenReturn(dto);
        assertSame(dto, controller.home("SQA", request).getBody());
    }

    @Test
    public void liveAndHistoryDelegate() {
        when(liveSessionService.live("ana", "MEMBER", null)).thenReturn(List.of());
        when(liveSessionService.history("ana", "MEMBER", "poker", "SQA", 10)).thenReturn(List.of());
        assertEquals(HttpStatus.OK, controller.liveSessions(null, request).getStatusCode());
        assertEquals(HttpStatus.OK, controller.history("poker", "SQA", 10, request).getStatusCode());
    }

    @Test
    public void forbiddenPropagates() {
        when(liveSessionService.live("ana", "MEMBER", "SQX")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThrows(ResponseStatusException.class, () -> controller.liveSessions("SQX", request));
        when(homeService.home("ana", "MEMBER", "SQX")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThrows(ResponseStatusException.class, () -> controller.home("SQX", request));
    }
}
