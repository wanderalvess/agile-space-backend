package com.agilespace.backend;

import com.agilespace.backend.domain.Feedback;
import com.agilespace.backend.repository.FeedbackRepository;
import com.agilespace.backend.controller.FeedbackController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class FeedbackControllerTest {

    @Mock
    private FeedbackRepository repository;

    @InjectMocks
    private FeedbackController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testSaveFeedbackGeneratesIdAndTimestamp() {
        Feedback feedback = Feedback.builder()
                .toolName("FocusTimer")
                .score(5)
                .comment("Excelente ferramenta!")
                .userId("user-123")
                .build();

        when(repository.save(any(Feedback.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<Feedback> response = controller.saveFeedback(feedback);
        Feedback saved = response.getBody();

        assertNotNull(saved);
        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        assertEquals("FocusTimer", saved.getToolName());
        assertEquals(5, saved.getScore());
        verify(repository, times(1)).save(feedback);
    }

    @Test
    public void testGetAllFeedbacks() {
        Feedback f1 = Feedback.builder().id("1").toolName("Vault").score(4).build();
        Feedback f2 = Feedback.builder().id("2").toolName("Showcase").score(5).build();

        when(repository.findByOrderByCreatedAtDesc()).thenReturn(Arrays.asList(f1, f2));

        ResponseEntity<List<Feedback>> response = controller.getAllFeedbacks();
        List<Feedback> list = response.getBody();

        assertNotNull(list);
        assertEquals(2, list.size());
        verify(repository, times(1)).findByOrderByCreatedAtDesc();
    }
}
