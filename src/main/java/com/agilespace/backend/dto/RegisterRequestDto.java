package com.agilespace.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequestDto {

    @NotBlank(message = "E-mail corporativo é obrigatório")
    private String email;

    @NotBlank(message = "Nome completo é obrigatório")
    private String name;

    @NotBlank(message = "Senha é obrigatória")
    private String password;

    private String jiraAccountId;
    private String defaultProjectId;
    private String segmentName;
    private String tribeName;
}
