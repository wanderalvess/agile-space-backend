package com.agilespace.backend.service;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.service.SessionStatusResolver.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LiveSessionService - acesso à squad em ao vivo e histórico")
class LiveSessionServiceTest {

    @Mock private SquadAudienceService audienceService;
    @Mock private SquadAccessService squadAccessService;
    @Mock private RitualSessionService ritualSessionService;
    @InjectMocks private LiveSessionService service;

    @BeforeEach
    void ctx() {
        lenient().when(audienceService.contextFor(eq("ana"), any())).thenReturn(Optional.of(new SquadAudienceService.UserContext(
                User.builder().id("ana").build(), "SQA", List.of("SQA", "SQB"), Set.of("MEMBER"), false)));
    }

    @Test
    @DisplayName("Sem squadId: todas as acessíveis")
    void liveAllAccessible() {
        service.live("ana", "MEMBER", null);
        verify(ritualSessionService).live(eq(List.of("SQA", "SQB")), any());
    }

    @Test
    @DisplayName("squadId de outra squad: 403 e nenhuma consulta de sessões")
    void liveForbidden() {
        when(squadAccessService.matchesSquad("SQX", "ana", "MEMBER")).thenReturn(false);
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> service.live("ana", "MEMBER", "SQX")).getStatusCode());
        verifyNoInteractions(ritualSessionService);
    }

    @Test
    @DisplayName("squadId própria não passa pelo SquadAccessService")
    void liveOwnSquad() {
        service.live("ana", "MEMBER", "sqa");
        verify(ritualSessionService).live(eq(List.of("sqa")), any());
        verifyNoInteractions(squadAccessService);
    }

    @Test
    @DisplayName("Histórico: ferramenta inexistente 404, sem acesso 403, sem squad 400, padrão = squad da sessão")
    void history() {
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.history("ana", "MEMBER", "xyz", "SQA", 10)).getStatusCode());

        when(squadAccessService.matchesSquad("SQX", "ana", "MEMBER")).thenReturn(false);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.history("ana", "MEMBER", "poker", "SQX", 10)).getStatusCode());
        verifyNoInteractions(ritualSessionService);

        service.history("ana", "MEMBER", "health-check", null, 5);
        verify(ritualSessionService).history(eq(Tool.HEALTH_CHECK), eq("SQA"), eq(5), any());

        when(audienceService.contextFor(eq("sem"), any())).thenReturn(Optional.of(new SquadAudienceService.UserContext(
                User.builder().id("sem").build(), null, List.of(), Set.of(), false)));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.history("sem", "MEMBER", "retro", null, 5)).getStatusCode());
    }

    @Test
    @DisplayName("Sessão inválida: 401")
    void unauthorized() {
        when(audienceService.contextFor(eq("x"), any())).thenReturn(Optional.empty());
        assertEquals(HttpStatus.UNAUTHORIZED,
                assertThrows(ResponseStatusException.class, () -> service.live("x", "MEMBER", null)).getStatusCode());
    }
}
