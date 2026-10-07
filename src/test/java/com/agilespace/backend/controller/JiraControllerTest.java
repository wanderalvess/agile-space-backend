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

    @Test
    public void testTestCaseRequiresDomainTokenAndKey() {
        ResponseEntity<String> response = controller.getTestCase(java.util.Map.of("testCaseKey", "ABC-T1"));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    public void testTestCaseAcceptsPatAsToken() {
        when(service.getTestCase("jira.x.com", "tok", "ABC-T1")).thenReturn(ResponseEntity.ok("{\"key\":\"ABC-T1\"}"));
        ResponseEntity<String> response = controller.getTestCase(
                java.util.Map.of("domain", "jira.x.com", "pat", "tok", "testCaseKey", "ABC-T1"));
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testBoardsRequiresProjectKey() {
        ResponseEntity<String> response = controller.listScrumBoards(java.util.Map.of("domain", "d", "token", "t"));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        when(service.listScrumBoards("d", "t", "ABC")).thenReturn(ResponseEntity.ok("{\"values\":[]}"));
        assertEquals(HttpStatus.OK, controller.listScrumBoards(java.util.Map.of("domain", "d", "token", "t", "projectKey", "ABC")).getStatusCode());
    }
}
