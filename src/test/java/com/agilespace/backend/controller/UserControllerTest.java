package com.agilespace.backend.controller;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.domain.UserJiraConfig;
import com.agilespace.backend.service.UserService;
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

    @Test
    public void testGetUserFound() {
        when(service.getUser("u1")).thenReturn(new User());
        
        ResponseEntity<User> response = controller.getUser("u1");
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testGetUserNotFound() {
        when(service.getUser("u1")).thenReturn(null);
        
        ResponseEntity<User> response = controller.getUser("u1");
        
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    public void testSaveUser() {
        User user = new User();
        when(service.saveUser(user)).thenReturn(user);
        
        ResponseEntity<User> response = controller.saveUser(user);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }
}
