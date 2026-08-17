package com.agilespace.backend.controller;

import com.agilespace.backend.domain.Prompt;
import com.agilespace.backend.domain.PromptComment;
import com.agilespace.backend.service.PromptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class PromptControllerTest {

    @Mock
    private PromptService service;

    @InjectMocks
    private PromptController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testListPrompts() {
        Page<Prompt> page = new PageImpl<>(Arrays.asList(new Prompt()));
        when(service.listPrompts(eq("query"), eq("u1"), any(PageRequest.class))).thenReturn(page);
        
        ResponseEntity<Page<Prompt>> response = controller.listPrompts("query", "u1", PageRequest.of(0, 12));
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testGetPromptByIdFound() {
        UUID id = UUID.randomUUID();
        when(service.getPromptById(id)).thenReturn(new Prompt());
        
        ResponseEntity<Prompt> response = controller.getPromptById(id);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testCreatePrompt() {
        Prompt prompt = new Prompt();
        when(service.createPrompt(prompt)).thenReturn(prompt);
        
        ResponseEntity<Prompt> response = controller.createPrompt(prompt);
        
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
    }

    @Test
    public void testUpdatePrompt() {
        UUID id = UUID.randomUUID();
        Prompt prompt = new Prompt();
        when(service.updatePrompt(id, prompt)).thenReturn(prompt);
        
        ResponseEntity<Prompt> response = controller.updatePrompt(id, prompt);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testDeletePrompt() {
        UUID id = UUID.randomUUID();
        doNothing().when(service).deletePrompt(id);
        
        ResponseEntity<Void> response = controller.deletePrompt(id);
        
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    public void testAddComment() {
        UUID id = UUID.randomUUID();
        PromptComment comment = new PromptComment();
        when(service.addComment(id, comment)).thenReturn(comment);
        
        ResponseEntity<PromptComment> response = controller.addComment(id, comment);
        
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
    }
}
