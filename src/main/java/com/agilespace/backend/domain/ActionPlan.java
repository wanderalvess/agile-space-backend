package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "action_plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String creatorId;

    @Column(nullable = false)
    private String title;

    private String team;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @Builder.Default
    private Boolean isPublic = true;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "action_plan_participants", joinColumns = @JoinColumn(name = "board_id"))
    @Column(name = "participant_id")
    @Builder.Default
    private Set<String> participantIds = new HashSet<>();
}
