package com.agilespace.backend.controller;

import com.agilespace.backend.config.ErrorMessageAdvice;
import com.agilespace.backend.domain.ShowcaseTask;
import com.agilespace.backend.dto.ShowcasePresenterDto;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.ShowcaseJqlService;
import com.agilespace.backend.service.ShowcaseSessionService;
import com.agilespace.backend.service.SquadAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Rotas novas da Review (docs/ceremony-review-api.md) pela camada HTTP: caminhos, status e corpo de erro. */
@ExtendWith(MockitoExtension.class)
class ShowcaseSessionControllerCeremonyTest {

    @Mock private ShowcaseSessionService service;
    @Mock private SquadAccessService squadAccessService;
    @Mock private ShowcaseJqlService jqlService;
    @InjectMocks private ShowcaseSessionController controller;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ShowcaseJqlController jql = new ShowcaseJqlController(jqlService, squadAccessService);
        mvc = MockMvcBuilders.standaloneSetup(controller, jql).setControllerAdvice(new ErrorMessageAdvice()).build();
    }

    @Test
    void patchRouteSendsBodyAndCallerAndReturnsTheCard() throws Exception {
        ShowcaseTask card = ShowcaseTask.builder().id("c1").title("Novo").version(4L).updatedAt(LocalDateTime.of(2026, 10, 17, 10, 0)).build();
        when(service.patchCard(eq("s1"), eq("c1"), any(JsonNode.class), eq("ana"))).thenReturn(card);

        mvc.perform(patch("/api/showcase-sessions/s1/cards/c1").requestAttr(JwtAuthenticationFilter.ATTR_USER_ID, "ana")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":3,\"title\":\"Novo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("c1"))
                .andExpect(jsonPath("$.version").value(4));

        verify(service).patchCard(eq("s1"), eq("c1"), argThat(n -> n.get("version").asInt() == 3 && "Novo".equals(n.get("title").asText())), eq("ana"));
    }

    @Test
    void patchConflictBecomes409WithCodeAndCurrentCard() throws Exception {
        ShowcaseTask current = ShowcaseTask.builder().id("c1").title("Atual").version(5L).build();
        when(service.patchCard(any(), any(), any(), any())).thenThrow(new ShowcaseSessionService.CardVersionConflictException(current));

        mvc.perform(patch("/api/showcase-sessions/s1/cards/c1").requestAttr(JwtAuthenticationFilter.ATTR_USER_ID, "ana")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CARD_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.card.id").value("c1"))
                .andExpect(jsonPath("$.card.version").value(5))
                .andExpect(jsonPath("$.card.title").value("Atual"));
    }

    @Test
    void decisionRestrictedBecomes403WithCodeAndCardIds() throws Exception {
        when(service.patchCard(any(), any(), any(), any())).thenThrow(new ShowcaseSessionService.DecisionRestrictedException(List.of("c1")));

        mvc.perform(patch("/api/showcase-sessions/s1/cards/c1").requestAttr(JwtAuthenticationFilter.ATTR_USER_ID, "dev")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":3,\"decision\":\"approved\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DECISION_RESTRICTED"))
                .andExpect(jsonPath("$.cardIds[0]").value("c1"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void wholeSessionPostAlsoReturnsTheDecisionRestrictedBody() throws Exception {
        when(service.saveSession(any(), eq("dev"))).thenThrow(new ShowcaseSessionService.DecisionRestrictedException(List.of("c1", "c2")));

        mvc.perform(post("/api/showcase-sessions").requestAttr(JwtAuthenticationFilter.ATTR_USER_ID, "dev")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"R\",\"status\":\"active\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DECISION_RESTRICTED"))
                .andExpect(jsonPath("$.cardIds.length()").value(2));
    }

    @Test
    void presenterRoutes() throws Exception {
        ShowcasePresenterDto p = new ShowcasePresenterDto("ana", "Ana", "c1", "card", LocalDateTime.of(2026, 10, 17, 10, 0));
        when(service.publishPresenter("s1", "ana", "c1", "card")).thenReturn(p);
        when(service.currentPresenter("s1")).thenReturn(Optional.of(p));
        when(service.currentPresenter("vazia")).thenReturn(Optional.empty());

        mvc.perform(put("/api/showcase-sessions/s1/presenter").requestAttr(JwtAuthenticationFilter.ATTR_USER_ID, "ana")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cardId\":\"c1\",\"step\":\"card\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.userId").value("ana"))
                .andExpect(jsonPath("$.cardId").value("c1"));
        mvc.perform(get("/api/showcase-sessions/s1/presenter")).andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("Ana"));
        mvc.perform(get("/api/showcase-sessions/vazia/presenter")).andExpect(status().isNoContent());
        mvc.perform(delete("/api/showcase-sessions/s1/presenter").requestAttr(JwtAuthenticationFilter.ATTR_USER_ID, "ana"))
                .andExpect(status().isNoContent());
        verify(service).clearPresenter("s1", "ana");
    }

    @Test
    void presenterPutWithoutBodyIsAccepted() throws Exception {
        when(service.publishPresenter("s1", "ana", null, null)).thenReturn(new ShowcasePresenterDto("ana", "Ana", null, null, LocalDateTime.now()));

        mvc.perform(put("/api/showcase-sessions/s1/presenter").requestAttr(JwtAuthenticationFilter.ATTR_USER_ID, "ana"))
                .andExpect(status().isOk());
    }

    @Test
    void presenterForbiddenKeepsTheStandardErrorBody() throws Exception {
        when(service.publishPresenter(any(), any(), any(), any())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Só quem criou a Review."));

        mvc.perform(put("/api/showcase-sessions/s1/presenter").requestAttr(JwtAuthenticationFilter.ATTR_USER_ID, "dev")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Só quem criou a Review."));
    }

    // ---------- JQL ----------

    @Test
    void jqlLiteralPathIsNotCapturedByTheSessionIdRoute() throws Exception {
        when(jqlService.list("SQ1", "ana")).thenReturn(List.of());

        mvc.perform(get("/api/showcase-sessions/jql").param("squadId", "SQ1").requestAttr(JwtAuthenticationFilter.ATTR_USER_ID, "ana"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(squadAccessService).requireSquadReadAccess(eq("SQ1"), any());
        verify(service, never()).getSession(any());
    }

    @Test
    void jqlRequiresSquadReadAccess() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito")).when(squadAccessService).requireSquadReadAccess(eq("SQ9"), any());

        mvc.perform(get("/api/showcase-sessions/jql").param("squadId", "SQ9").requestAttr(JwtAuthenticationFilter.ATTR_USER_ID, "ana"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/showcase-sessions/jql").requestAttr(JwtAuthenticationFilter.ATTR_USER_ID, "ana")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"squadId\":\"SQ9\",\"name\":\"n\",\"jql\":\"j\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(jqlService);
    }

    @Test
    void jqlListWithoutSquadIs400() throws Exception {
        mvc.perform(get("/api/showcase-sessions/jql")).andExpect(status().isBadRequest());
    }

    @Test
    void jqlDeleteReturns204() throws Exception {
        mvc.perform(delete("/api/showcase-sessions/jql/abc").requestAttr(JwtAuthenticationFilter.ATTR_USER_ID, "ana"))
                .andExpect(status().isNoContent());

        verify(jqlService).delete("abc", "ana");
    }

    @Test
    void jqlCreatePassesBodyAndCaller() throws Exception {
        mvc.perform(post("/api/showcase-sessions/jql").requestAttr(JwtAuthenticationFilter.ATTR_USER_ID, "ana")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"squadId\":\"SQ1\",\"name\":\"Review\",\"jql\":\"project = X\",\"visibility\":\"SQUAD\"}"))
                .andExpect(status().isOk());

        verify(jqlService).create("SQ1", "Review", "project = X", "SQUAD", "ana");
    }
}
