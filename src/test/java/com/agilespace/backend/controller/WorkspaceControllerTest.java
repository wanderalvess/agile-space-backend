package com.agilespace.backend.controller;

import com.agilespace.backend.domain.UserKanbanCard;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.WorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    private HttpServletRequest requestAsUser(String userId) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn(userId);
        return request;
    }

    @Test
    public void testGetKanbanCards() {
        when(service.getKanbanCards("u1")).thenReturn(Arrays.asList(new UserKanbanCard()));

        ResponseEntity<List<UserKanbanCard>> response = controller.getKanbanCards("u1", requestAsUser("u1"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
    }

    @Test
    public void testGetKanbanCardsOfAnotherUserIsForbidden() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getKanbanCards("u1", requestAsUser("u2")));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    public void testSaveKanbanCard() {
        UserKanbanCard card = new UserKanbanCard();
        when(service.saveKanbanCard(card)).thenReturn(card);

        ResponseEntity<UserKanbanCard> response = controller.saveKanbanCard("u1", card, requestAsUser("u1"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("u1", card.getUserId());
    }

    @Test
    public void testSaveKanbanCardIntoAnotherUserIsForbidden() {
        assertThrows(ResponseStatusException.class,
                () -> controller.saveKanbanCard("u1", new UserKanbanCard(), requestAsUser("u2")));
        verifyNoInteractions(service);
    }

    @Test
    public void testDeleteKanbanCardPassesCaller() {
        ResponseEntity<Void> response = controller.deleteKanbanCard("c1", requestAsUser("u1"));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(service).deleteKanbanCard("c1", "u1");
    }
}
