package com.agilespace.backend.mcp;

import com.agilespace.backend.domain.PokerRoom;
import com.agilespace.backend.service.PokerService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Ferramenta MCP de criação de sessão de planning poker. Reaproveita o PokerService
 * existente (saveOrUpdateRoom) — como não existe sessão JWT numa chamada MCP, o
 * creatorId da sala é sintetizado a partir do ownerUserId da API key (ver
 * McpRequestContext). callerRole fixo "ADMIN" só importa no caminho de update de
 * sala já existente (requireRoomParticipant); criação nova não passa por ali.
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
}
