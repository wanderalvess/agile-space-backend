package com.agilespace.backend.controller;

import com.agilespace.backend.domain.Prompt;
import com.agilespace.backend.dto.PromptDto;
import com.agilespace.backend.dto.PromptFacetsDto;
import com.agilespace.backend.dto.PromptPageDto;
import com.agilespace.backend.dto.PromptUsageWeekDto;
import com.agilespace.backend.dto.PromptVersionDto;
import com.agilespace.backend.repository.PromptFilter;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.PromptCaller;
import com.agilespace.backend.service.PromptService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PromptControllerV2Test {

    @Mock private PromptService service;
    private PromptController controller;
    private MockHttpServletRequest request;
    private final UUID id = UUID.randomUUID();
    private final PromptCaller caller = new PromptCaller("u1", "MEMBER");

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        controller = new PromptController(service, Validation.buildDefaultValidatorFactory().getValidator());
        request = new MockHttpServletRequest();
        request.setAttribute(JwtAuthenticationFilter.ATTR_USER_ID, "u1");
        request.setAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE, "MEMBER");
    }

    private ResponseEntity<Object> list(String type, String visibility, String status, String impact, String tag,
                                        Boolean favorites, String sort) {
        return controller.listPrompts("q", null, type, visibility, status, impact, tag, favorites, sort,
                PageRequest.of(0, 12), request);
    }

    @Test
    void withoutNewParamsKeepsLegacyBehaviourAndFormat() {
        var page = new PageImpl<>(List.of(new Prompt()));
        when(service.listPrompts(eq("q"), eq(null), any(), any())).thenReturn(page);

        ResponseEntity<Object> response = list(null, null, null, null, null, null, null);

        assertSame(page, response.getBody());
        verify(service, never()).searchPromptDtos(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void anyNewParamSwitchesToNewContractWithFiltersAndDefaultSize() {
        var dto = new PromptPageDto<PromptDto>(List.of(), 0, 24, 0, 0);
        when(service.searchPromptDtos(any(), any(), any(), anyInt(), anyInt())).thenReturn(dto);

        ResponseEntity<Object> response = list("skill", "private", "all", "high", "scrum", true, "az");

        assertSame(dto, response.getBody());
        ArgumentCaptor<PromptFilter> f = ArgumentCaptor.forClass(PromptFilter.class);
        verify(service).searchPromptDtos(f.capture(), eq("az"), eq(caller), eq(0), eq(24));
        assertEquals("skill", f.getValue().type());
        assertEquals("private", f.getValue().visibility());
        assertEquals("all", f.getValue().status());
        assertEquals("high", f.getValue().impact());
        assertEquals("scrum", f.getValue().tag());
        assertTrue(f.getValue().favorites());
        assertEquals("q", f.getValue().query());
        verify(service, never()).listPrompts(any(), any(), any(), any());
    }

    @Test
    void explicitSizeIsHonouredInNewContract() {
        request.setParameter("size", "10");
        when(service.searchPromptDtos(any(), any(), any(), anyInt(), anyInt())).thenReturn(new PromptPageDto<>(List.of(), 0, 10, 0, 0));
        controller.listPrompts(null, null, null, null, null, null, null, null, "recent", PageRequest.of(2, 10), request);
        verify(service).searchPromptDtos(any(), eq("recent"), any(), eq(2), eq(10));
    }

    @Test
    void facetsPassesFilters() {
        var facets = new PromptFacetsDto(1, Map.of(), List.of(), new PromptFacetsDto.Totals(1, 2, 3));
        when(service.facets(any(), any())).thenReturn(facets);
        assertSame(facets, controller.facets("q", null, "skill", "public", null, null, null, true, request).getBody());
        verify(service).facets(argThat(f -> f.favorites() && "skill".equals(f.type())), eq(caller));
    }

    @Test
    void favoriteAndUnfavoriteReturnState() {
        when(service.setFavorite(id, caller, true)).thenReturn(true);
        when(service.setFavorite(id, caller, false)).thenReturn(false);
        assertEquals(Map.of("favorite", true), controller.favorite(id, request).getBody());
        assertEquals(Map.of("favorite", false), controller.unfavorite(id, request).getBody());
    }

    @Test
    void favoriteOfHiddenItemIs404AndRequiresLogin() {
        when(service.setFavorite(eq(id), any(), eq(true))).thenThrow(new IllegalArgumentException("nope"));
        assertEquals(HttpStatus.NOT_FOUND, controller.favorite(id, request).getStatusCode());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.favorite(id, new MockHttpServletRequest()));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void versionsUsageAndRestoreEndpoints() {
        PromptVersionDto v = new PromptVersionDto(2, "t", "u1", "Ana", null, true, null, null, null, null, null, null);
        when(service.listVersions(eq(id), any())).thenReturn(List.of(v));
        when(service.getVersion(eq(id), eq(2), any())).thenReturn(v);
        when(service.getVersion(eq(id), eq(9), any())).thenThrow(new IllegalArgumentException("nope"));
        when(service.usageByWeek(eq(id), eq(8), any())).thenReturn(List.of(new PromptUsageWeekDto("2026-10-12", 1, 0)));

        assertEquals(1, controller.versions(id, request).getBody().size());
        assertEquals(2, controller.version(id, 2, request).getBody().versionNumber());
        assertEquals(HttpStatus.NOT_FOUND, controller.version(id, 9, request).getStatusCode());
        assertEquals("2026-10-12", controller.usage(id, 8, request).getBody().get(0).weekStart());

        when(service.restoreVersion(eq(id), eq(2), any())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> controller.restoreVersion(id, 2, request)).getStatusCode());
    }

    @Test
    void hiddenItemUsageAndVersionsAre404() {
        when(service.usageByWeek(eq(id), anyInt(), any())).thenThrow(new IllegalArgumentException("nope"));
        when(service.listVersions(eq(id), any())).thenThrow(new IllegalArgumentException("nope"));
        assertEquals(HttpStatus.NOT_FOUND, controller.usage(id, 8, request).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.versions(id, request).getStatusCode());
    }
}
