package com.agilespace.backend.controller;

import com.agilespace.backend.domain.UserWorklog;
import com.agilespace.backend.service.DailyFlowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class DailyFlowControllerTest {

    @Mock
    private DailyFlowService service;

    @InjectMocks
    private DailyFlowController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testListWorklogs() {
        when(service.listWorklogs("u1", "2026-08-14")).thenReturn(Arrays.asList(new UserWorklog()));
        
        ResponseEntity<List<UserWorklog>> response = controller.listWorklogs("u1", "2026-08-14");
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
    }

    @Test
    public void testSaveOrUpdateWorklog() {
        UserWorklog log = new UserWorklog();
        when(service.saveOrUpdateWorklog(log)).thenReturn(log);
        
        ResponseEntity<UserWorklog> response = controller.saveOrUpdateWorklog(log);
        
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
    }

    @Test
    public void testDeleteWorklogSuccess() {
        doNothing().when(service).deleteWorklog("1");
        
        ResponseEntity<Void> response = controller.deleteWorklog("1");
        
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    public void testDeleteWorklogNotFound() {
        doThrow(new IllegalArgumentException()).when(service).deleteWorklog("1");
        
        ResponseEntity<Void> response = controller.deleteWorklog("1");
        
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
