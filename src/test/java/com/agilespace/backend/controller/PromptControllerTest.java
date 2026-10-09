package com.agilespace.backend.controller;

import com.agilespace.backend.domain.Prompt;
import com.agilespace.backend.domain.PromptComment;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.PromptCaller;
import com.agilespace.backend.service.PromptService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class PromptControllerTest {

    @Mock
    private PromptService service;

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private PromptController controller;
    private MockHttpServletRequest request;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        controller = new PromptController(service, validator);
        request = new MockHttpServletRequest();
        request.setAttribute(JwtAuthenticationFilter.ATTR_USER_ID, "u1");
        request.setAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE, "MEMBER");
    }

    @Test
    public void testListPromptsUsesJwtIdentity() {
        Page<Prompt> page = new PageImpl<>(Arrays.asList(new Prompt()));
        when(service.listPrompts(eq("query"), eq("u1"), eq(new PromptCaller("u1", "MEMBER")), any(PageRequest.class)))
                .thenReturn(page);

        ResponseEntity<Page<Prompt>> response = controller.listPrompts("query", "u1", PageRequest.of(0, 12), request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testWithoutIdentityIsUnauthorized() {
        MockHttpServletRequest anonymous = new MockHttpServletRequest();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listPrompts(null, null, PageRequest.of(0, 12), anonymous));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    public void testGetPromptByIdHiddenIs404() {
        UUID id = UUID.randomUUID();
        when(service.getVisiblePrompt(eq(id), any())).thenThrow(new IllegalArgumentException("nope"));

        assertEquals(HttpStatus.NOT_FOUND, controller.getPromptById(id, request).getStatusCode());
    }

    @Test
    public void testCreatePromptPassesCaller() {
        Prompt prompt = new Prompt();
        when(service.createPrompt(eq(prompt), any(PromptCaller.class))).thenReturn(prompt);

        ResponseEntity<Prompt> response = controller.createPrompt(prompt, request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        ArgumentCaptor<PromptCaller> captor = ArgumentCaptor.forClass(PromptCaller.class);
        verify(service).createPrompt(eq(prompt), captor.capture());
        assertEquals("u1", captor.getValue().id());
    }

    @Test
    public void testUpdatePrompt() {
        UUID id = UUID.randomUUID();
        Prompt prompt = new Prompt();
        when(service.updatePrompt(eq(id), eq(prompt), any(PromptCaller.class))).thenReturn(prompt);

        assertEquals(HttpStatus.OK, controller.updatePrompt(id, prompt, request).getStatusCode());
    }

    @Test
    public void testDeletePrompt() {
        UUID id = UUID.randomUUID();

        assertEquals(HttpStatus.NO_CONTENT, controller.deletePrompt(id, request).getStatusCode());
        verify(service).deletePrompt(eq(id), any(PromptCaller.class));
    }

    @Test
    public void testForbiddenFromServicePropagates() {
        UUID id = UUID.randomUUID();
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(service).deletePrompt(eq(id), any());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.deletePrompt(id, request));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    public void testAddComment() {
        UUID id = UUID.randomUUID();
        PromptComment comment = new PromptComment();
        when(service.addComment(eq(id), eq(comment), any(PromptCaller.class))).thenReturn(comment);

        assertEquals(HttpStatus.CREATED, controller.addComment(id, comment, request).getStatusCode());
    }

    @Test
    public void testDeleteComment() {
        UUID id = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();

        assertEquals(HttpStatus.NO_CONTENT, controller.deleteComment(id, commentId, request).getStatusCode());
        verify(service).deleteComment(eq(id), eq(commentId), any(PromptCaller.class));
    }

    @Test
    public void testBatchRejectsInvalidItem() {
        Prompt invalid = Prompt.builder().title("").build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.createPromptsBatch(List.of(invalid), request));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(service, never()).createPromptsBatch(any(), any(PromptCaller.class));
    }

    @Test
    public void testEntityLimitsAreEnforced() {
        Prompt tooLong = Prompt.builder().title("t").businessGoal("x".repeat(256)).authorId("u1").build();
        assertFalse(validator.validate(tooLong).isEmpty());
        assertFalse(validator.validate(Prompt.builder().title("  ").authorId("u1").build()).isEmpty());
    }
}
