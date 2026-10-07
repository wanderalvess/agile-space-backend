package com.agilespace.backend.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JiraSyncResult {

    private String squadId;
    private String squadName;
    private int membersFound;
    private String syncedAt;

    public int getMemberCount() {
        return membersFound;
    }
}
