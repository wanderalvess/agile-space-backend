package com.agilespace.backend.controller;

import com.agilespace.backend.domain.UserWorklog;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.DailyFlowService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    private HttpServletRequest mockRequest(String userId, String role) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn(userId);
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE)).thenReturn(role);
        return request;
    }

    @Test
    public void testListWorklogs() {
        when(service.listWorklogs("u1", "2026-08-14")).thenReturn(Arrays.asList(new UserWorklog()));

        ResponseEntity<List<UserWorklog>> response = controller.listWorklogs("u1", "2026-08-14", mockRequest("u1", "MEMBER"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
    }

    @Test
    public void testListWorklogsRejectsOtherUser() {
        assertThrows(ResponseStatusException.class,
                () -> controller.listWorklogs("u1", "2026-08-14", mockRequest("intruso", "MEMBER")));
        verify(service, never()).listWorklogs(anyString(), anyString());
    }

    @Test
    public void testSaveOrUpdateWorklog() {
        UserWorklog log = UserWorklog.builder().userId("u1").build();
        when(service.saveOrUpdateWorklog(log)).thenReturn(log);

        ResponseEntity<UserWorklog> response = controller.saveOrUpdateWorklog(log, mockRequest("u1", "MEMBER"));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
    }

    @Test
    public void testDeleteWorklogSuccess() {
        doNothing().when(service).deleteWorklog("1", "u1", false);

        ResponseEntity<Void> response = controller.deleteWorklog("1", mockRequest("u1", "MEMBER"));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    public void testDeleteWorklogNotFound() {
        doThrow(new IllegalArgumentException()).when(service).deleteWorklog("1", "u1", false);

        ResponseEntity<Void> response = controller.deleteWorklog("1", mockRequest("u1", "MEMBER"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    public void testDeleteWorklogAsAdminPassesAdminFlag() {
        doNothing().when(service).deleteWorklog("1", "admin-boss", true);

        ResponseEntity<Void> response = controller.deleteWorklog("1", mockRequest("admin-boss", "ADMIN"));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(service).deleteWorklog("1", "admin-boss", true);
    }
}
