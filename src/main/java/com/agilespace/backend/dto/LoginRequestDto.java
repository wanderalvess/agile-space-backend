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
public class LoginRequestDto {

    // Aceita e-mail completo ou apenas o usuário (parte antes do "@").
    @NotBlank(message = "E-mail ou usuário é obrigatório")
    private String email;

    @NotBlank(message = "Senha é obrigatória")
    private String password;
}
