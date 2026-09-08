package com.agilespace.backend.repository;

import com.agilespace.backend.domain.PokerRound;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PokerRoundRepository extends JpaRepository<PokerRound, String> {
    List<PokerRound> findByRoomId(String roomId);

    @Query("SELECT r FROM PokerRound r WHERE r.roomId = :roomId ORDER BY r.timestamp DESC")
    List<PokerRound> findRecentRounds(@Param("roomId") String roomId, Pageable pageable);

    void deleteByRoomId(String roomId);

    /**
     * Busca textual em topic/note — não existe campo estruturado de squad/projeto
     * no Poker (PokerRoom/PokerRound), e o nome do serviço/projeto normalmente está
     * dentro do texto da tarefa (topic), não numa chave separada. Mesmo padrão de
     * busca textual do KnowledgeService/PromptRepository.searchPublic.
     */
    @Query("SELECT r FROM PokerRound r WHERE LOWER(r.topic) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "OR LOWER(r.note) LIKE LOWER(CONCAT('%', :q, '%')) ORDER BY r.timestamp DESC")
    Page<PokerRound> searchByTopicOrNote(@Param("q") String query, Pageable pageable);
}
