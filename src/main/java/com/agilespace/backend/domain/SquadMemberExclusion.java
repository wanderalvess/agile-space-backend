package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

/** Pessoa removida à mão do time de uma squad: o sync do Jira não a recoloca no roster. */
@Entity
@Table(name = "squad_member_exclusions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SquadMemberExclusion {

    @Id
    @Column(name = "db_id")
    private String dbId; // {squadId}_{jiraAccountId}

    @Column(name = "squad_id", nullable = false)
    private String squadId;

    @Column(name = "jira_account_id", nullable = false)
    private String jiraAccountId;

    @Column(name = "email")
    private String email;

    @Column(name = "removed_by")
    private String removedBy;

    @Column(name = "removed_at")
    private String removedAt;
}
