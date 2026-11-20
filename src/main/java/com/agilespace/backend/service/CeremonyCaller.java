package com.agilespace.backend.service;

/** Identidade de quem chama as cerimônias (Brainstorming, Health Check, Plano de Ação): vem do JWT, nunca do corpo. */
public record CeremonyCaller(String id, String role) {

    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }

    public boolean is(String userId) {
        return id != null && id.equals(userId);
    }

    public boolean isAuthenticated() {
        return id != null && !id.isBlank();
    }
}
