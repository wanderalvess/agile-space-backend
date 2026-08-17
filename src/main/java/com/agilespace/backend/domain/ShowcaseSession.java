package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "showcase_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShowcaseSession {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(name = "sprint_name")
    private String sprintName;

    @Column(nullable = false)
    private String status; // planning, active, finished, etc.

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "cover_image")
    private String coverImage;

    @Column(name = "squad_name")
    private String squadName;

    private String period;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "default_sort")
    private String defaultSort;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Object> tasks; // Lista rica de ShowcaseTask contendo evidências, aceites e feedbacks

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Object> members; // Participantes/membros vinculados
}
