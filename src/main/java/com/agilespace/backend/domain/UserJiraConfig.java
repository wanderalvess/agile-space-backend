package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "user_jira_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserJiraConfig {

    @Id
    @Column(name = "user_id")
    private String userId; // Mesmo id do usuário (relacionamento 1:1)

    @Column(nullable = false)
    private String token;

    @Column(nullable = false)
    private String domain;
}
