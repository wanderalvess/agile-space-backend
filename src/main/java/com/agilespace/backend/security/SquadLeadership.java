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

    private SquadLeadership() {
    }

    public static boolean isLeadershipJobTitle(String jobTitle) {
        if (jobTitle == null || jobTitle.isBlank()) return false;
        return LEADERSHIP_JOB_TITLES.contains(jobTitle.trim().toLowerCase());
    }
}
