package com.agilespace.backend.dto;

import lombok.Data;
import java.util.List;

@Data
public class JiraSearchRequest {
    private String domain;
    private String token;
    private String jql;
    private Integer maxResults;
    private Integer startAt;
    private List<String> fields;
}
