package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "brainstorming_groups")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BrainstormingGroup {

    @Id
    private String id; // UUID do grupo

    @Column(name = "board_id", nullable = false)
    private String boardId;

    private String title;

    @Column(name = "group_order") // Desvia de palavra reservada 'order'
    private Integer order;

    private String createdAt;

    private String color;
}
