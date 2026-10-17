package com.agilespace.backend.controller;

import com.agilespace.backend.dto.MembershipReportDto;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.MembershipReportService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

/**
 * Relatório somente leitura de inconsistências do vínculo Pessoa x Squad x Papel (docs/membership-report.md).
 * O role=ADMIN de /api/admin/** é exigido pelo JwtAuthenticationFilter; o controller confere de novo (defesa em
 * profundidade) e não grava nada.
 */
@RestController
@RequestMapping("/api/admin/membership")
@RequiredArgsConstructor
@Slf4j
public class AdminMembershipController {

    private final MembershipReportService reportService;

    @GetMapping("/report")
    public ResponseEntity<MembershipReportDto> report(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(reportService.generate());
    }

    @GetMapping(value = "/report.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> reportCsv(HttpServletRequest request) {
        requireAdmin(request);
        String csv = reportService.toCsv(reportService.generate());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, new MediaType("text", "csv", StandardCharsets.UTF_8).toString())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"membership-report.csv\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    private static void requireAdmin(HttpServletRequest request) {
        Object role = request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
        if (!(role instanceof String r) || !"ADMIN".equalsIgnoreCase(r)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a administradores");
        }
        log.info("Relatório de vínculos gerado por {}", request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID));
    }
}
