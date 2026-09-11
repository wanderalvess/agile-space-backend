package com.agilespace.backend.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

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

    // --- Votes collection ---
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "retro_card_votes", joinColumns = @JoinColumn(name = "card_id"))
    @Column(name = "user_id")
    @Builder.Default
    private List<String> votes = new ArrayList<>();

    // --- Reações rápidas (independentes do voto de priorização) ---
    // Formato: { "up": ["uid1"], "love": [], "wow": [], "concern": ["uid2"] }
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reactions", columnDefinition = "jsonb")
    private JsonNode reactions;
}
