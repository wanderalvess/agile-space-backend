package com.agilespace.backend.controller;

import com.agilespace.backend.dto.AuthzDiffDto;
import com.agilespace.backend.dto.LeadDemotionDto;
import com.agilespace.backend.dto.LeadRestoreDto;
import com.agilespace.backend.dto.MembershipReportDto;
import com.agilespace.backend.dto.MembershipShadowDiffDto;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.AuthzDiffService;
import com.agilespace.backend.service.LeadDemotionService;
import com.agilespace.backend.service.MembershipReportService;
import com.agilespace.backend.service.MembershipShadowService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final MembershipShadowService shadowService;
    private final AuthzDiffService authzDiffService;
    private final LeadDemotionService leadDemotionService;

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

    /**
     * Legado x novo para todos os usuários ativos (docs/membership-api.md). Somente leitura: recalcula do banco, não usa
     * nem popula o cache e não grava nada.
     */
    @GetMapping("/shadow-diff")
    public ResponseEntity<MembershipShadowDiffDto> shadowDiff(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(shadowService.report());
    }

    /**
     * Autorização por squad legada x nova para todos os usuários ativos x squads (Etapa 2, docs/membership-api.md).
     * Somente leitura. Contagens por sentido/ponto/motivo e amostra de até 20 divergências (ids e rótulos, sem e-mail).
     */
    @GetMapping("/authz-diff")
    public ResponseEntity<AuthzDiffDto> authzDiff(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(authzDiffService.report());
    }

    /**
     * Etapa 4: rebaixa LEAD para MEMBER só quem já tem vínculo de liderança. Dry-run por padrão (apply=false). Ver
     * docs/membership-api.md.
     */
    @PostMapping("/lead-demotion")
    public ResponseEntity<LeadDemotionDto> leadDemotion(
            @RequestParam(name = "apply", defaultValue = "false") boolean apply,
            @RequestParam(name = "limit", defaultValue = "" + LeadDemotionService.DEFAULT_LIMIT) int limit,
            @RequestParam(name = "userId", required = false) String userId,
            @RequestParam(name = "forceDemote", defaultValue = "false") boolean forceDemote,
            HttpServletRequest request) {
        requireAdmin(request);
        String actor = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(leadDemotionService.demote(apply, limit, userId, forceDemote, actor));
    }

    /** Etapa 4: desfaz o rebaixamento a partir de users_role_lead_backup (uma conta com userId, ou todas). */
    @PostMapping("/lead-restore")
    public ResponseEntity<LeadRestoreDto> leadRestore(
            @RequestParam(name = "userId", required = false) String userId,
            HttpServletRequest request) {
        requireAdmin(request);
        String actor = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(leadDemotionService.restore(userId, actor));
    }

    private static void requireAdmin(HttpServletRequest request) {
        Object role = request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE);
        if (!(role instanceof String r) || !"ADMIN".equalsIgnoreCase(r)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a administradores");
        }
        log.info("Relatório de vínculos gerado por {}", request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID));
    }
}
