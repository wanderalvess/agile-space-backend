package com.agilespace.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

/** Participante gravado ao finalizar a Review (V81). Sem relação JPA, como as demais filhas da sessão. */
@Entity
@Table(name = "showcase_participants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShowcaseParticipant {

    @Id
    private String id;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
