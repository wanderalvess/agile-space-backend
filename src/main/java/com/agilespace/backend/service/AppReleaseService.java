package com.agilespace.backend.service;

import com.agilespace.backend.domain.AppRelease;
import com.agilespace.backend.dto.AppReleaseDTO;
import com.agilespace.backend.repository.AppReleaseRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class AppReleaseService {

    @Autowired
    private AppReleaseRepository repository;

    @Transactional(readOnly = true)
    public List<AppRelease> getPublishedReleases() {
        return repository.findByIsPublishedTrueOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public Optional<AppRelease> getLatestRelease() {
        return repository.findFirstByIsPublishedTrueOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<AppRelease> getAllReleasesAdmin() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public Optional<AppRelease> getReleaseById(String id) {
        return repository.findById(id);
    }

    @Transactional
    public AppRelease createRelease(AppReleaseDTO dto) {
        if (dto.getTag() == null || dto.getTag().trim().isEmpty()) {
            throw new IllegalArgumentException("A tag da versão é obrigatória.");
        }

        if (repository.existsByTag(dto.getTag().trim())) {
            throw new IllegalArgumentException("Já existe uma versão cadastrada com a tag " + dto.getTag());
        }

        AppRelease release = AppRelease.builder()
                .id(UUID.randomUUID().toString())
                .tag(dto.getTag().trim())
                .title(dto.getTitle() != null ? dto.getTitle().trim() : "")
                .description(dto.getDescription() != null ? dto.getDescription().trim() : "")
                .changes(dto.getChanges() != null ? new ArrayList<>(dto.getChanges()) : new ArrayList<>())
                .type(dto.getType() != null ? dto.getType().toLowerCase().trim() : "patch")
                .iconName(dto.getIconName() != null ? dto.getIconName().trim() : "Zap")
                .iconClass(dto.getIconClass() != null ? dto.getIconClass().trim() : "h-5 w-5 text-indigo-500")
                .displayDate(dto.getDisplayDate() != null ? dto.getDisplayDate().trim() : "")
                .isPublished(dto.getIsPublished() != null ? dto.getIsPublished() : true)
                .createdBy(dto.getCreatedBy() != null ? dto.getCreatedBy().trim() : "Admin")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        return repository.save(release);
    }

    @Transactional
    public AppRelease updateRelease(String id, AppReleaseDTO dto) {
        AppRelease existing = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Versão não encontrada com id: " + id));

        if (dto.getTag() != null && !dto.getTag().trim().equals(existing.getTag())) {
            if (repository.existsByTag(dto.getTag().trim())) {
                throw new IllegalArgumentException("Já existe uma versão cadastrada com a tag " + dto.getTag());
            }
            existing.setTag(dto.getTag().trim());
        }

        if (dto.getTitle() != null) existing.setTitle(dto.getTitle().trim());
        if (dto.getDescription() != null) existing.setDescription(dto.getDescription().trim());
        if (dto.getChanges() != null) existing.setChanges(new ArrayList<>(dto.getChanges()));
        if (dto.getType() != null) existing.setType(dto.getType().toLowerCase().trim());
        if (dto.getIconName() != null) existing.setIconName(dto.getIconName().trim());
        if (dto.getIconClass() != null) existing.setIconClass(dto.getIconClass().trim());
        if (dto.getDisplayDate() != null) existing.setDisplayDate(dto.getDisplayDate().trim());
        if (dto.getIsPublished() != null) existing.setIsPublished(dto.getIsPublished());

        existing.setUpdatedAt(LocalDateTime.now());
        return repository.save(existing);
    }

    @Transactional
    public void deleteRelease(String id) {
        if (!repository.existsById(id)) {
            throw new NoSuchElementException("Versão não encontrada com id: " + id);
        }
        repository.deleteById(id);
    }

    @Transactional
    public Map<String, Object> importLegacyReleases(List<AppReleaseDTO> dtos) {
        int imported = 0;
        int skipped = 0;

        // Inverter ou ordenar para que as mais antigas recebam createdAt anterior caso queira manter a ordem cronológica
        // Dtos geralmente vem do mais novo para o mais antigo.
        // Vamos iterar e definir createdAt decrementado para manter ordem exata
        LocalDateTime baseTime = LocalDateTime.now();

        for (int i = dtos.size() - 1; i >= 0; i--) {
            AppReleaseDTO dto = dtos.get(i);
            if (dto.getTag() == null || dto.getTag().trim().isEmpty()) {
                skipped++;
                continue;
            }

            String tag = dto.getTag().trim();
            if (repository.existsByTag(tag)) {
                skipped++;
                continue;
            }

            LocalDateTime releaseTime = baseTime.minusSeconds((long) (dtos.size() - 1 - i) * 60);

            AppRelease release = AppRelease.builder()
                    .id(UUID.randomUUID().toString())
                    .tag(tag)
                    .title(dto.getTitle() != null ? dto.getTitle() : "")
                    .description(dto.getDescription() != null ? dto.getDescription() : "")
                    .changes(dto.getChanges() != null ? new ArrayList<>(dto.getChanges()) : new ArrayList<>())
                    .type(dto.getType() != null ? dto.getType() : "patch")
                    .iconName(dto.getIconName() != null ? dto.getIconName() : "Zap")
                    .iconClass(dto.getIconClass() != null ? dto.getIconClass() : "h-5 w-5 text-indigo-500")
                    .displayDate(dto.getDisplayDate() != null ? dto.getDisplayDate() : "")
                    .isPublished(dto.getIsPublished() != null ? dto.getIsPublished() : true)
                    .createdBy("Legacy Import")
                    .createdAt(releaseTime)
                    .updatedAt(releaseTime)
                    .build();

            repository.save(release);
            imported++;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("imported", imported);
        result.put("skipped", skipped);
        result.put("total", dtos.size());
        return result;
    }
}
