package com.agilespace.backend.controller;

import com.agilespace.backend.dto.JiraConfirmSyncRequest;
import com.agilespace.backend.dto.JiraProjectPreviewDto;
import com.agilespace.backend.dto.JiraSyncRequest;
import com.agilespace.backend.dto.JiraSyncResult;
import com.agilespace.backend.service.JiraAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class JiraAdminControllerTest {

    @Mock
    private JiraAdminService jiraAdminService;

    @InjectMocks
    private JiraAdminController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testPreviewProject() {
        JiraSyncRequest request = JiraSyncRequest.builder()
                .projectKey("PROJ1").jiraDomain("empresa.atlassian.net").token("tok").build();
        JiraProjectPreviewDto preview = JiraProjectPreviewDto.builder()
                .squadId("PROJ1").squadName("Projeto Um").members(Collections.emptyList()).build();
        when(jiraAdminService.previewProject(request)).thenReturn(preview);

        ResponseEntity<JiraProjectPreviewDto> response = controller.previewProject(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("PROJ1", response.getBody().getSquadId());
    }

    @Test
    public void testConfirmSync() {
        JiraConfirmSyncRequest request = JiraConfirmSyncRequest.builder().squadId("PROJ1").build();
        JiraSyncResult result = JiraSyncResult.builder().squadId("PROJ1").membersFound(3).build();
        when(jiraAdminService.confirmSync(request)).thenReturn(result);

        ResponseEntity<JiraSyncResult> response = controller.confirmSync(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(3, response.getBody().getMembersFound());
    }

    @Test
    public void testSyncProject() {
        JiraSyncRequest request = JiraSyncRequest.builder()
                .projectKey("PROJ1").jiraDomain("empresa.atlassian.net").token("tok").build();
        JiraSyncResult result = JiraSyncResult.builder().squadId("PROJ1").membersFound(5).build();
        when(jiraAdminService.syncProject(request)).thenReturn(result);

        ResponseEntity<JiraSyncResult> response = controller.syncProject(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(5, response.getBody().getMembersFound());
    }
}
