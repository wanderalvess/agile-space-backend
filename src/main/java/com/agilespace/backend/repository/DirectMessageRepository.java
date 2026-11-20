package com.agilespace.backend.repository;

import com.agilespace.backend.domain.DirectMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface DirectMessageRepository extends JpaRepository<DirectMessage, String> {

    @Query("SELECT m FROM DirectMessage m WHERE m.conversationId = :conversationId ORDER BY m.createdAt DESC")
    List<DirectMessage> findLatest(@Param("conversationId") String conversationId, Pageable pageable);

    @Query("SELECT m FROM DirectMessage m WHERE m.conversationId = :conversationId AND m.createdAt < :before ORDER BY m.createdAt DESC")
    List<DirectMessage> findLatestBefore(@Param("conversationId") String conversationId, @Param("before") LocalDateTime before, Pageable pageable);

    /** Última mensagem de cada conversa informada (uma consulta para a lista inteira). */
    @Query("SELECT m FROM DirectMessage m WHERE m.conversationId IN :ids AND m.createdAt = "
            + "(SELECT MAX(m2.createdAt) FROM DirectMessage m2 WHERE m2.conversationId = m.conversationId)")
    List<DirectMessage> findLastMessages(@Param("ids") Collection<String> conversationIds);

    /** Não lidas por conversa: [conversationId, count] das mensagens do outro lado depois da minha última leitura. */
    @Query("SELECT m.conversationId, COUNT(m) FROM DirectMessage m, Conversation c WHERE m.conversationId = c.id "
            + "AND m.senderId <> :userId AND ("
            + "(c.userAId = :userId AND (c.lastReadAtA IS NULL OR m.createdAt > c.lastReadAtA)) OR "
            + "(c.userBId = :userId AND (c.lastReadAtB IS NULL OR m.createdAt > c.lastReadAtB))) "
            + "GROUP BY m.conversationId")
    List<Object[]> countUnreadByConversation(@Param("userId") String userId);
}
