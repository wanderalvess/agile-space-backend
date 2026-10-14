package com.agilespace.backend.controller;

import com.agilespace.backend.service.jolt.JoltSpecGenerator;
import com.agilespace.backend.service.jolt.JoltSpecGeneratorService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Gerador de spec Jolt a partir de exemplo: recebe a entrada (resposta da API de origem) e a saída que a API de destino
 * espera, devolve a spec, as regras inferidas (com o que precisa de confirmação) e a validação no motor Java.
 * Exige login (não está nas rotas públicas): a inferência é mais cara que uma transformação.
 */
@RestController
@RequestMapping("/api/jolt")
@RequiredArgsConstructor
@Tag(name = "JOLT Engine")
public class JoltGeneratorController {

    static final int MAX_NODES = 50_000;

    private final JoltSpecGeneratorService generatorService;

    /**
     * {@code overrides}: escolhas da pessoa por campo da saída (ver {@link JoltSpecGenerator.Override}).
     * {@code learn}: grava no glossário da equipe as escolhas, se a spec cumprir a saída.
     */
    public record GenerateRequest(JsonNode input, JsonNode output, Map<String, JoltSpecGenerator.Override> overrides,
                                  boolean learn) {
    }

    @Operation(summary = "Gera a spec Jolt a partir de um par entrada/saída e valida no motor Java")
    @PostMapping("/generate")
    public ResponseEntity<JoltSpecGeneratorService.Generated> generate(@RequestBody GenerateRequest request) {
        if (request == null || request.input() == null || request.output() == null
                || !request.input().isContainerNode() || !request.output().isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Informe a entrada (objeto ou lista JSON) e a saída esperada (objeto JSON).");
        }
        if (countNodes(request.input()) > MAX_NODES || countNodes(request.output()) > MAX_NODES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Exemplo grande demais (máximo de " + MAX_NODES + " campos). Use um ou poucos registros.");
        }
        if (request.overrides() != null && request.overrides().size() > 2_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolhas demais na mesma requisição.");
        }
        try {
            return ResponseEntity.ok(generatorService.generate(request.input(), request.output(),
                    request.overrides() == null ? Map.of() : request.overrides(), request.learn()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    static int countNodes(JsonNode root) {
        int count = 0;
        Deque<JsonNode> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty() && count <= MAX_NODES) {
            JsonNode n = stack.pop();
            count++;
            n.elements().forEachRemaining(stack::push);
        }
        return count;
    }
}
