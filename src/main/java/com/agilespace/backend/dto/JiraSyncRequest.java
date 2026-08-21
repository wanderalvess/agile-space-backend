package com.agilespace.backend.dto;

import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JiraSyncRequest {

    @NotNull
    private String projectKey;

    @NotNull
    private String jiraDomain;

    @NotNull
    private String token;
}
