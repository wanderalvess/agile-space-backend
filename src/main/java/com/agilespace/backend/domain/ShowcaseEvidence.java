package com.agilespace.backend.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShowcaseEvidence {

    @Column(columnDefinition = "TEXT")
    private String problem;

    @Column(columnDefinition = "TEXT")
    private String solution;

    @Column(name = "evidence_dev")
    private String dev;

    @Column(name = "evidence_qa")
    private String qa;

    @Column(columnDefinition = "TEXT")
    private String screenshot;

    @Column(columnDefinition = "TEXT")
    private String video;

    @Column(name = "evidence_preference")
    private String evidencePreference;

    @Column(name = "time_spent")
    private Double timeSpent;

    @Column(name = "time_estimate")
    private Double timeEstimate;

    @Column(name = "planned_dev")
    @JsonIgnore
    private String plannedDev;

    @Column(name = "planned_qa")
    @JsonIgnore
    private String plannedQa;

    @Column(name = "planned_tu")
    @JsonIgnore
    private String plannedTu;

    // O frontend envia/lê "planned" como objeto aninhado ({dev,qa,tu}), mas as colunas
    // são escalares (mesmo tratamento que SprintPlanningSettings dá a campos flat) —
    // essas duas pontes existem só pra casar o formato JSON, sem persistir "planned" em si.
    // As colunas flat ficam @JsonIgnore pra não duplicar o campo na resposta.
    public Planned getPlanned() {
        if (plannedDev == null && plannedQa == null && plannedTu == null) {
            return null;
        }
        return new Planned(plannedDev, plannedQa, plannedTu);
    }

    public void setPlanned(Planned planned) {
        if (planned != null) {
            this.plannedDev = planned.getDev();
            this.plannedQa = planned.getQa();
            this.plannedTu = planned.getTu();
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Planned {
        private String dev;
        private String qa;
        private String tu;
    }
}
