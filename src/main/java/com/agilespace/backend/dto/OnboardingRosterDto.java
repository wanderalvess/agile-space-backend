package com.agilespace.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Visão enxuta de um projeto e das pessoas dele para o onboarding.
 *
 * Diferente de {@link ProjectDetailDto}, aqui NÃO vai e-mail completo de ninguém:
 * a tela de onboarding é acessível a qualquer usuário autenticado (inclusive
 * alguém que ainda não pertence a projeto nenhum), então devolver o roster
 * inteiro com e-mail seria entregar a lista de contatos da empresa. O que vai é
 * nome, papel e um e-mail mascarado, o suficiente pra pessoa se reconhecer na
 * lista sem virar fonte de coleta.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnboardingRosterDto {

    private String projectId;
    private String projectName;
    private String segmentName;
    private String tribeName;

    /** Total de pessoas do projeto — pode ser maior que members.size() nas prévias de busca. */
    private int memberCount;

    private List<Candidate> members;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Candidate {
        private String memberId;
        private String displayName;
        private String roleName;
        private String roleKey;
        private String avatarUrl;

        /** Papel de liderança (PO, Tech Lead, Agile Master...) — não pode ser autovinculado. */
        private boolean leadership;

        /** Já existe uma conta ligada a essa pessoa. */
        private boolean claimed;

        /** É essa conta que está ligada — ou seja, é você. */
        private boolean claimedByMe;

        /** Pode ser reivindicada com um clique ("sou eu"). */
        private boolean claimable;

        /** E-mail mascarado (ex: "w****n@totvs.com.br"), ou vazio se a pessoa não tem e-mail no Jira. */
        private String emailHint;
    }
}
