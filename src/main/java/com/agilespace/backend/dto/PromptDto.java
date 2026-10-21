package com.agilespace.backend.dto;

import com.agilespace.backend.domain.Prompt;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Item da Biblioteca de IA como a tela o consome: todos os campos do domínio (superconjunto do JSON
 * antigo) mais o que depende de quem olha — favorite, canEdit (dono ou ADMIN) e canModerate (ADMIN).
 * Datas em ISO com Z. Visibilidade só "public" ou "private" (valores antigos viram "private").
 */
public record PromptDto(
        UUID id, String title, String description, String content, String type, String visibility,
        String status, String impact, String businessGoal, String targetAudience,
        String gemLink, String architectureLink,
        String authorId, String authorName, String authorRole, String authorSquad, String authorAvatar,
        Integer useCount, Integer forkCount, List<String> tags,
        Instant createdAt, Instant updatedAt,
        boolean favorite, boolean canEdit, boolean canModerate) {

    public static PromptDto of(Prompt p, boolean favorite, boolean canEdit, boolean canModerate) {
        return new PromptDto(
                p.getId(), p.getTitle(), p.getDescription(), p.getContent(), p.getType(),
                "public".equals(p.getVisibility()) ? "public" : "private",
                p.getStatus(), p.getImpact(), p.getBusinessGoal(), p.getTargetAudience(),
                p.getGemLink(), p.getArchitectureLink(),
                p.getAuthorId(), p.getAuthorName(), p.getAuthorRole(), p.getAuthorSquad(), p.getAuthorAvatar(),
                p.getUseCount() == null ? 0 : p.getUseCount(), p.getForkCount() == null ? 0 : p.getForkCount(),
                p.getTags() == null ? List.of() : p.getTags().stream().sorted().toList(),
                toInstant(p.getCreatedAt()), toInstant(p.getUpdatedAt()),
                favorite, canEdit, canModerate);
    }

    private static Instant toInstant(LocalDateTime t) {
        return t == null ? null : t.atZone(ZoneId.systemDefault()).toInstant();
    }
}
