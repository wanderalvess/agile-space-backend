package com.agilespace.backend.controller;

import com.agilespace.backend.domain.Prompt;
import com.agilespace.backend.service.PromptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PublicPromptHubControllerTest {

    @Mock private PromptService service;
    private PublicPromptHubController controller;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        controller = new PublicPromptHubController(service);
    }

    private Prompt prompt(String visibility) {
        return Prompt.builder().id(UUID.randomUUID()).title("t").visibility(visibility)
                .authorId("uid-interno").authorName("Ana").build();
    }

    @Test
    void listUsesPublicOnlyQueryAndCapsPageSize() {
        when(service.listPublicPrompts(any(), eq(null), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(prompt("public"))));

        var response = controller.list("  scrum  ", 0, 5000);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(service).listPublicPrompts(eq("scrum"), eq(null), captor.capture());
        assertEquals(50, captor.getValue().getPageSize());
        verify(service, never()).listPrompts(any(), any(), any(), any());
    }

    @Test
    void getPublicItemReturnsDtoWithoutInternalAuthorId() {
        Prompt p = prompt("public");
        when(service.getPromptById(p.getId())).thenReturn(p);

        var response = controller.get(p.getId());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Ana", response.getBody().authorName());
        assertTrue(java.util.Arrays.stream(PublicPromptHubController.PublicPromptView.class.getRecordComponents())
                .noneMatch(c -> c.getName().equalsIgnoreCase("authorId")));
    }

    @Test
    void privateAndMissingBothAre404() {
        Prompt priv = prompt("private");
        when(service.getPromptById(priv.getId())).thenReturn(priv);
        UUID missing = UUID.randomUUID();
        when(service.getPromptById(missing)).thenThrow(new IllegalArgumentException("nope"));

        assertEquals(HttpStatus.NOT_FOUND, controller.get(priv.getId()).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.get(missing).getStatusCode());
    }

    @Test
    void controllerHasNoWriteEndpoints() {
        for (Method m : PublicPromptHubController.class.getDeclaredMethods()) {
            assertNull(m.getAnnotation(PostMapping.class), m.getName());
            assertNull(m.getAnnotation(PutMapping.class), m.getName());
            assertNull(m.getAnnotation(DeleteMapping.class), m.getName());
            assertNull(m.getAnnotation(PatchMapping.class), m.getName());
        }
        assertNotNull(PublicPromptHubController.class.getDeclaredMethods().length > 0 ? 1 : null);
        assertTrue(java.util.Arrays.stream(PublicPromptHubController.class.getDeclaredMethods())
                .anyMatch(m -> m.getAnnotation(GetMapping.class) != null));
    }
}
