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
public class SaveJoltProjectRequestDto {

    @NotBlank(message = "O nome do projeto é obrigatório")
    @Size(max = 200, message = "O nome do projeto aceita no máximo 200 caracteres")
    private String name;

    @Size(max = 5000, message = "A descrição aceita no máximo 5000 caracteres")
    private String description;
    @Size(max = 50, message = "A categoria aceita no máximo 50 caracteres")
    private String category;
    @Size(max = 100, message = "O nome da entidade aceita no máximo 100 caracteres")
    private String entityName;
    @Size(max = 20, message = "Modo de mapeamento inválido")
    private String mappingMode;
    private Boolean isPublic;
    @Size(max = 255, message = "Identificador de squad inválido")
    private String squadId;

    // Payloads: ~4 MB de texto cada (o motor de execução aceita no máximo 2 MB de entrada e de spec).
    @Size(max = 4_000_000, message = "O JSON de entrada excede o limite de 4 MB")
    private String inputJson;
    @Size(max = 4_000_000, message = "O JSON de destino excede o limite de 4 MB")
    private String targetJson;
    @Size(max = 4_000_000, message = "A especificação excede o limite de 4 MB")
    private String specJson;
    @Size(max = 6_000_000, message = "O mapa visual excede o limite de 6 MB")
    private String flowNodes;
    @Size(max = 6_000_000, message = "O mapa visual excede o limite de 6 MB")
    private String flowEdges;

    @Size(max = 500, message = "A mensagem de versão aceita no máximo 500 caracteres")
    private String commitMessage;

    /**
     * Opcional (opt-in). Versão de edição que o cliente carregou; se o projeto já mudou, o PUT responde 409
     * em vez de sobrescrever. Ausente = comportamento antigo (última gravação vence).
     */
    private Long expectedVersion;
}
