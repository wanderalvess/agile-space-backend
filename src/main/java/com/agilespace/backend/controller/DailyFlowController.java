package com.agilespace.backend.controller;

import com.agilespace.backend.domain.DailyReport;
import com.agilespace.backend.domain.UserWorklog;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.DailyFlowService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/daily")
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class DailyFlowController {

    private final DailyFlowService dailyFlowService;

    /**
     * Worklog e daily report são dado pessoal: só o próprio usuário (ou um ADMIN) lê e grava.
     * Antes disso, todo endpoint aqui exigia só autenticação — qualquer usuário autenticado
     * lia/escrevia worklog e daily report de qualquer outro usuário trocando o userId.
     */
    private void requireSelfOrAdmin(String userId, HttpServletRequest request) {
        String role = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
        if ("ADMIN".equalsIgnoreCase(role)) {
            return;
        }
        String callerId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        if (callerId == null || !callerId.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito ao próprio usuário.");
        }
    }

    // --- User Worklogs Endpoints ---
    @GetMapping("/worklogs")
    public ResponseEntity<List<UserWorklog>> listWorklogs(
            @RequestParam("userId") String userId,
            @RequestParam("date") String date,
            HttpServletRequest request) {
        requireSelfOrAdmin(userId, request);
        return ResponseEntity.ok(dailyFlowService.listWorklogs(userId, date));
    }

    @PostMapping("/worklogs/weekly")
    public ResponseEntity<List<UserWorklog>> listWeeklyWorklogs(
            @RequestParam("userId") String userId,
            @RequestBody List<String> dates,
            HttpServletRequest request) {
        requireSelfOrAdmin(userId, request);
        return ResponseEntity.ok(dailyFlowService.listWeeklyWorklogs(userId, dates));
    }

    @PostMapping("/worklogs")
    public ResponseEntity<UserWorklog> saveOrUpdateWorklog(@Valid @RequestBody UserWorklog log, HttpServletRequest request) {
        requireSelfOrAdmin(log.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(dailyFlowService.saveOrUpdateWorklog(log));
    }

    @DeleteMapping("/worklogs/{id}")
    public ResponseEntity<Void> deleteWorklog(@PathVariable("id") String id, HttpServletRequest request) {
        try {
            String callerId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
            String role = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
            dailyFlowService.deleteWorklog(id, callerId, "ADMIN".equalsIgnoreCase(role));
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // --- Daily Reports Endpoints ---
    @GetMapping("/reports")
    public ResponseEntity<List<DailyReport>> listDailyReports(@RequestParam("userId") String userId, HttpServletRequest request) {
        requireSelfOrAdmin(userId, request);
        return ResponseEntity.ok(dailyFlowService.listDailyReports(userId));
    }

    @PostMapping("/reports")
    public ResponseEntity<DailyReport> saveOrUpdateDailyReport(@Valid @RequestBody DailyReport report, HttpServletRequest request) {
        requireSelfOrAdmin(report.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(dailyFlowService.saveOrUpdateDailyReport(report));
    }

    @DeleteMapping("/reports/{id}")
    public ResponseEntity<Void> deleteDailyReport(@PathVariable("id") String id, HttpServletRequest request) {
        try {
            String callerId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
            String role = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
            dailyFlowService.deleteDailyReport(id, callerId, "ADMIN".equalsIgnoreCase(role));
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
