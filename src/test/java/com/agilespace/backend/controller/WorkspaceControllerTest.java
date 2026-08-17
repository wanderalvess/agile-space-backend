package com.agilespace.backend.controller;

import com.agilespace.backend.domain.UserKanbanCard;
import com.agilespace.backend.domain.UserQuickLink;
import com.agilespace.backend.domain.UserStickyNote;
import com.agilespace.backend.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class WorkspaceControllerTest {

    @Mock
    private WorkspaceService service;

    @InjectMocks
    private WorkspaceController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testGetKanbanCards() {
        when(service.getKanbanCards("u1")).thenReturn(Arrays.asList(new UserKanbanCard()));
        
        ResponseEntity<List<UserKanbanCard>> response = controller.getKanbanCards("u1");
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
    }

    @Test
    public void testSaveKanbanCard() {
        UserKanbanCard card = new UserKanbanCard();
        when(service.saveKanbanCard(card)).thenReturn(card);
        
        ResponseEntity<UserKanbanCard> response = controller.saveKanbanCard("u1", card);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("u1", card.getUserId());
    }

    @Test
    public void testDeleteKanbanCard() {
        doNothing().when(service).deleteKanbanCard("c1");
        
        ResponseEntity<Void> response = controller.deleteKanbanCard("c1");
        
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }
}
