package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Pedido de vínculo (V74): quem não consegue se reconhecer no roster (e-mail do Jira diferente do login, linha de
 * liderança) pede a um admin que ligue a conta a uma linha de project_member_roles.
 */
@Entity
@Table(name = "link_requests", indexes = {
    @Index(name = "idx_link_requests_status_created", columnList = "status, created_at"),
    @Index(name = "idx_link_requests_requester_project_status", columnList = "requester_user_id, project_key, status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LinkRequest {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "requester_user_id", nullable = false)
    private String requesterUserId;

    @Column(name = "project_key", nullable = false, length = 50)
    private String projectKey;

    /** Linha do roster (project_member_roles.id) que a pessoa diz ser ela; o admin pode definir/trocar na aprovação. */
    @Column(name = "member_id")
    private String memberId;

    @Column(name = "email_in_jira", nullable = false)
    private String emailInJira;

    @Column(length = 500)
    private String message;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** E-mail (ou id) do admin que decidiu. */
    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;
}
