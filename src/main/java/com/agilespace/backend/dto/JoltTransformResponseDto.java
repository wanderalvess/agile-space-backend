package com.agilespace.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JoltTransformResponseDto {
    private boolean success;
    private Object output;
    private long executionTimeMs;
    private String error;
    private String engine;
}
