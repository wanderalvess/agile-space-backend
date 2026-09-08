package com.agilespace.backend.mcp;

import com.agilespace.backend.domain.ApiKeyScope;
import com.agilespace.backend.domain.SquadIssueSnapshot;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.SquadMetricsRollup;
import com.agilespace.backend.service.SquadService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Ferramentas MCP de leitura de dados de squad. Reaproveita o SquadService existente
 * sem endpoint REST novo — chamam o service direto (não há /api/v1/squads).
 *
 * Mais restrito que a sessão JWT comum de propósito: hoje nenhum GET de
 * /api/squads checa ownership por squadId (comentário original desta classe,
 * confirmado igual em SquadController.requireSquadWriteAccess), mas uma API
 * key aqui É restrita à squad dela via ApiKeyContext.requireSquad — decisão
 * do plano de escopo por chave, comunicada e não contestada antes desta
 * implementação. squadId null na chave (toda chave de ADMIN/LEAD hoje)
 * continua sem restrição, igual antes.
 */
@Component
@RequiredArgsConstructor
public class McpSquadTools {

    private final SquadService squadService;

    @Tool(description = "Retorna o resumo/status atual de uma squad (contagens de issues, tempo estimado/logado/restante)")
    public SquadMetricsRollup getSquadStatus(
            @ToolParam(description = "Id da squad") String squadId,
            ToolContext toolContext) {
        ApiKeyContext ctx = ApiKeyContext.from(toolContext);
        ctx.requireScope(ApiKeyScope.SQUAD_READ);
        ctx.requireSquad(squadId);
        return squadService.getRollup(squadId)
                .orElseThrow(() -> new IllegalArgumentException("Squad sem rollup calculado: " + squadId));
    }

    @Tool(description = "Lista os membros de uma squad")
    public List<SquadMember> listSquadMembers(
            @ToolParam(description = "Id da squad") String squadId,
            ToolContext toolContext) {
        ApiKeyContext ctx = ApiKeyContext.from(toolContext);
        ctx.requireScope(ApiKeyScope.SQUAD_READ);
        ctx.requireSquad(squadId);
        return squadService.getMembers(squadId);
    }

    @Tool(description = "Lista as issues (histórias/bugs) sincronizadas de uma squad, opcionalmente filtradas por sprint")
    public List<SquadIssueSnapshot> listSquadIssues(
            @ToolParam(description = "Id da squad") String squadId,
            @ToolParam(description = "Id do sprint, opcional (sem filtro retorna todas as issues sincronizadas)", required = false) String sprintId,
            ToolContext toolContext) {
        ApiKeyContext ctx = ApiKeyContext.from(toolContext);
        ctx.requireScope(ApiKeyScope.SQUAD_READ);
        ctx.requireSquad(squadId);
        return squadService.getIssues(squadId, sprintId);
    }
}
