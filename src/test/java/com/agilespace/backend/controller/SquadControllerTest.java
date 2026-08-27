package com.agilespace.backend.controller;

import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.SquadService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class SquadControllerTest {

    @Mock
    private SquadService service;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private SquadController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    private HttpServletRequest adminRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE)).thenReturn("ADMIN");
        return request;
    }

    @Test
    public void testGetSquadFound() {
        Squad squad = new Squad();
        when(service.getSquad("sq-1")).thenReturn(Optional.of(squad));
        
        ResponseEntity<Squad> response = controller.getSquad("sq-1");
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testGetSquadNotFound() {
        when(service.getSquad("sq-1")).thenReturn(Optional.empty());
        
        ResponseEntity<Squad> response = controller.getSquad("sq-1");
        
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    public void testSaveSquad() {
        Squad squad = new Squad();
        when(service.saveSquad(squad)).thenReturn(squad);
        
        ResponseEntity<Squad> response = controller.saveSquad("sq-1", squad, adminRequest());
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("sq-1", squad.getId());
    }
}
