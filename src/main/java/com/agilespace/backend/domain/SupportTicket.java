package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "support_tickets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupportTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "OPEN"; // OPEN, IN_PROGRESS, CLOSED

    @Column(name = "requester_id", nullable = false)
    private String requesterId;

    @Column(name = "requester_name")
    private String requesterName;

    @Column(name = "requester_email")
    private String requesterEmail;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** support | bug | suggestion | feedback. Nulo em chamado antigo (o tipo ia embutido no assunto). */
    @Column(length = 20)
    private String type;

    /** SUP-0001...; nulo em chamado antigo (ver {@link #getProtocol()}). */
    @Column(length = 20)
    private String protocol;

    /** Última vez que o solicitante abriu o chamado: respostas do time depois disso são "não lidas". */
    @Column(name = "requester_last_read_at")
    private LocalDateTime requesterLastReadAt;

    /** Respostas do time ainda não lidas pelo solicitante (calculado na listagem "mine"; não persiste). */
    @Transient
    private int unreadReplies;

    /** Protocolo gravado ou, em chamado antigo, um derivado estável do id (nunca gravado). */
    public String getProtocol() {
        if (protocol != null && !protocol.isBlank()) return protocol;
        return derivedProtocol(id);
    }

    public static String derivedProtocol(UUID id) {
        if (id == null) return null;
        return "SUP-" + id.toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    public boolean isHasUnreadReply() {
        return unreadReplies > 0;
    }
}
