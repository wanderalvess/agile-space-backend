package com.agilespace.backend.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "showcase_tasks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShowcaseTask {

    @Id
    private String id;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "task_key")
    private String key;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "acceptance_criteria", columnDefinition = "TEXT")
    private String acceptanceCriteria;

    @Column(name = "task_type")
    private String type;

    private String status;

    private String priority;

    private Double points;

    @Column(name = "card_kind")
    private String cardKind;

    @Column(name = "chart_type")
    private String chartType;

    @Column(name = "chart_title")
    private String chartTitle;

    @Column(name = "chart_display")
    private String chartDisplay;

    private String assignee;

    @Column(columnDefinition = "TEXT")
    private String url;

    private String decision;

    @Column(name = "preparation_status")
    private String preparationStatus;

    @Column(columnDefinition = "TEXT")
    private String feedback;

    private String project;

    @Column(name = "version_suporte")
    private String versionSuporte;

    @Column(name = "version_master")
    private String versionMaster;

    @Column(name = "version_release")
    private String versionRelease;

    @Column(name = "version_develop")
    private String versionDevelop;

    @Column(name = "approved_at")
    private String approvedAt;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_by_name")
    private String decidedByName;

    @Column(name = "decided_at")
    private String decidedAt;

    @Embedded
    @Builder.Default
    private ShowcaseEvidence evidence = ShowcaseEvidence.builder().build();

    @ElementCollection
    @CollectionTable(name = "showcase_task_metrics", joinColumns = @JoinColumn(name = "task_id"))
    @OrderColumn(name = "metric_order")
    @Builder.Default
    private List<ShowcaseImpactMetric> metrics = new ArrayList<>();

    @Column(name = "task_order")
    private Integer order;

    // Controle otimista por card (docs/ceremony-review-api.md). Começa em 1 e sobe só quando o conteúdo muda. O cliente devolve
    // o que leu; null = cliente que não conhece o campo (a última gravação vence, como antes da V81).
    @Column(name = "card_version")
    private Long version;

    // Última mudança de conteúdo do card. Só leitura: o servidor carimba.
    @Column(name = "card_updated_at")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime updatedAt;

    // Montado pelo Service a partir de showcase_task_files. Só sai na resposta: o que o cliente
    // mandar aqui é ignorado, anexo só entra e sai pelos endpoints de arquivo.
    @Transient
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private List<ShowcaseTaskFile> attachments;

    // De onde veio o texto de Problema, Solução e Critérios quando o card nasceu da importação do Jira (showcase_task_origins).
    // Lido e gravado junto do card; null = o cliente não conhece o campo e nada muda. importedBy/importedAt são do servidor.
    @Transient
    private List<com.agilespace.backend.dto.ShowcaseFieldOriginDto> origins;

    /** Versão efetiva: linha sem versão (testes, objetos montados à mão) vale 1. */
    public long versionOrDefault() {
        return version != null ? version : 1L;
    }

    /** Cópia profunda do que o servidor guarda do card (evidência e métricas), para comparar ou regravar depois do delete. */
    public ShowcaseTask copy() {
        ShowcaseEvidence e = evidence != null ? evidence : ShowcaseEvidence.builder().build();
        return ShowcaseTask.builder()
                .id(id).sessionId(sessionId).key(key).title(title).description(description)
                .acceptanceCriteria(acceptanceCriteria).type(type).status(status).priority(priority).points(points)
                .cardKind(cardKind).chartType(chartType).chartTitle(chartTitle).chartDisplay(chartDisplay)
                .assignee(assignee).url(url).decision(decision).preparationStatus(preparationStatus).feedback(feedback)
                .project(project).versionSuporte(versionSuporte).versionMaster(versionMaster)
                .versionRelease(versionRelease).versionDevelop(versionDevelop)
                .approvedAt(approvedAt).decidedBy(decidedBy).decidedByName(decidedByName).decidedAt(decidedAt)
                .evidence(e.copy())
                .metrics(copyMetrics(metrics))
                .order(order).version(version).updatedAt(updatedAt)
                .build();
    }

    /** Copia para este card (gerenciado) o conteúdo editável de {@code o}: o que o PATCH pode mudar. Não mexe em id, ordem, autoria nem versão. */
    public void applyContentFrom(ShowcaseTask o) {
        this.key = o.key; this.title = o.title; this.description = o.description;
        this.acceptanceCriteria = o.acceptanceCriteria; this.type = o.type; this.status = o.status;
        this.priority = o.priority; this.points = o.points;
        this.cardKind = o.cardKind; this.chartType = o.chartType; this.chartTitle = o.chartTitle; this.chartDisplay = o.chartDisplay;
        this.assignee = o.assignee; this.url = o.url; this.decision = o.decision;
        this.preparationStatus = o.preparationStatus; this.feedback = o.feedback; this.project = o.project;
        this.versionSuporte = o.versionSuporte; this.versionMaster = o.versionMaster;
        this.versionRelease = o.versionRelease; this.versionDevelop = o.versionDevelop;
        this.evidence = (o.evidence != null ? o.evidence : ShowcaseEvidence.builder().build()).copy();
        if (this.metrics == null) {
            this.metrics = new ArrayList<>();
        }
        this.metrics.clear();
        this.metrics.addAll(copyMetrics(o.metrics));
    }

    private static List<ShowcaseImpactMetric> copyMetrics(List<ShowcaseImpactMetric> source) {
        List<ShowcaseImpactMetric> out = new ArrayList<>();
        if (source != null) {
            for (ShowcaseImpactMetric m : source) {
                out.add(ShowcaseImpactMetric.builder().field(m.getField()).value(m.getValue()).build());
            }
        }
        return out;
    }

    /**
     * Mesmo conteúdo editável (o que o PATCH pode mexer)? Texto em branco e null são a mesma coisa e "open" é o mesmo que sem
     * decisão. Não compara id, ordem, autoria da decisão, versão nem anexos (são do servidor).
     */
    public boolean sameContentAs(ShowcaseTask o) {
        if (o == null) return false;
        ShowcaseEvidence a = evidence != null ? evidence : ShowcaseEvidence.builder().build();
        ShowcaseEvidence b = o.evidence != null ? o.evidence : ShowcaseEvidence.builder().build();
        return eq(key, o.key) && eq(title, o.title) && eq(description, o.description)
                && eq(acceptanceCriteria, o.acceptanceCriteria) && eq(type, o.type) && eq(status, o.status)
                && eq(priority, o.priority) && Objects.equals(points, o.points)
                && eq(cardKind, o.cardKind) && eq(chartType, o.chartType) && eq(chartTitle, o.chartTitle)
                && eq(chartDisplay, o.chartDisplay) && eq(assignee, o.assignee) && eq(url, o.url)
                && eq(normalizedDecision(), o.normalizedDecision()) && eq(preparationStatus, o.preparationStatus)
                && eq(feedback, o.feedback) && eq(project, o.project)
                && eq(versionSuporte, o.versionSuporte) && eq(versionMaster, o.versionMaster)
                && eq(versionRelease, o.versionRelease) && eq(versionDevelop, o.versionDevelop)
                && a.sameContentAs(b) && sameMetrics(metrics, o.metrics);
    }

    /** Sem decisão = null, vazio ou "open". */
    public String normalizedDecision() {
        return decision == null || decision.isBlank() || "open".equals(decision) ? null : decision;
    }

    public boolean sameDecisionFieldsAs(ShowcaseTask o) {
        return o != null && eq(normalizedDecision(), o.normalizedDecision()) && eq(feedback, o.feedback);
    }

    private static boolean sameMetrics(List<ShowcaseImpactMetric> x, List<ShowcaseImpactMetric> y) {
        int nx = x == null ? 0 : x.size();
        int ny = y == null ? 0 : y.size();
        if (nx != ny) return false;
        for (int i = 0; i < nx; i++) {
            ShowcaseImpactMetric mx = x.get(i);
            ShowcaseImpactMetric my = y.get(i);
            if (!eq(mx.getField(), my.getField()) || !Objects.equals(mx.getValue(), my.getValue())) return false;
        }
        return true;
    }

    static boolean eq(String a, String b) {
        String x = a == null || a.isBlank() ? null : a;
        String y = b == null || b.isBlank() ? null : b;
        return Objects.equals(x, y);
    }
}
