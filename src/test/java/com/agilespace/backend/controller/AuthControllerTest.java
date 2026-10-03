package com.agilespace.backend.controller;

import com.agilespace.backend.dto.AuthResponseDto;
import com.agilespace.backend.dto.ForgotPasswordRequestDto;
import com.agilespace.backend.dto.LoginRequestDto;
import com.agilespace.backend.dto.RegisterRequestDto;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.AuthService;
import com.agilespace.backend.service.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class AuthControllerTest {

    @Mock
    private AuthService authService;

    @Mock
    private PasswordResetService passwordResetService;

    @InjectMocks
    private AuthController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    private HttpServletRequest requestAsUser(String userId) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn(userId);
        return request;
    }

    private AuthResponseDto authResponse() {
        return AuthResponseDto.builder().token("jwt-token").tokenType("Bearer").id("u1").build();
    }

    @Test
    public void testLoginReturnsToken() {
        LoginRequestDto request = LoginRequestDto.builder().email("joao@empresa.com.br").password("SenhaForte123").build();
        when(authService.login(request)).thenReturn(authResponse());

        ResponseEntity<AuthResponseDto> response = controller.login(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("jwt-token", response.getBody().getToken());
    }

    @Test
    public void testLoginPropagatesUnauthorizedFromService() {
        LoginRequestDto request = LoginRequestDto.builder().email("joao@empresa.com.br").password("errada").build();
        when(authService.login(request))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "E-mail ou senha incorretos"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.login(request));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    public void testRegisterReturnsSession() {
        RegisterRequestDto request = RegisterRequestDto.builder()
                .email("novo@empresa.com.br").name("Novo").password("SenhaForte123").build();
        when(authService.register(request)).thenReturn(authResponse());

        ResponseEntity<AuthResponseDto> response = controller.register(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("u1", response.getBody().getId());
    }

    @Test
    public void testForgotPasswordReturnsGenericMessageForKnownEmail() {
        ResponseEntity<Map<String, String>> response = controller.forgotPassword(
                buildForgotRequest("joao@empresa.com.br"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(passwordResetService).requestReset("joao@empresa.com.br");
        assertTrue(response.getBody().get("message").startsWith("Se o e-mail estiver cadastrado"));
    }

    @Test
    public void testForgotPasswordResponseIsIdenticalForUnknownEmail() {
        // Nao pode existir diferenca observavel entre e-mail cadastrado e nao cadastrado
        // (evita enumeracao de contas pelo endpoint publico).
        ResponseEntity<Map<String, String>> known = controller.forgotPassword(buildForgotRequest("joao@empresa.com.br"));
        ResponseEntity<Map<String, String>> unknown = controller.forgotPassword(buildForgotRequest("fantasma@empresa.com.br"));

        assertEquals(known.getStatusCode(), unknown.getStatusCode());
        assertEquals(known.getBody(), unknown.getBody());
    }

    @Test
    public void testForgotPasswordNeverLeaksTempPassword() {
        ResponseEntity<Map<String, String>> response = controller.forgotPassword(buildForgotRequest("joao@empresa.com.br"));

        assertEquals(1, response.getBody().size());
        assertTrue(response.getBody().containsKey("message"));
    }

    @Test
    public void testGetMeUsesUserIdFromJwtFilterAttribute() {
        when(authService.getMe("u1")).thenReturn(authResponse());

        ResponseEntity<AuthResponseDto> response = controller.getMe(requestAsUser("u1"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(authService).getMe("u1");
        // Nenhum id vindo do corpo/query pode ser usado aqui.
        verify(authService, never()).getMe(argThat(id -> !"u1".equals(id)));
    }

    @Test
    public void testSwitchProjectUsesAuthenticatedUserIdNotClientSuppliedUser() {
        when(authService.switchActiveProject("u1", "OUTRO")).thenReturn(authResponse());

        ResponseEntity<AuthResponseDto> response = controller.switchProject(requestAsUser("u1"), "OUTRO");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(authService).switchActiveProject("u1", "OUTRO");
    }

    @Test
    public void testSwitchProjectPropagatesForbiddenFromService() {
        when(authService.switchActiveProject(anyString(), anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Usuario nao tem acesso a este projeto"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.switchProject(requestAsUser("u1"), "PROJETO-ALHEIO"));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    private ForgotPasswordRequestDto buildForgotRequest(String email) {
        ForgotPasswordRequestDto dto = new ForgotPasswordRequestDto();
        dto.setEmail(email);
        return dto;
    }
}
