package com.agilespace.backend.controller;

import com.agilespace.backend.dto.AdminLinkRequestDto;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.LinkRequestApprovalPolicy;
import com.agilespace.backend.service.LinkRequestService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SquadLinkRequestController - rotas por squad delegam ao mesmo serviço, com o chamador do token")
class SquadLinkRequestControllerTest {

    @Mock private LinkRequestService service;
    @Mock private HttpServletRequest request;

    private SquadLinkRequestController controller;

    @BeforeEach
    void setup() {
        controller = new SquadLinkRequestController(service);
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("am1");
        lenient().when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_EMAIL)).thenReturn("am@totvs.com.br");
    }

    private static AdminLinkRequestDto dto(String status) {
        return new AdminLinkRequestDto("r1", new AdminLinkRequestDto.Requester("u1", "Ana", "ana@x.com"), "SQ", null, null,
                "a@b.com", null, status, null, null, null, null, "CONTRIBUTOR", "Developer", true);
    }

    @Test
    @DisplayName("can-approve: poderes do chamador do token, 200 { canApprove, canApproveLeadership }")
    void canApprove() {
        when(service.canApprove("am1", "SQ")).thenReturn(new LinkRequestApprovalPolicy.Eligibility(true, false));
        ResponseEntity<LinkRequestApprovalPolicy.Eligibility> res = controller.canApprove("SQ", request);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertTrue(res.getBody().canApprove());
        assertFalse(res.getBody().canApproveLeadership());
    }

    @Test
    @DisplayName("list repassa status e paginação")
    void list() {
        when(service.squadList("am1", "SQ", "ALL", 1, 20)).thenReturn(List.of(dto("PENDING")));
        assertEquals(1, controller.list("SQ", "ALL", 1, 20, request).getBody().size());
    }

    @Test
    @DisplayName("approve/reject usam id e e-mail do token; corpo ausente é permitido")
    void approveAndReject() {
        when(service.approveInSquad("SQ", "r1", null, "am1", "am@totvs.com.br")).thenReturn(dto("APPROVED"));
        when(service.approveInSquad("SQ", "r2", "m9", "am1", "am@totvs.com.br")).thenReturn(dto("APPROVED"));
        when(service.rejectInSquad("SQ", "r1", "motivo", "am1", "am@totvs.com.br")).thenReturn(dto("REJECTED"));
        when(service.rejectInSquad("SQ", "r3", null, "am1", "am@totvs.com.br")).thenReturn(dto("REJECTED"));

        assertEquals("APPROVED", controller.approve("SQ", "r1", null, request).getBody().status());
        assertEquals("APPROVED", controller.approve("SQ", "r2", new AdminLinkRequestController.ApproveBody("m9"), request).getBody().status());
        assertEquals("REJECTED", controller.reject("SQ", "r1", new AdminLinkRequestController.RejectBody("motivo"), request).getBody().status());
        assertEquals("REJECTED", controller.reject("SQ", "r3", null, request).getBody().status());
    }

    @Test
    @DisplayName("Sem e-mail no token, o id vira o rótulo de quem decidiu")
    void labelFallsBackToId() {
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_EMAIL)).thenReturn(null);
        when(service.approveInSquad("SQ", "r1", null, "am1", "am1")).thenReturn(dto("APPROVED"));
        assertEquals("APPROVED", controller.approve("SQ", "r1", null, request).getBody().status());
    }
}
