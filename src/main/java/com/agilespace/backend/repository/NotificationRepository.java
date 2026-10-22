package com.agilespace.backend.repository;

import com.agilespace.backend.domain.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, String> {

    Optional<Notification> findByIdAndUserId(String id, String userId);

    @Query("SELECT n FROM Notification n WHERE n.userId = :userId AND n.kind IN :kinds ORDER BY n.createdAt DESC")
    List<Notification> findForUser(@Param("userId") String userId, @Param("kinds") Collection<String> kinds, Pageable pageable);

    @Query("SELECT COUNT(n) FROM Notification n WHERE n.userId = :userId AND n.readAt IS NULL AND n.kind IN :kinds")
    long countUnread(@Param("userId") String userId, @Param("kinds") Collection<String> kinds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.userId = :userId AND n.readAt IS NULL AND n.kind IN :kinds")
    int markAllRead(@Param("userId") String userId, @Param("kinds") Collection<String> kinds, @Param("now") LocalDateTime now);

    /** Quem já recebeu a notificação com esta chave (lembrete 1 por sala, aviso 1 por pessoa). */
    @Query("SELECT n.userId FROM Notification n WHERE n.dedupeKey = :key AND n.userId IN :userIds")
    List<String> findUserIdsWithDedupeKey(@Param("key") String key, @Param("userIds") Collection<String> userIds);

    /** Quem ainda tem não lida a notificação desta chave (para retirar o aviso de um pedido cancelado). */
    @Query("SELECT DISTINCT n.userId FROM Notification n WHERE n.dedupeKey = :key AND n.readAt IS NULL")
    List<String> findUnreadUserIdsByDedupeKey(@Param("key") String key);

    /** Marca como lidas as notificações de uma pessoa que apontam para este link (ex.: respostas de um chamado). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.userId = :userId AND n.kind = :kind AND n.link = :link AND n.readAt IS NULL")
    int markReadByUserKindAndLink(@Param("userId") String userId, @Param("kind") String kind, @Param("link") String link,
                                  @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.dedupeKey = :key AND n.readAt IS NULL")
    int markReadByDedupeKey(@Param("key") String key, @Param("now") LocalDateTime now);
}
