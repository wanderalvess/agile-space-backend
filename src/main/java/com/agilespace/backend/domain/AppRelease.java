package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "app_releases")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppRelease {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String tag;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "app_release_changes", joinColumns = @JoinColumn(name = "release_id"))
    @Column(name = "change_text", columnDefinition = "TEXT")
    @Builder.Default
    private List<String> changes = new ArrayList<>();

    @Column(nullable = false)
    private String type; // major, minor, patch

    @Column(name = "icon_name")
    private String iconName; // e.g. Sparkles, Zap, Rocket

    @Column(name = "icon_class")
    private String iconClass; // e.g. h-5 w-5 text-indigo-500

    @Column(name = "display_date", nullable = false)
    private String displayDate; // e.g. "17 de Agosto, 2026"

    @Column(name = "is_published", nullable = false)
    @Builder.Default
    private Boolean isPublished = true;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
