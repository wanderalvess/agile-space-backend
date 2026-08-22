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
public class CreateProjectRequestDto {

    @NotBlank(message = "Chave do projeto é obrigatória")
    private String id; // Ex: "MEUTIME" — vira o identificador do projeto

    @NotBlank(message = "Nome do projeto é obrigatório")
    private String name;

    private String segmentName;
    private String tribeName;
}
