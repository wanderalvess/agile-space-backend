package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "showcase_members")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShowcaseMember {

    @Id
    private String id;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(nullable = false)
    private String name;

    private String role;

    private String avatar;

    @Column(name = "member_order")
    private Integer order;
}
