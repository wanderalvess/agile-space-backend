package com.agilespace.backend.controller;

import com.agilespace.backend.dto.JiraSearchRequest;
import com.agilespace.backend.service.JiraService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/jira")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class JiraController {

    private final JiraService jiraService;

    @PostMapping("/search")
    public ResponseEntity<String> searchIssues(@Valid @RequestBody JiraSearchRequest request) {
        return jiraService.searchIssues(request);
    }
}
