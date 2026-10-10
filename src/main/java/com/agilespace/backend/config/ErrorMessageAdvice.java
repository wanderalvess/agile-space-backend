package com.agilespace.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Leva ao navegador a mensagem das {@link ResponseStatusException} 4xx.
 *
 * Em produção {@code server.error.include-message} é {@code never}, então sem este handler o corpo do
 * erro perde o texto escrito pelo servidor ("A votação não está aberta.") e as telas caem na mensagem
 * genérica. Aqui só as 4xx levam o texto: todas as mensagens são escritas por nós, para a pessoa ler.
 * As 5xx saem com texto fixo e o motivo vai só para o log, porque ali o reason pode carregar detalhe
 * interno (ex.: resposta de erro do Jira num BAD_GATEWAY).
 *
 * Ordem mais baixa de propósito: um {@code @ExceptionHandler} dentro do controller (Brainstorming,
 * Health Check, Plano de Ação, Retro...) sempre vence o do advice, e continua valendo.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
@Slf4j
public class ErrorMessageAdvice {

    static final String DEFAULT_4XX = "Não foi possível concluir a operação.";
    static final String GENERIC_5XX = "Erro interno. Tente novamente.";

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException ex) {
        HttpStatusCode status = ex.getStatusCode();
        if (status.is5xxServerError()) {
            log.warn("Erro {} devolvido ao cliente sem o motivo: {}", status.value(), ex.getReason());
            return ResponseEntity.status(status).body(Map.of(
                    "error", status.toString(),
                    "message", GENERIC_5XX));
        }
        String reason = ex.getReason();
        return ResponseEntity.status(status).body(Map.of(
                "error", status.toString(),
                "message", reason == null || reason.isBlank() ? DEFAULT_4XX : reason));
    }
}
