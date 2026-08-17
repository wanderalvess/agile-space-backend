package com.agilespace.backend.controller;

import com.agilespace.backend.domain.DailyReport;
import com.agilespace.backend.domain.UserWorklog;
import com.agilespace.backend.service.DailyFlowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/daily")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DailyFlowController {

    private final DailyFlowService dailyFlowService;

    // --- User Worklogs Endpoints ---
    @GetMapping("/worklogs")
    public ResponseEntity<List<UserWorklog>> listWorklogs(
            @RequestParam("userId") String userId,
            @RequestParam("date") String date) {
        return ResponseEntity.ok(dailyFlowService.listWorklogs(userId, date));
    }

    @PostMapping("/worklogs/weekly")
    public ResponseEntity<List<UserWorklog>> listWeeklyWorklogs(
            @RequestParam("userId") String userId,
            @RequestBody List<String> dates) {
        return ResponseEntity.ok(dailyFlowService.listWeeklyWorklogs(userId, dates));
    }

    @PostMapping("/worklogs")
    public ResponseEntity<UserWorklog> saveOrUpdateWorklog(@Valid @RequestBody UserWorklog log) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dailyFlowService.saveOrUpdateWorklog(log));
    }

    @DeleteMapping("/worklogs/{id}")
    public ResponseEntity<Void> deleteWorklog(@PathVariable("id") String id) {
        try {
            dailyFlowService.deleteWorklog(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // --- Daily Reports Endpoints ---
    @GetMapping("/reports")
    public ResponseEntity<List<DailyReport>> listDailyReports(@RequestParam("userId") String userId) {
        return ResponseEntity.ok(dailyFlowService.listDailyReports(userId));
    }

    @PostMapping("/reports")
    public ResponseEntity<DailyReport> saveOrUpdateDailyReport(@Valid @RequestBody DailyReport report) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dailyFlowService.saveOrUpdateDailyReport(report));
    }
}
