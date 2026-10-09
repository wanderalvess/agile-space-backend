package com.agilespace.backend.domain;

import jakarta.persistence.Embeddable;
import lombok.*;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class RetroColumnDef {

    private String id;
    private String title;
    private String theme;
    @com.fasterxml.jackson.annotation.JsonAlias("order")
    private Integer columnOrder; // Renomeado de order para columnOrder (palavra reservada)
}
