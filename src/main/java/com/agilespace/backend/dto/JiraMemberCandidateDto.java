package com.agilespace.backend.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JiraMemberCandidateDto {
    private String jiraAccountId;
    private String displayName;
    private String email;
    /** Foto do usuário no Jira (URL); a importação do onboarding a converte em imagem embutida. */
    private String avatarUrl;
    private String role;
    private int score;
    @Builder.Default
    private boolean selected = true;
    @Builder.Default
    private Double capacityHoursPerDay = 8.0;
}
