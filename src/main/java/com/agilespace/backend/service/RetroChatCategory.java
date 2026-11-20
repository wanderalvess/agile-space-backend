package com.agilespace.backend.service;

import com.agilespace.backend.domain.RetroParticipant;

import java.util.Set;

/**
 * Categoria de chat de um participante da Retro (Developer, QA, UX, Designer, Management), a mesma que o
 * frontend usa para escolher o canal role-{Categoria} (retro-chat.ts + getParticipantCategory em poker-utils.ts).
 * O servidor precisa repetir a regra para restringir o canal por função: sem isso qualquer participante
 * leria e postaria em role-QA só digitando o nome do canal.
 */
final class RetroChatCategory {

    private static final Set<String> MANAGEMENT_ROLES = Set.of(
            "Agile Master", "Scrum Master", "Product Owner", "Tech Lead", "People Lead", "SME",
            "Stakeholder / Observador");

    private RetroChatCategory() {
    }

    /** null = espectador (sem canal por função). */
    static String of(RetroParticipant p) {
        if (p == null) return null;
        String globalRole = p.getGlobalRole();
        boolean hasGlobal = globalRole != null && !globalRole.isEmpty();
        String chatRole = chatRole(p.getRole(), hasGlobal);
        if ("spectator".equals(chatRole)) return null;

        if (hasGlobal) {
            if (globalRole.equals("Developer") || globalRole.contains("Desenvolvedor")) return "Developer";
            if (globalRole.equals("QA") || globalRole.contains("Analista de QA")) return "QA";
            if (globalRole.equals("UX") || globalRole.contains("UI-UX")) return "UX";
            if (globalRole.equals("Designer")) return "Designer";
            if (MANAGEMENT_ROLES.contains(globalRole) || globalRole.contains("Master")
                    || globalRole.contains("Lead") || globalRole.contains("Owner")) {
                return "Management";
            }
        }
        return switch (chatRole) {
            case "dev" -> "Developer";
            case "qa" -> "QA";
            case "organizador" -> "Management";
            default -> null;
        };
    }

    private static String chatRole(String teamRole, boolean hasGlobal) {
        if (teamRole != null) {
            switch (teamRole) {
                case "DEV": return "dev";
                case "QA": return "qa";
                case "AM":
                case "PO":
                case "PL": return "organizador";
                default: break;
            }
        }
        return hasGlobal ? "dev" : "spectator";
    }
}
