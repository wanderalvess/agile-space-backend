package com.agilespace.backend.domain;

import com.agilespace.backend.security.CryptoConverter;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "user_tdn_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserTdnConfig {

    @Id
    @Column(name = "user_id")
    private String userId; // Mesmo id do usuário (relacionamento 1:1)

    @Convert(converter = CryptoConverter.class)
    @Column(nullable = false, length = 1000)
    private String token;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    @Column
    private String space;

    @Column
    private String label;
}
