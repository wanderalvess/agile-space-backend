package com.agilespace.backend.controller;

import com.agilespace.backend.dto.AdminLinkRequestDto;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.LinkRequestApprovalPolicy;
import com.agilespace.backend.service.LinkRequestService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Pedidos de vínculo vistos e decididos pela liderança da squad (docs/link-requests-squad-api.md). O {@code {id}} é a
 * chave da squad. Quem decide e o que pode vêm sempre do token e dos vínculos (project_member_roles), nunca do corpo; a
 * flag {@code app.link-requests.squad-approval=off} faz estas rotas responderem 404 (só {@code /api/admin/link-requests}
 * decide). O mesmo {@link LinkRequestService} serve a rota de admin: um fluxo só.
 */
@RestController
@RequestMapping("/api/squads/{id}/link-requests")
@RequiredArgsConstructor
public class SquadLinkRequestController {

    private final LinkRequestService linkRequestService;

    @GetMapping("/can-approve")
    public ResponseEntity<LinkRequestApprovalPolicy.Eligibility> canApprove(@PathVariable String id, HttpServletRequest request) {
        return ResponseEntity.ok(linkRequestService.canApprove(callerId(request), id));
    }

    @GetMapping
    public ResponseEntity<List<AdminLinkRequestDto>> list(@PathVariable String id,
                                                          @RequestParam(required = false) String status,
                                                          @RequestParam(required = false) Integer page,
                                                          @RequestParam(required = false) Integer size,
                                                          HttpServletRequest request) {
        return ResponseEntity.ok(linkRequestService.squadList(callerId(request), id, status, page, size));
    }

    @PostMapping("/{reqId}/approve")
    public ResponseEntity<AdminLinkRequestDto> approve(@PathVariable String id, @PathVariable String reqId,
                                                       @RequestBody(required = false) AdminLinkRequestController.ApproveBody body,
                                                       HttpServletRequest request) {
        return ResponseEntity.ok(linkRequestService.approveInSquad(id, reqId, body == null ? null : body.memberId(),
                callerId(request), callerLabel(request)));
    }

    @PostMapping("/{reqId}/reject")
    public ResponseEntity<AdminLinkRequestDto> reject(@PathVariable String id, @PathVariable String reqId,
                                                      @RequestBody(required = false) AdminLinkRequestController.RejectBody body,
                                                      HttpServletRequest request) {
        return ResponseEntity.ok(linkRequestService.rejectInSquad(id, reqId, body == null ? null : body.reason(),
                callerId(request), callerLabel(request)));
    }

    private static String callerId(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
    }

    /** E-mail do token (ou o id, sem e-mail): vai para decided_by, como na rota de admin. */
    private static String callerLabel(HttpServletRequest request) {
        String email = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_EMAIL);
        if (email != null && !email.isBlank()) return email;
        String id = callerId(request);
        return id != null ? id : "";
    }
}
