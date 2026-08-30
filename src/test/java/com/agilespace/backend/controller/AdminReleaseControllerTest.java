package com.agilespace.backend.controller;

import com.agilespace.backend.domain.AppRelease;
import com.agilespace.backend.dto.AppReleaseDTO;
import com.agilespace.backend.service.AppReleaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class AdminReleaseControllerTest {

    @Mock
    private AppReleaseService service;

    @InjectMocks
    private AdminReleaseController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    private AppRelease release(String tag, boolean published) {
        return AppRelease.builder().id("rel-1").tag(tag).isPublished(published).build();
    }

    @Test
    public void testGetAllReleasesUsesAdminListingIncludingDrafts() {
        when(service.getAllReleasesAdmin()).thenReturn(List.of(release("v4.0.0", true), release("v5.0.0-rc", false)));

        ResponseEntity<List<AppRelease>> response = controller.getAllReleases();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(2, response.getBody().size());
        verify(service).getAllReleasesAdmin();
        verify(service, never()).getPublishedReleases();
    }

    @Test
    public void testCreateReleaseDelegatesDto() {
        AppReleaseDTO dto = AppReleaseDTO.builder().tag("v5.0.0").title("Nova").build();
        when(service.createRelease(dto)).thenReturn(release("v5.0.0", true));

        assertEquals(HttpStatus.OK, controller.createRelease(dto).getStatusCode());
        verify(service).createRelease(dto);
    }

    @Test
    public void testCreateReleasePropagatesDuplicateTagError() {
        AppReleaseDTO dto = AppReleaseDTO.builder().tag("v4.0.0").build();
        when(service.createRelease(dto)).thenThrow(new IllegalArgumentException("tag duplicada"));

        assertThrows(IllegalArgumentException.class, () -> controller.createRelease(dto));
    }

    @Test
    public void testUpdateReleaseDelegatesIdAndDto() {
        AppReleaseDTO dto = AppReleaseDTO.builder().title("Titulo novo").build();
        when(service.updateRelease("rel-1", dto)).thenReturn(release("v4.0.0", true));

        assertEquals(HttpStatus.OK, controller.updateRelease("rel-1", dto).getStatusCode());
        verify(service).updateRelease("rel-1", dto);
    }

    @Test
    public void testUpdateReleasePropagatesNotFound() {
        when(service.updateRelease(anyString(), any())).thenThrow(new NoSuchElementException("nao encontrada"));

        assertThrows(NoSuchElementException.class,
                () -> controller.updateRelease("ghost", AppReleaseDTO.builder().build()));
    }

    @Test
    public void testDeleteReleaseReturnsNoContent() {
        assertEquals(HttpStatus.NO_CONTENT, controller.deleteRelease("rel-1").getStatusCode());
        verify(service).deleteRelease("rel-1");
    }

    @Test
    public void testDeleteReleasePropagatesNotFound() {
        doThrow(new NoSuchElementException("nao encontrada")).when(service).deleteRelease("ghost");

        assertThrows(NoSuchElementException.class, () -> controller.deleteRelease("ghost"));
    }

    @Test
    public void testImportLegacyReturnsCounters() {
        List<AppReleaseDTO> payload = List.of(AppReleaseDTO.builder().tag("v1.0.0").build());
        when(service.importLegacyReleases(payload)).thenReturn(Map.of("imported", 1, "skipped", 0, "total", 1));

        ResponseEntity<Map<String, Object>> response = controller.importLegacy(payload);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().get("imported"));
    }
}
