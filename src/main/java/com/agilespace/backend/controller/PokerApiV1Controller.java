package com.agilespace.backend.controller;

import com.agilespace.backend.domain.ApiKeyScope;
import com.agilespace.backend.domain.PokerRound;
import com.agilespace.backend.security.ApiKeyAccess;
import com.agilespace.backend.service.PokerService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API pública de leitura de estimativas do Scrum Poker, autenticada por API key
 * (mesmo padrão do KnowledgeApiV1Controller/PromptHubApiV1Controller) — pensada
 * pra consumo externo (ex: Lynn/TOTVS perguntando "quanto foi estimado pra tarefas
 * do projeto X"). Sem campo estruturado de squad/projeto no Poker — busca é textual
 * sobre topic/note (ver PokerRoundRepository.searchByTopicOrNote). Escopo por
 * chave (ApiKeyAccess) — fase 3 do plano de API key com escopo, mesmo escopo
 * POKER_READ já aplicado à tool MCP searchPokerEstimates equivalente.
 */
@RestController
@RequestMapping("/api/v1/poker")
@RequiredArgsConstructor
public class PokerApiV1Controller {

    private final PokerService pokerService;

    @GetMapping("/rounds")
    public ResponseEntity<Page<PokerRound>> searchRounds(
            @RequestParam(value = "q", required = false) String query,
            @PageableDefault(size = 20) Pageable pageable,
            HttpServletRequest request) {
        ApiKeyAccess.requireScope(request, ApiKeyScope.POKER_READ);
        String q = query != null ? query : "";
        return ResponseEntity.ok(pokerService.searchRounds(q, pageable));
    }
}
