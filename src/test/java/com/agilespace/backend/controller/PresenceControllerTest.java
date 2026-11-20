package com.agilespace.backend.controller;

import com.agilespace.backend.domain.PokerRoom;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.security.JwtTokenUtil;
import com.agilespace.backend.service.BrainstormingService;
import com.agilespace.backend.service.HealthCheckService;
import com.agilespace.backend.service.PokerService;
import com.agilespace.backend.service.RetroCaller;
import com.agilespace.backend.service.RetroService;
import com.agilespace.backend.service.ShowcaseSessionService;
import com.agilespace.backend.websocket.PresenceModule;
import com.agilespace.backend.websocket.PresenceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PresenceControllerTest {

    private PresenceRegistry registry;
    private PokerService poker;
    private RetroService retro;
    private BrainstormingService brainstorming;
    private HealthCheckService healthCheck;
    private ShowcaseSessionService showcase;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        registry = new PresenceRegistry();
        poker = mock(PokerService.class);
        retro = mock(RetroService.class);
        brainstorming = mock(BrainstormingService.class);
        healthCheck = mock(HealthCheckService.class);
        showcase = mock(ShowcaseSessionService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new PresenceController(registry, poker, retro, brainstorming, healthCheck, showcase))
                .build();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder asUser(String path, String userId) {
        return get(path).requestAttr(JwtAuthenticationFilter.ATTR_USER_ID, userId)
                .requestAttr(JwtAuthenticationFilter.ATTR_USER_ROLE, "MEMBER");
    }

    // --- 401 ---

    @Test
    void withoutAuthenticatedUserIsUnauthorized() throws Exception {
        when(poker.getRoom("r1")).thenReturn(Optional.of(new PokerRoom()));
        mvc.perform(get("/api/presence/poker/r1")).andExpect(status().isUnauthorized());
    }

    @Test
    void jwtFilterRequiresTokenForPresenceRoutes() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(mock(JwtTokenUtil.class));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/presence/retro/b1"), response, new MockFilterChain());
        assertEquals(401, response.getStatus());
    }

    // --- 404 ---

    @Test
    void unknownModuleIsNotFound() throws Exception {
        mvc.perform(asUser("/api/presence/chat/r1", "ana")).andExpect(status().isNotFound());
        mvc.perform(asUser("/api/presence/POKER/r1", "ana")).andExpect(status().isNotFound());
    }

    @Test
    void roomThatDoesNotExistIsNotFoundInEveryModule() throws Exception {
        when(poker.getRoom("nada")).thenReturn(Optional.empty());
        when(retro.boardExists("nada")).thenReturn(false);
        when(brainstorming.boardExists("nada")).thenReturn(false);
        when(healthCheck.boardExists("nada")).thenReturn(false);
        when(showcase.sessionExists("nada")).thenReturn(false);

        for (PresenceModule m : PresenceModule.values()) {
            mvc.perform(asUser("/api/presence/" + m.path() + "/nada", "ana")).andExpect(status().isNotFound());
        }
    }

    @Test
    void absurdlyLongRoomIdIsNotFoundWithoutTouchingTheDatabase() throws Exception {
        mvc.perform(asUser("/api/presence/poker/" + "x".repeat(201), "ana")).andExpect(status().isNotFound());
        org.mockito.Mockito.verifyNoInteractions(poker);
    }

    // --- 403 ---

    @Test
    void retroBoardOfWhichTheCallerIsNotMemberIsForbidden() throws Exception {
        registry.join(PresenceModule.RETRO, "b1", "bia", "s1", null);
        when(retro.boardExists("b1")).thenReturn(true);
        when(retro.canAccessBoard(eq("b1"), any(RetroCaller.class))).thenReturn(false);

        mvc.perform(asUser("/api/presence/retro/b1", "intruso"))
                .andExpect(status().isForbidden());
    }

    // --- 200 ---

    @Test
    void retroMemberSeesWhoIsOnline() throws Exception {
        registry.join(PresenceModule.RETRO, "b1", "ana", "s1", null);
        registry.join(PresenceModule.RETRO, "b1", "ana", "s2", null);
        registry.join(PresenceModule.RETRO, "b1", "bia", "s3", null);
        when(retro.boardExists("b1")).thenReturn(true);
        when(retro.canAccessBoard(eq("b1"), eq(new RetroCaller("ana", "MEMBER")))).thenReturn(true);

        mvc.perform(asUser("/api/presence/retro/b1", "ana"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.module").value("retro"))
                .andExpect(jsonPath("$.roomId").value("b1"))
                .andExpect(jsonPath("$.online[0]").value("ana"))
                .andExpect(jsonPath("$.online[1]").value("bia"))
                .andExpect(jsonPath("$.online.length()").value(2))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void existingRoomWithNobodyConnectedIsOkAndEmpty() throws Exception {
        when(brainstorming.boardExists("b2")).thenReturn(true);

        mvc.perform(asUser("/api/presence/brainstorming/b2", "ana"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.online.length()").value(0))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void anyAuthenticatedUserCanReadPresenceOfLinkRooms() throws Exception {
        registry.join(PresenceModule.POKER, "p1", "ana", "s1", null);
        registry.join(PresenceModule.HEALTH_CHECK, "h1", "bia", "s2", null);
        registry.join(PresenceModule.SHOWCASE, "sh1", "caio", "s3", null);
        when(poker.getRoom("p1")).thenReturn(Optional.of(new PokerRoom()));
        when(healthCheck.boardExists("h1")).thenReturn(true);
        when(showcase.sessionExists("sh1")).thenReturn(true);

        mvc.perform(asUser("/api/presence/poker/p1", "qualquer")).andExpect(status().isOk())
                .andExpect(jsonPath("$.online[0]").value("ana"));
        mvc.perform(asUser("/api/presence/health-check/h1", "qualquer")).andExpect(status().isOk())
                .andExpect(jsonPath("$.module").value("health-check"))
                .andExpect(jsonPath("$.online[0]").value("bia"));
        mvc.perform(asUser("/api/presence/showcase/sh1", "qualquer")).andExpect(status().isOk())
                .andExpect(jsonPath("$.online[0]").value("caio"));
    }

    @Test
    void presenceOfAnotherModuleWithTheSameRoomIdDoesNotLeak() throws Exception {
        registry.join(PresenceModule.POKER, "same", "ana", "s1", null);
        when(brainstorming.boardExists("same")).thenReturn(true);

        mvc.perform(asUser("/api/presence/brainstorming/same", "bia"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }
}
