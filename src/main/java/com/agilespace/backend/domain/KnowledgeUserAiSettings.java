package com.agilespace.backend.domain;

import com.agilespace.backend.security.CryptoConverter;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "knowledge_user_ai_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeUserAiSettings {

    @Id
    @Column(name = "user_id")
    private String userId; // Mesmo id do usuário (relacionamento 1:1)

    private String model;

    @Convert(converter = CryptoConverter.class)
    @Column(name = "byok_api_key", length = 1000)
    private String byokApiKey;

    private LocalDateTime updatedAt;
}
