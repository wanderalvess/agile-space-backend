package com.agilespace.backend.service;

/** Identidade de quem chama as operações da Retro (vem do JWT, nunca do corpo da requisição). */
public record RetroCaller(String id, String role) {

    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }
}
