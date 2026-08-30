package com.agilespace.backend.service;

import com.agilespace.backend.domain.AppRelease;
import com.agilespace.backend.dto.AppReleaseDTO;
import com.agilespace.backend.repository.AppReleaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class AppReleaseServiceTest {

    @Mock
    private AppReleaseRepository repository;

    @InjectMocks
    private AppReleaseService service;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        lenient().when(repository.save(any(AppRelease.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AppRelease release(String tag) {
        return AppRelease.builder()
                .id("rel-1")
                .tag(tag)
                .title("Titulo")
                .type("minor")
                .isPublished(true)
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ---------- leituras ----------

    @Test
    public void testGetPublishedReleasesReturnsExistingWithoutSeeding() {
        when(repository.findByIsPublishedTrueOrderByCreatedAtDesc()).thenReturn(List.of(release("v4.0.0")));

        assertEquals(1, service.getPublishedReleases().size());
        verify(repository, never()).save(any());
    }

    @Test
    public void testGetPublishedReleasesSeedsWhenEmpty() {
        when(repository.findByIsPublishedTrueOrderByCreatedAtDesc())
                .thenReturn(List.of())
                .thenReturn(List.of(release("v4.0.0")));
        when(repository.count()).thenReturn(0L);
        when(repository.existsByTag(anyString())).thenReturn(false);

        assertEquals(1, service.getPublishedReleases().size());
        // O seed gravou releases padrao antes da segunda leitura.
        verify(repository, atLeastOnce()).save(any(AppRelease.class));
    }

    @Test
    public void testSeedIsSkippedWhenRepositoryAlreadyHasData() {
        when(repository.count()).thenReturn(9L);

        service.seedInitialReleasesIfEmpty();

        verify(repository, never()).save(any());
    }

    @Test
    public void testGetLatestReleaseDelegates() {
        when(repository.findFirstByIsPublishedTrueOrderByCreatedAtDesc()).thenReturn(Optional.of(release("v4.0.0")));

        assertEquals("v4.0.0", service.getLatestRelease().orElseThrow().getTag());
        verify(repository, never()).save(any());
    }

    @Test
    public void testGetReleaseByIdDelegates() {
        when(repository.findById("rel-1")).thenReturn(Optional.of(release("v4.0.0")));

        assertTrue(service.getReleaseById("rel-1").isPresent());
        assertTrue(service.getReleaseById("ghost").isEmpty());
    }

    // ---------- createRelease ----------

    @Test
    public void testCreateReleaseAppliesDefaultsAndTrims() {
        when(repository.existsByTag("v5.0.0")).thenReturn(false);

        AppRelease created = service.createRelease(AppReleaseDTO.builder()
                .tag("  v5.0.0  ")
                .title("  Nova versao  ")
                .build());

        assertEquals("v5.0.0", created.getTag());
        assertEquals("Nova versao", created.getTitle());
        assertEquals("patch", created.getType());
        assertEquals("Zap", created.getIconName());
        assertEquals("Admin", created.getCreatedBy());
        assertTrue(created.getIsPublished());
        assertNotNull(created.getId());
        assertTrue(created.getChanges().isEmpty());
    }

    @Test
    public void testCreateReleaseNormalizesTypeToLowerCase() {
        when(repository.existsByTag(anyString())).thenReturn(false);

        assertEquals("major", service.createRelease(
                AppReleaseDTO.builder().tag("v5.0.0").type("  MAJOR  ").build()).getType());
    }

    @Test
    public void testCreateReleaseRejectsBlankTag() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createRelease(AppReleaseDTO.builder().tag("   ").build()));
        assertThrows(IllegalArgumentException.class,
                () -> service.createRelease(AppReleaseDTO.builder().build()));
        verify(repository, never()).save(any());
    }

    @Test
    public void testCreateReleaseRejectsDuplicateTag() {
        when(repository.existsByTag("v4.0.0")).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> service.createRelease(AppReleaseDTO.builder().tag("v4.0.0").build()));
        verify(repository, never()).save(any());
    }

    // ---------- updateRelease ----------

    @Test
    public void testUpdateReleaseAppliesOnlyProvidedFields() {
        AppRelease existing = release("v4.0.0");
        existing.setDescription("descricao antiga");
        when(repository.findById("rel-1")).thenReturn(Optional.of(existing));

        AppRelease updated = service.updateRelease("rel-1", AppReleaseDTO.builder().title("Novo titulo").build());

        assertEquals("Novo titulo", updated.getTitle());
        assertEquals("descricao antiga", updated.getDescription());
        assertEquals("v4.0.0", updated.getTag());
        assertNotNull(updated.getUpdatedAt());
    }

    @Test
    public void testUpdateReleaseRejectsTagAlreadyUsedByAnotherRelease() {
        when(repository.findById("rel-1")).thenReturn(Optional.of(release("v4.0.0")));
        when(repository.existsByTag("v3.0.0")).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> service.updateRelease("rel-1", AppReleaseDTO.builder().tag("v3.0.0").build()));
        verify(repository, never()).save(any());
    }

    @Test
    public void testUpdateReleaseKeepingSameTagDoesNotTriggerDuplicateCheck() {
        when(repository.findById("rel-1")).thenReturn(Optional.of(release("v4.0.0")));

        AppRelease updated = service.updateRelease("rel-1", AppReleaseDTO.builder().tag("v4.0.0").build());

        assertEquals("v4.0.0", updated.getTag());
        verify(repository, never()).existsByTag(anyString());
    }

    @Test
    public void testUpdateReleaseUnknownIdThrows() {
        when(repository.findById("ghost")).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> service.updateRelease("ghost", AppReleaseDTO.builder().title("x").build()));
    }

    // ---------- deleteRelease ----------

    @Test
    public void testDeleteReleaseRemovesExisting() {
        when(repository.existsById("rel-1")).thenReturn(true);

        service.deleteRelease("rel-1");

        verify(repository).deleteById("rel-1");
    }

    @Test
    public void testDeleteReleaseUnknownIdThrows() {
        when(repository.existsById("ghost")).thenReturn(false);

        assertThrows(NoSuchElementException.class, () -> service.deleteRelease("ghost"));
        verify(repository, never()).deleteById(anyString());
    }

    // ---------- importLegacyReleases ----------

    @Test
    public void testImportLegacySkipsDuplicatesAndBlankTags() {
        when(repository.existsByTag("v1.0.0")).thenReturn(true);
        when(repository.existsByTag("v2.0.0")).thenReturn(false);

        Map<String, Object> result = service.importLegacyReleases(List.of(
                AppReleaseDTO.builder().tag("v2.0.0").title("Nova").build(),
                AppReleaseDTO.builder().tag("v1.0.0").title("Ja existe").build(),
                AppReleaseDTO.builder().tag("   ").title("Sem tag").build()));

        assertEquals(1, result.get("imported"));
        assertEquals(2, result.get("skipped"));
        assertEquals(3, result.get("total"));
    }

    @Test
    public void testImportLegacyMarksOriginAndKeepsChronologicalOrder() {
        when(repository.existsByTag(anyString())).thenReturn(false);

        service.importLegacyReleases(List.of(
                AppReleaseDTO.builder().tag("v2.0.0").build(),
                AppReleaseDTO.builder().tag("v1.0.0").build()));

        ArgumentCaptor<AppRelease> captor = ArgumentCaptor.forClass(AppRelease.class);
        verify(repository, times(2)).save(captor.capture());
        List<AppRelease> saved = captor.getAllValues();

        // A lista chega da mais nova para a mais antiga; a mais antiga e gravada primeiro
        // e recebe createdAt anterior para preservar a ordem do changelog.
        assertEquals("v1.0.0", saved.get(0).getTag());
        assertEquals("v2.0.0", saved.get(1).getTag());
        assertTrue(saved.get(0).getCreatedAt().isBefore(saved.get(1).getCreatedAt()));
        assertEquals("Legacy Import", saved.get(0).getCreatedBy());
    }

    @Test
    public void testImportLegacyEmptyListIsNoOp() {
        Map<String, Object> result = service.importLegacyReleases(List.of());

        assertEquals(0, result.get("imported"));
        assertEquals(0, result.get("total"));
        verify(repository, never()).save(any());
    }
}
