package com.agilespace.backend.domain;

import com.agilespace.backend.dto.ShowcaseParticipantDto;
import com.agilespace.backend.dto.ShowcasePresenterDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "showcase_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShowcaseSession {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(name = "sprint_name")
    private String sprintName;

    @Column(nullable = false)
    private String status; // planning, active, finished, etc.

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // Carimbado a cada escrita real na sessão — usado como proxy de "tempo de uso"
    // (updatedAt - createdAt) na aba executiva do /admin. Nenhum job/sync toca essa
    // tabela em background, então é sinal de atividade real, não ruído.
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "cover_image", columnDefinition = "TEXT")
    private String coverImage;

    @Column(name = "squad_name")
    private String squadName;

    private String period;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "default_sort")
    private String defaultSort;

    // Fundo (cor, gradiente CSS ou caminho de imagem) e tema do Modo Teatro.
    @Column(name = "presentation_background", columnDefinition = "TEXT")
    private String presentationBackground;

    @Column(name = "presentation_theme", length = 32)
    private String presentationTheme;

    // Checklist "Guia de Elite" da tela inicial da Review (um booleano por item).
    @Convert(converter = BooleanListConverter.class)
    @Column(name = "readiness_checklist", columnDefinition = "TEXT")
    private List<Boolean> readinessChecklist;

    @Transient
    private List<ShowcaseTask> tasks; // Montado pelo Service a partir de showcase_tasks

    @Transient
    private List<ShowcaseMember> members; // Montado pelo Service a partir de showcase_members

    // --- Só leitura (docs/ceremony-review-api.md): montados pelo Service, o que o cliente mandar aqui é ignorado ---

    /** Quem esteve na sala quando a Review foi finalizada. Lista vazia se não finalizou ou é anterior à V81. */
    @Transient
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private List<ShowcaseParticipantDto> participants;

    /** Quem está apresentando agora (em memória); ausente = ninguém. */
    @Transient
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ShowcasePresenterDto presenter;

    /** Só na resposta do POST: cards em que o cliente tinha versão velha e o servidor manteve o dele. */
    @Transient
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> staleCardIds;
}
