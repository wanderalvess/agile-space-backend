package com.agilespace.backend.controller;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.domain.UserJiraConfig;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class UserControllerTest {

    @Mock
    private UserService service;

    @InjectMocks
    private UserController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    private HttpServletRequest requestAsUser(String userId) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn(userId);
        return request;
    }

    @Test
    public void testGetUserFound() {
        when(service.getUser("u1")).thenReturn(new User());

        ResponseEntity<?> response = controller.getUser("u1", requestAsUser("u1"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testGetUserNotFound() {
        when(service.getUser("u1")).thenReturn(null);

        ResponseEntity<?> response = controller.getUser("u1", requestAsUser("u1"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    public void testGetUserRejectsNonOwnerNonAdmin() {
        ResponseEntity<?> response = controller.getUser("u1", requestAsUser("someone-else"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(service, never()).getUser(anyString());
    }

    @Test
    public void testSaveUser() {
        User user = new User();
        user.setId("u1");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("u1");
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE)).thenReturn("MEMBER");
        when(service.saveUser(user, false)).thenReturn(user);

        ResponseEntity<?> response = controller.saveUser(user, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testSaveUserRejectsUnauthenticatedRequest() {
        User user = new User();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn(null);

        ResponseEntity<?> response = controller.saveUser(user, request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }
}
