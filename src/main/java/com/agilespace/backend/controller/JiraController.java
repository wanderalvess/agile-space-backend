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
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class JiraController {

    private final JiraService jiraService;

    @PostMapping("/search")
    public ResponseEntity<String> searchIssues(@Valid @RequestBody JiraSearchRequest request) {
        return jiraService.searchIssues(request);
    }

    @PostMapping("/myself")
    public ResponseEntity<String> getMyself(@RequestBody java.util.Map<String, String> payload) {
        String domain = payload.get("domain");
        String token = payload.get("token");
        if (domain == null || token == null) {
            return ResponseEntity.badRequest().body("{\"error\": \"Domain e Token são obrigatórios.\"}");
        }
        return jiraService.getMyself(domain, token);
    }

    @PostMapping("/greenhopper/work-data")
    public ResponseEntity<String> getGreenhopperWorkData(@RequestBody com.agilespace.backend.dto.JiraGreenhopperWorkRequest request) {
        if (request.getDomain() == null || request.getToken() == null || request.getRapidViewId() == null) {
            return ResponseEntity.badRequest().body("{\"error\": \"Domain, Token e RapidViewId são obrigatórios.\"}");
        }
        return jiraService.getGreenhopperWorkData(
            request.getDomain(),
            request.getToken(),
            request.getRapidViewId(),
            request.getSelectedProjectKey()
        );
    }
}
