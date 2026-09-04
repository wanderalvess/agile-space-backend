package com.agilespace.backend.mcp;

import com.agilespace.backend.domain.SquadIssueSnapshot;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.SquadMetricsRollup;
import com.agilespace.backend.service.SquadService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Ferramentas MCP de leitura de dados de squad. Reaproveita o SquadService existente
 * sem endpoint REST novo — chamam o service direto (não há /api/v1/squads). Mesmo
 * modelo de confiança dos GETs de /api/squads hoje: nenhum ownership check por squadId,
 * então uma API key MCP lê qualquer squad, igual um usuário JWT logado qualquer lê hoje.
 */
@Component
@RequiredArgsConstructor
public class McpSquadTools {

    private final SquadService squadService;

    @Tool(description = "Retorna o resumo/status atual de uma squad (contagens de issues, tempo estimado/logado/restante)")
    public SquadMetricsRollup getSquadStatus(@ToolParam(description = "Id da squad") String squadId) {
        return squadService.getRollup(squadId)
                .orElseThrow(() -> new IllegalArgumentException("Squad sem rollup calculado: " + squadId));
    }

    @Tool(description = "Lista os membros de uma squad")
    public List<SquadMember> listSquadMembers(@ToolParam(description = "Id da squad") String squadId) {
        return squadService.getMembers(squadId);
    }

    @Tool(description = "Lista as issues (histórias/bugs) sincronizadas de uma squad, opcionalmente filtradas por sprint")
    public List<SquadIssueSnapshot> listSquadIssues(
            @ToolParam(description = "Id da squad") String squadId,
            @ToolParam(description = "Id do sprint, opcional (sem filtro retorna todas as issues sincronizadas)", required = false) String sprintId) {
        return squadService.getIssues(squadId, sprintId);
    }
}
