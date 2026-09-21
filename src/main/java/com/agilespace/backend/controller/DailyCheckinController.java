package com.agilespace.backend.controller;

import com.agilespace.backend.domain.DailyCheckin;
import com.agilespace.backend.service.DailyCheckinService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Daily checkin por squad — base do "Daily Digest com IA": um registro por
 * pessoa/dia, igual ao DailyReport, mas com squadId para permitir consultar
 * o dia inteiro do squad de uma vez (usado pela tela /daily-flow).
 */
@RestController
@RequestMapping("/api/daily-checkins")
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class DailyCheckinController {

    private final DailyCheckinService dailyCheckinService;

    @GetMapping
    public ResponseEntity<List<DailyCheckin>> listBySquadAndDate(
            @RequestParam("squadId") String squadId,
            @RequestParam("date") String date) {
        return ResponseEntity.ok(dailyCheckinService.listBySquadAndDate(squadId, date));
    }

    @GetMapping("/range")
    public ResponseEntity<List<DailyCheckin>> listBySquadAndDateRange(
            @RequestParam("squadId") String squadId,
            @RequestParam("startDate") String startDate,
            @RequestParam("endDate") String endDate) {
        return ResponseEntity.ok(dailyCheckinService.listBySquadAndDateRange(squadId, startDate, endDate));
    }

    @PostMapping
    public ResponseEntity<DailyCheckin> saveOrUpdate(@Valid @RequestBody DailyCheckin checkin) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dailyCheckinService.saveOrUpdate(checkin));
    }

    @PostMapping("/batch")
    public ResponseEntity<List<DailyCheckin>> saveOrUpdateBatch(@Valid @RequestBody List<DailyCheckin> checkins) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dailyCheckinService.saveOrUpdateBatch(checkins));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        try {
            dailyCheckinService.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
