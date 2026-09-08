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
public class SaveJoltProjectRequestDto {

    @NotBlank(message = "O nome do projeto é obrigatório")
    private String name;

    private String description;
    private String category;
    private String entityName;
    private String mappingMode;
    private Boolean isPublic;
    private String squadId;

    private String inputJson;
    private String targetJson;
    private String specJson;
    private String flowNodes;
    private String flowEdges;

    private String commitMessage;
}
