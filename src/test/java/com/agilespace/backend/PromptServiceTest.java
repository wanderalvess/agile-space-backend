package com.agilespace.backend;

import com.agilespace.backend.domain.Prompt;
import com.agilespace.backend.domain.PromptComment;
import com.agilespace.backend.repository.PromptCommentRepository;
import com.agilespace.backend.repository.PromptRepository;
import com.agilespace.backend.service.PromptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class PromptServiceTest {

    @Mock
    private PromptRepository promptRepository;

    @Mock
    private PromptCommentRepository commentRepository;

    @InjectMocks
    private PromptService service;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testListPromptsWithQuery() {
        Page<Prompt> page = new PageImpl<>(Arrays.asList(new Prompt()));
        when(promptRepository.searchPublic(eq("test"), eq("public"), any(Pageable.class))).thenReturn(page);
        
        Page<Prompt> result = service.listPrompts("test", null, PageRequest.of(0, 10));
        assertEquals(1, result.getTotalElements());
    }

    @Test
    public void testListPromptsWithAuthor() {
        Page<Prompt> page = new PageImpl<>(Arrays.asList(new Prompt()));
        when(promptRepository.findByAuthorId(eq("u1"), any(Pageable.class))).thenReturn(page);
        
        Page<Prompt> result = service.listPrompts(null, "u1", PageRequest.of(0, 10));
        assertEquals(1, result.getTotalElements());
    }

    @Test
    public void testListPromptsPublic() {
        Page<Prompt> page = new PageImpl<>(Arrays.asList(new Prompt()));
        when(promptRepository.findByVisibility(eq("public"), any(Pageable.class))).thenReturn(page);
        
        Page<Prompt> result = service.listPrompts(null, null, PageRequest.of(0, 10));
        assertEquals(1, result.getTotalElements());
    }

    @Test
    public void testGetPromptById() {
        UUID promptId = UUID.randomUUID();
        Prompt prompt = Prompt.builder().id(promptId).title("Prompt Scrum Master").build();
        when(promptRepository.findById(promptId)).thenReturn(Optional.of(prompt));

        Prompt result = service.getPromptById(promptId);
        assertEquals("Prompt Scrum Master", result.getTitle());
    }

    @Test
    public void testGetPromptByIdNotFound() {
        UUID promptId = UUID.randomUUID();
        when(promptRepository.findById(promptId)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.getPromptById(promptId));
    }

    @Test
    public void testCreatePromptResetsCounters() {
        Prompt prompt = Prompt.builder().title("Novo Prompt").useCount(10).forkCount(5).build();
        when(promptRepository.save(any(Prompt.class))).thenAnswer(i -> i.getArgument(0));

        Prompt saved = service.createPrompt(prompt);
        assertEquals(0, saved.getUseCount());
        assertEquals(0, saved.getForkCount());
    }

    @Test
    public void testUpdatePrompt() {
        UUID promptId = UUID.randomUUID();
        Prompt existing = Prompt.builder().id(promptId).title("Old").build();
        Prompt update = Prompt.builder().title("New").build();
        
        when(promptRepository.findById(promptId)).thenReturn(Optional.of(existing));
        when(promptRepository.save(any(Prompt.class))).thenAnswer(i -> i.getArgument(0));

        Prompt updated = service.updatePrompt(promptId, update);
        assertEquals("New", updated.getTitle());
    }

    @Test
    public void testDeletePrompt() {
        UUID promptId = UUID.randomUUID();
        PromptComment c1 = new PromptComment();
        when(commentRepository.findByPromptIdOrderByCreatedAtAsc(promptId)).thenReturn(Arrays.asList(c1));
        
        service.deletePrompt(promptId);
        
        verify(commentRepository).deleteAll(any());
        verify(promptRepository).deleteById(promptId);
    }

    @Test
    public void testIncrementUseCount() {
        UUID promptId = UUID.randomUUID();
        Prompt prompt = Prompt.builder().id(promptId).useCount(5).build();
        when(promptRepository.findById(promptId)).thenReturn(Optional.of(prompt));
        when(promptRepository.save(any(Prompt.class))).thenAnswer(i -> i.getArgument(0));

        Prompt updated = service.incrementUseCount(promptId);
        assertEquals(6, updated.getUseCount());
    }

    @Test
    public void testIncrementForkCount() {
        UUID promptId = UUID.randomUUID();
        Prompt prompt = Prompt.builder().id(promptId).forkCount(2).build();
        when(promptRepository.findById(promptId)).thenReturn(Optional.of(prompt));
        when(promptRepository.save(any(Prompt.class))).thenAnswer(i -> i.getArgument(0));

        Prompt updated = service.incrementForkCount(promptId);
        assertEquals(3, updated.getForkCount());
    }

    @Test
    public void testAddComment() {
        UUID promptId = UUID.randomUUID();
        Prompt prompt = Prompt.builder().id(promptId).build();
        when(promptRepository.findById(promptId)).thenReturn(Optional.of(prompt));
        
        PromptComment comment = new PromptComment();
        when(commentRepository.save(any(PromptComment.class))).thenAnswer(i -> i.getArgument(0));

        PromptComment saved = service.addComment(promptId, comment);
        assertNotNull(saved.getPrompt());
    }

    @Test
    public void testGetComments() {
        UUID promptId = UUID.randomUUID();
        when(commentRepository.findByPromptIdOrderByCreatedAtAsc(promptId)).thenReturn(Arrays.asList(new PromptComment()));
        
        List<PromptComment> result = service.getComments(promptId);
        assertEquals(1, result.size());
    }
}
