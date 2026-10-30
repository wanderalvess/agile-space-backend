package com.agilespace.backend.controller;

import com.agilespace.backend.dto.JiraPublishDtos;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.JiraPublishService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Publicação do refinamento de uma tarefa do Poker no Jira: prévia, envio, fila e retentativa. */
@RestController
@RequestMapping("/api/poker/{roomId}/jira-publish")
@RequiredArgsConstructor
public class JiraPublishController {

    private final JiraPublishService service;

    @GetMapping("/preview")
    public ResponseEntity<JiraPublishDtos.Preview> preview(@PathVariable String roomId,
                                                           @RequestParam("issueId") String issueId,
                                                           HttpServletRequest request) {
        return ResponseEntity.ok(service.preview(roomId, issueId, userId(request), role(request)));
    }

    @PostMapping
    public ResponseEntity<JiraPublishDtos.Job> submit(@PathVariable String roomId,
                                                      @RequestBody JiraPublishDtos.SubmitRequest body,
                                                      HttpServletRequest request) {
        JiraPublishDtos.Job job = service.submit(roomId, body, userId(request), role(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(job);
    }

    @GetMapping
    public ResponseEntity<List<JiraPublishDtos.Job>> list(@PathVariable String roomId, HttpServletRequest request) {
        return ResponseEntity.ok(service.listForRoom(roomId, userId(request), role(request)));
    }

    @PostMapping("/{jobId}/retry")
    public ResponseEntity<JiraPublishDtos.Job> retry(@PathVariable String roomId,
                                                     @PathVariable String jobId,
                                                     HttpServletRequest request) {
        return ResponseEntity.ok(service.retry(roomId, jobId, userId(request), role(request)));
    }

    private static String userId(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
    }

    private static String role(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
    }
}
