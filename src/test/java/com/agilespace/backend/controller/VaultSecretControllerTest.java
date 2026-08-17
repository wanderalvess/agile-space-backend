package com.agilespace.backend.controller;

import com.agilespace.backend.domain.VaultSecret;
import com.agilespace.backend.service.VaultSecretService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class VaultSecretControllerTest {

    @Mock
    private VaultSecretService service;

    @InjectMocks
    private VaultSecretController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testCreateSecret() {
        VaultSecret secret = new VaultSecret();
        when(service.save(secret)).thenReturn(secret);
        
        ResponseEntity<VaultSecret> response = controller.createSecret(secret);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testGetSecretFound() {
        when(service.getAndProcessExpiration("123")).thenReturn(new VaultSecret());
        
        ResponseEntity<VaultSecret> response = controller.getSecret("123");
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testGetSecretNotFound() {
        when(service.getAndProcessExpiration("123")).thenReturn(null);
        
        ResponseEntity<VaultSecret> response = controller.getSecret("123");
        
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
