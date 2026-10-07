package com.agilespace.backend.controller;

import com.agilespace.backend.domain.AppRelease;
import com.agilespace.backend.service.AppReleaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ChangelogControllerTest {

    @Mock
    private AppReleaseService service;

    @InjectMocks
    private ChangelogController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    private AppRelease release(String tag) {
        return AppRelease.builder().id("rel-1").tag(tag).isPublished(true).build();
    }

    @Test
    public void testGetChangelogExposesOnlyPublishedReleases() {
        when(service.getPublishedReleases()).thenReturn(List.of(release("v4.0.0")));

        ResponseEntity<List<AppRelease>> response = controller.getChangelog();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        // O endpoint publico nunca pode cair no listador de admin (que inclui rascunhos).
        verify(service).getPublishedReleases();
        verify(service, never()).getAllReleasesAdmin();
    }

    @Test
    public void testGetChangelogEmpty() {
        when(service.getPublishedReleases()).thenReturn(List.of());

        assertTrue(controller.getChangelog().getBody().isEmpty());
    }

    @Test
    public void testGetLatestReturnsRelease() {
        when(service.getLatestRelease()).thenReturn(Optional.of(release("v4.0.0")));

        ResponseEntity<AppRelease> response = controller.getLatest();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("v4.0.0", response.getBody().getTag());
    }

    @Test
    public void testGetLatestReturnsNotFoundWhenNoRelease() {
        when(service.getLatestRelease()).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller.getLatest().getStatusCode());
    }

    @Test
    public void testGetByIdReturnsRelease() {
        when(service.getReleaseById("rel-1")).thenReturn(Optional.of(release("v4.0.0")));

        assertEquals(HttpStatus.OK, controller.getById("rel-1").getStatusCode());
    }

    @Test
    public void testGetByIdUnknownReturnsNotFound() {
        when(service.getReleaseById("ghost")).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller.getById("ghost").getStatusCode());
    }
}
