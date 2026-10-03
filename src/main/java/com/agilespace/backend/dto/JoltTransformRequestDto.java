package com.agilespace.backend.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JoltTransformRequestDto {

    @NotNull(message = "O payload de entrada (input) é obrigatório")
    private Object input;

    @NotNull(message = "A especificação JOLT (spec) é obrigatória")
    private Object spec;

    private Options options;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Options {
        private boolean smartHubEnvelope;
        private boolean sortKeys;
    }
}
