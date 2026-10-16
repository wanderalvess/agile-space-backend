package com.agilespace.backend.controller;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.AdminLinkRequestDto;
import com.agilespace.backend.dto.LinkRequestCreateRequest;
import com.agilespace.backend.dto.LinkRequestCreatedDto;
import com.agilespace.backend.dto.LinkRequestDto;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
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
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Controllers de pedido de vínculo - solicitante e admin")
class LinkRequestControllerTest {

    @Mock private LinkRequestService service;
    @Mock private UserRepository userRepository;
    @Mock private HttpServletRequest request;

    private LinkRequestController userController;
    private AdminLinkRequestController adminController;

    @BeforeEach
    void setup() {
        userController = new LinkRequestController(service, userRepository);
        adminController = new AdminLinkRequestController(service);
    }

    @Test
    @DisplayName("POST cria com o usuário do token e responde 201 { id, status }")
    void createUsesTokenUser() {
        User ana = User.builder().id("u1").email("ana@x.com").name("Ana").build();
        LinkRequestCreateRequest body = new LinkRequestCreateRequest("DDWMISSI", "m1", "a@b.com", null);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("u1");
        when(userRepository.findById("u1")).thenReturn(Optional.of(ana));
        when(service.create(ana, body)).thenReturn(new LinkRequestCreatedDto("lr1", "PENDING"));

        ResponseEntity<LinkRequestCreatedDto> res = userController.create(body, request);

        assertEquals(HttpStatus.CREATED, res.getStatusCode());
        assertEquals("lr1", res.getBody().id());
        assertEquals("PENDING", res.getBody().status());
    }

    @Test
    @DisplayName("GET /mine lista só os do usuário do token")
    void mineUsesTokenUser() {
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("u1");
        when(userRepository.findById("u1")).thenReturn(Optional.of(User.builder().id("u1").build()));
        when(service.mine("u1")).thenReturn(List.of());

        ResponseEntity<List<LinkRequestDto>> res = userController.mine(request);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertTrue(res.getBody().isEmpty());
    }

    @Test
    @DisplayName("Sem sessão válida ou conta inexistente: 401")
    void unauthenticatedIs401() {
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn(null, "fantasma");
        when(userRepository.findById("fantasma")).thenReturn(Optional.empty());

        assertEquals(HttpStatus.UNAUTHORIZED,
                assertThrows(ResponseStatusException.class, () -> userController.mine(request)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                assertThrows(ResponseStatusException.class, () -> userController.mine(request)).getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("Admin: lista por status")
    void adminList() {
        when(service.adminList("PENDING")).thenReturn(List.of());
        assertEquals(HttpStatus.OK, adminController.list("PENDING").getStatusCode());
        verify(service).adminList("PENDING");
    }

    @Test
    @DisplayName("Admin: approve usa a linha do corpo (opcional) e identifica o admin pelo token (e-mail)")
    void adminApprove() {
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("adm");
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_EMAIL)).thenReturn("adm@totvs.com.br");
        AdminLinkRequestDto dto = mock(AdminLinkRequestDto.class);
        when(service.approve("lr1", "m2", "adm", "adm@totvs.com.br")).thenReturn(dto);
        when(service.approve("lr2", null, "adm", "adm@totvs.com.br")).thenReturn(dto);

        assertSame(dto, adminController.approve("lr1", new AdminLinkRequestController.ApproveBody("m2"), request).getBody());
        assertSame(dto, adminController.approve("lr2", null, request).getBody());
    }

    @Test
    @DisplayName("Admin: reject com e sem corpo; sem e-mail no token usa o id")
    void adminReject() {
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("adm");
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_EMAIL)).thenReturn(" ");
        AdminLinkRequestDto dto = mock(AdminLinkRequestDto.class);
        when(service.reject("lr1", "não confere", "adm", "adm")).thenReturn(dto);
        when(service.reject("lr2", null, "adm", "adm")).thenReturn(dto);

        assertSame(dto, adminController.reject("lr1", new AdminLinkRequestController.RejectBody("não confere"), request).getBody());
        assertSame(dto, adminController.reject("lr2", null, request).getBody());
    }

    @Test
    @DisplayName("Admin: token sem id nem e-mail cai no rótulo ADMIN")
    void adminLabelFallback() {
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn(null);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_EMAIL)).thenReturn(null);
        AdminLinkRequestDto dto = mock(AdminLinkRequestDto.class);
        when(service.reject("lr1", null, null, "ADMIN")).thenReturn(dto);

        assertSame(dto, adminController.reject("lr1", null, request).getBody());
    }
}
