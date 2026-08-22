package com.agilespace.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
    @Size(min = 8, max = 128, message = "Senha deve ter entre 8 e 128 caracteres")
    private String password;

    private String jiraAccountId;
    private String defaultProjectId;
    private String segmentName;
    private String tribeName;
}
