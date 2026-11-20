package com.agilespace.backend.controller;

import com.agilespace.backend.dto.JiraTextSourcesRequest;
import com.agilespace.backend.dto.JiraTextSourcesResponse;
import com.agilespace.backend.service.JiraTextSourceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** De onde pode vir cada texto de Problema, Solução e Critérios das issues importadas (docs/jira-import-origem-api.md). */
@RestController
@RequestMapping("/api/jira/text-sources")
@RequiredArgsConstructor
public class JiraTextSourceController {

    private final JiraTextSourceService service;

    @PostMapping
    public ResponseEntity<JiraTextSourcesResponse> analyze(@Valid @RequestBody JiraTextSourcesRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.analyze(request));
    }
}
