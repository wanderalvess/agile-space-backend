package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "squad_members", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"squad_id", "jira_account_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SquadMember {

    @Id
    @Column(name = "db_id")
    private String dbId; // PK = {squadId}_{jiraAccountId}

    @Column(name = "squad_id", nullable = false)
    private String squadId;

    @Column(name = "jira_account_id", nullable = false)
    private String jiraAccountId;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "email")
    private String email;

    @Column(name = "role")
    private String role;

    @Column(name = "capacity_hours_per_day")
    private Double capacityHoursPerDay;

    @Column(name = "system_calculated_capacity_hours_per_day")
    private Double systemCalculatedCapacityHoursPerDay;

    @Column(name = "calibration_notes")
    private String calibrationNotes;

    @Column(name = "override_type")
    private String overrideType;

    @Column(name = "claimed_by_uid")
    private String claimedByUid;

    @Column(name = "updated_at")
    private String updatedAt;
}
