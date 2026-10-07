package com.agilespace.backend.dto;

import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JiraConfirmSyncRequest {

    @NotNull
    private String squadId;

    private String squadName;
    private String jiraDomain;
    private String estimationUnit;

    @Builder.Default
    private boolean replaceExisting = true;

    @Builder.Default
    private boolean syncUsers = true;

    private List<JiraMemberCandidateDto> members;
}
