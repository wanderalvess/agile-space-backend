package com.agilespace.backend.service;

import com.agilespace.backend.domain.ActionPlan;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Já existe plano de ação da mesma origem (tipo + id) na mesma squad. É um 409; o controller devolve o id do plano
 * existente quando quem chama pode vê-lo (docs/action-plan-origin-api.md).
 */
public class ActionPlanSourceConflictException extends ResponseStatusException {

    private final transient ActionPlan existing;

    public ActionPlanSourceConflictException(ActionPlan existing) {
        super(HttpStatus.CONFLICT, "Já existe um plano de ação criado a partir desta origem.");
        this.existing = existing;
    }

    public ActionPlan getExisting() {
        return existing;
    }
}
