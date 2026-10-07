package com.agilespace.backend.dto;

import lombok.*;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JiraProjectPreviewDto {
    private String squadId;
    private String squadName;
    private String jiraDomain;
    private int totalCandidates;
    private List<JiraMemberCandidateDto> members;
}
