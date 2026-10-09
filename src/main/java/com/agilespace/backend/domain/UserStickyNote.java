package com.agilespace.backend.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_sticky_notes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserStickyNote {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(nullable = false)
    private String color;

    @Column(name = "is_pinned", nullable = false)
    private boolean isPinned;

    // Lombok gera isPinned()/setPinned(), que o Jackson lê como "pinned" — mas o frontend usa
    // "isPinned". Sem isto a nota fixada nunca voltava fixada depois de recarregar.
    @JsonProperty("isPinned")
    @JsonAlias("pinned")
    public boolean isPinned() {
        return isPinned;
    }

    @JsonProperty("isPinned")
    @JsonAlias("pinned")
    public void setPinned(boolean pinned) {
        this.isPinned = pinned;
    }

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
