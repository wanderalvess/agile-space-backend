package com.agilespace.backend.controller;

import com.agilespace.backend.dto.*;
import com.agilespace.backend.service.JiraAdminService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/jira")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class JiraAdminController {

    @Autowired
    private JiraAdminService jiraAdminService;

    @PostMapping("/preview-project")
    public ResponseEntity<JiraProjectPreviewDto> previewProject(@RequestBody JiraSyncRequest request) {
        JiraProjectPreviewDto preview = jiraAdminService.previewProject(request);
        return ResponseEntity.ok(preview);
    }

    @PostMapping("/confirm-sync")
    public ResponseEntity<JiraSyncResult> confirmSync(@RequestBody JiraConfirmSyncRequest request) {
        JiraSyncResult result = jiraAdminService.confirmSync(request);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/sync-project")
    public ResponseEntity<JiraSyncResult> syncProject(@RequestBody JiraSyncRequest request) {
        JiraSyncResult result = jiraAdminService.syncProject(request);
        return ResponseEntity.ok(result);
    }
}
