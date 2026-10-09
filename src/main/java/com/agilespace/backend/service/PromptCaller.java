package com.agilespace.backend.service;

/** Identidade de quem chama a Biblioteca de IA (vem do JWT, nunca do corpo da requisição). */
public record PromptCaller(String id, String role) {

    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }

    public boolean is(String userId) {
        return id != null && id.equals(userId);
    }
}
