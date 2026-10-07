package com.agilespace.backend.dto;

import lombok.Data;

@Data
public class JiraGreenhopperWorkRequest {
    private String domain;
    private String token;
    private Long rapidViewId;
    private String selectedProjectKey;
}
