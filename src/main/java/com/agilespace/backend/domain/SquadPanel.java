package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "squad_panels")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SquadPanel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String squadId;

    @Column(nullable = false)
    private String ownerId;

    @Column(nullable = false)
    private String name;

    private String type;

    @Column(columnDefinition = "TEXT")
    private String config;

    private String visibility;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
