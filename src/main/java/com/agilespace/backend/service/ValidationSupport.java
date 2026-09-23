package com.agilespace.backend.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;

/**
 * Checagens de validação compartilhadas entre services que persistem conteúdo de tamanho
 * variável (Sprint Planner, Showcase) — evita duplicar o mesmo if/throw em cada um.
 */
final class ValidationSupport {

    private ValidationSupport() {
    }

    static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " é obrigatório");
        }
    }

    static void requireMaxSize(Collection<?> collection, int max, String field) {
        if (collection != null && collection.size() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " excede o limite de " + max + " itens");
        }
    }
}
