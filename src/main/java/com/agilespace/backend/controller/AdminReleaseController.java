package com.agilespace.backend.controller;

import com.agilespace.backend.domain.AppRelease;
import com.agilespace.backend.dto.AppReleaseDTO;
import com.agilespace.backend.service.AppReleaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/changelog")
@CrossOrigin(origins = "*")
public class AdminReleaseController {

    @Autowired
    private AppReleaseService service;

    @GetMapping
    public ResponseEntity<List<AppRelease>> getAllReleases() {
        return ResponseEntity.ok(service.getAllReleasesAdmin());
    }

    @PostMapping
    public ResponseEntity<AppRelease> createRelease(@RequestBody AppReleaseDTO dto) {
        return ResponseEntity.ok(service.createRelease(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AppRelease> updateRelease(@PathVariable String id, @RequestBody AppReleaseDTO dto) {
        return ResponseEntity.ok(service.updateRelease(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRelease(@PathVariable String id) {
        service.deleteRelease(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/import-legacy")
    public ResponseEntity<Map<String, Object>> importLegacy(@RequestBody List<AppReleaseDTO> dtos) {
        return ResponseEntity.ok(service.importLegacyReleases(dtos));
    }
}
