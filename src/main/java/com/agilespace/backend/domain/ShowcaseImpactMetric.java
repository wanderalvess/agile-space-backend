package com.agilespace.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShowcaseImpactMetric {

    @Column(name = "metric_field")
    private String field;

    @Column(name = "metric_value")
    private Double value;
}
