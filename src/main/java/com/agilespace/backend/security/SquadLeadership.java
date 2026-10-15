package com.agilespace.backend.security;

import java.util.Set;

/**
 * Cargos de negócio (User.jobTitle, autodeclarado) que valem como liderança/governança
 * de squad. Extraído de SquadController pra ser reaproveitado por outros endpoints
 * (ex: InviteController) sem duplicar a lista.
 */
public final class SquadLeadership {

    public static final Set<String> LEADERSHIP_JOB_TITLES = Set.of(
            "tech lead", "scrum master", "agile master", "product owner",
            "people lead", "tribe lead", "agile coach", "sme", "admin", "lead"
    );

    /**
     * Nomes de papel (ProjectMemberRole.roleName / Invite.roleName) que concedem governança. É a mesma lista que o
     * sync do Jira usa pra marcar isLeadership=true (JiraProfieldsService.LEADERSHIP_ROLE_NAMES), mais "ADMIN"/"LEAD".
     * "SME" fica de fora: é papel de contribuidor (autoatribuível) mesmo constando como cargo em LEADERSHIP_JOB_TITLES.
     */
    private static final Set<String> LEADERSHIP_ROLE_NAMES = Set.of(
            "AGILE MASTER", "AGILE COACH", "PRODUCT OWNER", "PRODUCT MANAGER",
            "PEOPLE LEAD", "TRIBE LEAD", "TEAM LEAD", "TECH LEAD", "SCRUM MASTER", "ADMIN", "LEAD"
    );

    private SquadLeadership() {
    }

    public static boolean isLeadershipJobTitle(String jobTitle) {
        if (jobTitle == null || jobTitle.isBlank()) return false;
        return LEADERSHIP_JOB_TITLES.contains(jobTitle.trim().toLowerCase());
    }

    /**
     * O nome de papel é de liderança? Normaliza caixa, acento, hífen/underscore e espaços repetidos, pra que
     * "agile_master", "Agile-Master" e "AGILE  MASTER" não escapem (a roleKey de um papel é derivada do nome).
     */
    public static boolean isLeadershipRoleName(String roleName) {
        if (roleName == null || roleName.isBlank()) return false;
        String stripped = java.text.Normalizer.normalize(roleName, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        String key = stripped.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z]+", " ").trim();
        return LEADERSHIP_ROLE_NAMES.contains(key);
    }
}
