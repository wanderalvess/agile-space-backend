package com.agilespace.backend.mcp;

import com.agilespace.backend.domain.PokerRoom;
import com.agilespace.backend.domain.PokerRound;
import com.agilespace.backend.service.PokerService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Ferramentas MCP do Scrum Poker: criação de sessão (escrita) e busca de estimativas
 * já rodadas (leitura). Reaproveita o PokerService existente — como não existe sessão
 * JWT numa chamada MCP, o creatorId da sala é sintetizado a partir do ownerUserId da
 * API key (ver McpRequestContext). callerRole fixo "ADMIN" só importa no caminho de
 * update de sala já existente (requireRoomParticipant); criação nova não passa por ali.
 */
@Component
@RequiredArgsConstructor
public class McpPokerTools {

    private static final String CALLER_ROLE = "ADMIN";
    private static final String DEFAULT_DECK_TYPE = "fibonacci";
    private static final String DEFAULT_MODE = "sync";

    private final PokerService pokerService;

    @Tool(description = "Cria uma nova sessão (sala) de planning poker e retorna o id da sala")
    public PokerRoom createPokerSession(
            @ToolParam(description = "Título da sessão") String title,
            @ToolParam(description = "Tipo de baralho: fibonacci, tshirt, etc. (padrão fibonacci)", required = false) String deckType,
            @ToolParam(description = "Modo: sync ou async (padrão sync)", required = false) String mode) {
        PokerRoom room = PokerRoom.builder()
                .id(UUID.randomUUID().toString())
                .title(title)
                .deckType(deckType != null && !deckType.isBlank() ? deckType : DEFAULT_DECK_TYPE)
                .mode(mode != null && !mode.isBlank() ? mode : DEFAULT_MODE)
                .votesRevealed(false)
                .participantsCount(0)
                .createdAt(Instant.now().toString())
                .build();
        return pokerService.saveOrUpdateRoom(room, McpRequestContext.callerId(), CALLER_ROLE);
    }

    @Tool(description = "Busca estimativas de rodadas de planning poker já feitas, por texto livre (tópico/nota) — " +
            "ex: nome de projeto ou serviço, já que não existe campo estruturado de squad/projeto no Poker")
    public PokerEstimatePage searchPokerEstimates(
            @ToolParam(description = "Termo de busca livre (tópico ou nota da rodada)") String query,
            @ToolParam(description = "Página, 0-based (padrão 0)", required = false) Integer page,
            @ToolParam(description = "Tamanho da página (padrão 20)", required = false) Integer size) {
        int p = page != null ? page : 0;
        int s = size != null ? size : 20;
        // Page<T> não é suportado como retorno de @Tool pelo Spring AI — achatamos pro record abaixo,
        // mesmo motivo já documentado em McpKnowledgeTools/McpPromptHubTools.
        Page<PokerRound> result = pokerService.searchRounds(query, PageRequest.of(p, s));
        return new PokerEstimatePage(result.getContent(), result.getTotalElements(), result.getNumber(), result.getSize());
    }

    public record PokerEstimatePage(java.util.List<PokerRound> content, long totalElements, int page, int size) {
    }
}
