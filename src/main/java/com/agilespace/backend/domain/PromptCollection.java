package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "prompt_collections")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromptCollection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 50)
    private String visibility;

    @Column(nullable = false)
    private String ownerId;
    
    private String ownerName;

    @ManyToMany
    @JoinTable(
            name = "prompt_collection_items",
            joinColumns = @JoinColumn(name = "collection_id"),
            inverseJoinColumns = @JoinColumn(name = "prompt_id")
    )
    @OrderColumn(name = "order_index")
    @Builder.Default
    private List<Prompt> items = new ArrayList<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
