package com.agilespace.backend.controller;

import com.agilespace.backend.domain.AppRelease;
import com.agilespace.backend.service.AppReleaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/changelog")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class ChangelogController {

    @Autowired
    private AppReleaseService service;

    @GetMapping
    public ResponseEntity<List<AppRelease>> getChangelog() {
        return ResponseEntity.ok(service.getPublishedReleases());
    }

    @GetMapping("/latest")
    public ResponseEntity<AppRelease> getLatest() {
        return service.getLatestRelease()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AppRelease> getById(@PathVariable String id) {
        return service.getReleaseById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
