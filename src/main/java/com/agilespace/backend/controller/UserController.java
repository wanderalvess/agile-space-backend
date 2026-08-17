package com.agilespace.backend.controller;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.domain.UserJiraConfig;
import com.agilespace.backend.domain.UserTdnConfig;
import com.agilespace.backend.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserService service;

    @GetMapping("/{id}")
    public ResponseEntity<User> getUser(@PathVariable String id) {
        User user = service.getUser(id);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(user);
    }

    @PostMapping
    public ResponseEntity<User> saveUser(@RequestBody User user) {
        User saved = service.saveUser(user);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{userId}/jira-config")
    public ResponseEntity<UserJiraConfig> getJiraConfig(@PathVariable String userId) {
        UserJiraConfig config = service.getJiraConfig(userId);
        if (config == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(config);
    }

    @PostMapping("/{userId}/jira-config")
    public ResponseEntity<UserJiraConfig> saveJiraConfig(@PathVariable String userId, @RequestBody UserJiraConfig config) {
        config.setUserId(userId);
        UserJiraConfig saved = service.saveJiraConfig(config);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{userId}/jira-config")
    public ResponseEntity<Void> deleteJiraConfig(@PathVariable String userId) {
        service.deleteJiraConfig(userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{userId}/tdn-config")
    public ResponseEntity<UserTdnConfig> getTdnConfig(@PathVariable String userId) {
        UserTdnConfig config = service.getTdnConfig(userId);
        if (config == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(config);
    }

    @PostMapping("/{userId}/tdn-config")
    public ResponseEntity<UserTdnConfig> saveTdnConfig(@PathVariable String userId, @RequestBody UserTdnConfig config) {
        config.setUserId(userId);
        UserTdnConfig saved = service.saveTdnConfig(config);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{userId}/tdn-config")
    public ResponseEntity<Void> deleteTdnConfig(@PathVariable String userId) {
        service.deleteTdnConfig(userId);
        return ResponseEntity.noContent().build();
    }
}

