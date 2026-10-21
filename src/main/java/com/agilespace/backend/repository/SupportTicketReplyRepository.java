package com.agilespace.backend.repository;

import com.agilespace.backend.domain.SupportTicketReply;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SupportTicketReplyRepository extends JpaRepository<SupportTicketReply, UUID> {
    List<SupportTicketReply> findByTicketIdOrderByCreatedAtAsc(UUID ticketId);
    void deleteByTicketId(UUID ticketId);

    /**
     * Respostas do time ainda não lidas, por chamado do solicitante: linhas {@code [ticketId, quantidade]}.
     * Não lida = resposta de admin depois de {@code requester_last_read_at} (ou qualquer uma, se nunca leu).
     */
    @Query("select r.ticketId, count(r) from SupportTicketReply r, SupportTicket t "
            + "where r.ticketId = t.id and t.requesterId = :requesterId and r.isAdmin = true "
            + "and (t.requesterLastReadAt is null or r.createdAt > t.requesterLastReadAt) "
            + "group by r.ticketId")
    List<Object[]> countUnreadAdminRepliesByRequester(@Param("requesterId") String requesterId);
}
