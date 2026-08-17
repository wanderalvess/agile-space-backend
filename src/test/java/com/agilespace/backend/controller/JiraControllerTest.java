package com.agilespace.backend.controller;

import com.agilespace.backend.dto.JiraSearchRequest;
import com.agilespace.backend.service.JiraService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class JiraControllerTest {

    @Mock
    private JiraService service;

    @InjectMocks
    private JiraController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testSearchIssues() {
        JiraSearchRequest request = new JiraSearchRequest();
        when(service.searchIssues(request)).thenReturn(ResponseEntity.ok("{\"issues\":[]}"));
        
        ResponseEntity<String> response = controller.searchIssues(request);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("{\"issues\":[]}", response.getBody());
    }
}
