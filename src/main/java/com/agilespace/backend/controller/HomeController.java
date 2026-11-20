package com.agilespace.backend.controller;

import com.agilespace.backend.dto.HomeDto;
import com.agilespace.backend.dto.LiveSessionDto;
import com.agilespace.backend.dto.RitualSessionDto;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.HomeService;
import com.agilespace.backend.service.LiveSessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Home: agregado, sessões ao vivo e histórico por ritual (docs/home-api.md §5, §6 e §7). */
@RestController
@RequiredArgsConstructor
public class HomeController {

    private final HomeService homeService;
    private final LiveSessionService liveSessionService;

    @GetMapping("/api/home")
    public ResponseEntity<HomeDto> home(@RequestParam(required = false) String squadId, HttpServletRequest request) {
        return ResponseEntity.ok(homeService.home(userId(request), role(request), squadId));
    }

    @GetMapping("/api/live-sessions")
    public ResponseEntity<List<LiveSessionDto>> liveSessions(@RequestParam(required = false) String squadId, HttpServletRequest request) {
        return ResponseEntity.ok(liveSessionService.live(userId(request), role(request), squadId));
    }

    @GetMapping("/api/rituals/{tool}/history")
    public ResponseEntity<List<RitualSessionDto>> history(@PathVariable String tool,
                                                          @RequestParam(required = false) String squadId,
                                                          @RequestParam(required = false) Integer limit,
                                                          HttpServletRequest request) {
        return ResponseEntity.ok(liveSessionService.history(userId(request), role(request), tool, squadId, limit));
    }

    private static String userId(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
    }

    private static String role(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
    }
}
