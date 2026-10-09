package com.agilespace.backend.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "retro_cards")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetroCard {

    @Id
    private String id; // UUID gerado pelo criador

    // Trava otimista: gravações concorrentes do mesmo card viram 409 em vez de lost update.
    // Somente leitura no JSON — o cliente nunca dita a versão.
    @Version
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Long version;

    @Column(nullable = false)
    private String boardId;

    @Column(nullable = false, length = 50)
    private String columnKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private String authorId;

    @Column(name = "card_order")
    @Builder.Default
    private Long order = 0L; // Alterado para Long para suportar timestamps de milissegundos do JS (ex: Date.now())

    private String assignee;
    
    private String dueDate;

    @Builder.Default
    private Boolean isDone = false;

    private String parentId; // Para cartões agrupados

    private String carriedFromBoardId;

    private String carriedFromBoardTitle;

    // Quantas vezes esta ação foi reimportada sem ser concluída (sinal de tema recorrente)
    private Integer carryCount;

    // --- Votes collection ---
    // Set: o banco tem UNIQUE (card_id, user_id) e o Hibernate grava só o delta (insert/delete
    // por linha) em vez de apagar e reinserir a coleção inteira, como fazia com List (bag).
    // SUBSELECT: carregar N cards custa 1 query para os votos, não N.
    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @CollectionTable(name = "retro_card_votes", joinColumns = @JoinColumn(name = "card_id"))
    @Column(name = "user_id")
    @Builder.Default
    private Set<String> votes = new LinkedHashSet<>();

    // --- Reações rápidas (independentes do voto de priorização) ---
    // Formato: { "up": ["uid1"], "love": [], "wow": [], "concern": ["uid2"] }
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reactions", columnDefinition = "jsonb")
    private JsonNode reactions;

    // Histórico de ideias fundidas neste card (conteúdo das origens, na ordem
    // em que foram fundidas) — content nunca é reescrito numa fusão, só isso
    // aqui cresce. Renderizado como linha do tempo no frontend.
    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @CollectionTable(name = "retro_card_original_texts", joinColumns = @JoinColumn(name = "card_id"))
    @Column(name = "text", columnDefinition = "TEXT")
    @OrderColumn(name = "position")
    @Builder.Default
    private List<String> originalTexts = new ArrayList<>();
}
