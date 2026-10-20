package com.agilespace.backend.service;

import com.agilespace.backend.domain.MembershipSource;
import com.agilespace.backend.domain.ProjectMemberRole;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Decide, de forma pura (sem banco), o que uma reimportação do Jira faz com as linhas de {@code project_member_roles}
 * de uma squad: atualizar no lugar (id estável), inserir ou remover. Substitui o antigo "apaga tudo e recria", que trocava
 * os ids, quebrava {@code link_requests.member_id} e apagava liderança cadastrada à mão (fundador de equipe manual,
 * PO/Tech Lead de {@code SquadTeamService.addMember}).
 *
 * <p>Regras:
 * <ol>
 *   <li><b>Chave natural</b>: mesma pessoa = mesma conta do Jira OU mesmo e-mail (sem caixa) OU mesmo {@code user_id}.
 *       Nome não conta (homônimos). O {@code id} da linha encontrada nunca muda.</li>
 *   <li><b>Escolha da linha</b> quando a pessoa tem mais de uma na squad: a de mesma {@code role_key}; senão uma linha
 *       "do roster" (legado/JIRA/CLAIM/APPROVED/BACKFILL), cujo papel segue o Jira. Se só houver linhas de decisão humana
 *       (MANUAL/INVITE/MIGRATION) com outro papel, o papel do Jira entra como linha EXTRA e a manual fica intocada.</li>
 *   <li><b>Precedência</b> quando a mesma pessoa fica com duas linhas na squad: LIDERANÇA VENCE (ver
 *       {@code UserProjectResolverService}); a reimportação nunca rebaixa nem apaga a linha de liderança manual.</li>
 *   <li><b>Remoção</b>: só linha legado/JIRA/BACKFILL, sem {@code user_id}, que o Jira deixou de listar. Linha com
 *       {@code user_id} ou de origem MANUAL/INVITE/CLAIM/APPROVED/MIGRATION (inclusive liderança) é preservada.
 *       Exceção explícita: a pessoa que o Jira ainda lista mas foi desmarcada na prévia sai, se a linha é do roster
 *       (legado/JIRA/BACKFILL): a escolha de quem importa vale. Origem protegida nunca sai por aqui.</li>
 *   <li>Quem foi removido à mão do time (exclusão) não entra de novo e a linha dele (do roster) sai.</li>
 * </ol>
 */
final class TeamImportPlanner {

    private TeamImportPlanner() {}

    /** Resultado do planejamento. {@code finalRows} = linhas que ficam na squad (existentes mantidas + inseridas). */
    record Plan(List<ProjectMemberRole> toInsert, List<ProjectMemberRole> toUpdate, List<ProjectMemberRole> toDelete,
                List<ProjectMemberRole> finalRows) {}

    /**
     * @param existing      linhas atuais da squad (entidades gerenciadas: as atualizações são feitas nelas)
     * @param incoming      o que a importação quer ter no time (já filtrado pela prévia)
     * @param jiraMembers   tudo o que o Jira devolveu, inclusive quem foi desmarcado na prévia
     * @param removedByHand (conta do Jira, e-mail) -> true se a liderança removeu a pessoa à mão
     */
    static Plan plan(List<ProjectMemberRole> existing, List<ProjectMemberRole> incoming, List<ProjectMemberRole> jiraMembers,
                     BiPredicate<String, String> removedByHand) {
        List<ProjectMemberRole> current = existing == null ? List.of() : existing;
        List<ProjectMemberRole> wanted = incoming == null ? List.of() : incoming;
        List<ProjectMemberRole> jira = jiraMembers == null ? List.of() : jiraMembers;
        BiPredicate<String, String> removed = removedByHand == null ? (a, b) -> false : removedByHand;

        Set<ProjectMemberRole> consumed = Collections.newSetFromMap(new IdentityHashMap<>());
        List<ProjectMemberRole> toInsert = new ArrayList<>();
        List<ProjectMemberRole> toUpdate = new ArrayList<>();
        List<ProjectMemberRole> toDelete = new ArrayList<>();

        for (ProjectMemberRole in : wanted) {
            if (removed.test(in.getJiraAccountId(), in.getEmail())) continue;
            ProjectMemberRole match = pickExisting(current, consumed, in);
            if (match != null) {
                consumed.add(match);
                if (refresh(match, in)) toUpdate.add(match);
            } else {
                if (MembershipSource.normalize(in.getSource()) == null) in.setSource(MembershipSource.JIRA);
                toInsert.add(in);
            }
        }

        List<ProjectMemberRole> kept = new ArrayList<>();
        for (ProjectMemberRole old : current) {
            if (consumed.contains(old)) { kept.add(old); continue; }
            boolean rosterRow = MembershipSource.importOwned(old.getSource());
            boolean protectedRow = MembershipSource.protectedFromImport(old.getSource(), old.getUserId());
            boolean deselectedInPreview = rosterRow && jira.stream().anyMatch(j -> samePerson(old, j));
            boolean removedOnPurpose = rosterRow && removed.test(old.getJiraAccountId(), old.getEmail());
            if (!protectedRow || deselectedInPreview || removedOnPurpose) toDelete.add(old);
            else kept.add(old);
        }

        List<ProjectMemberRole> finalRows = new ArrayList<>(kept);
        finalRows.addAll(toInsert);
        return new Plan(toInsert, toUpdate, toDelete, finalRows);
    }

    /** Mesma pessoa por conta do Jira, e-mail (sem caixa) ou conta do sistema. Nome NÃO conta: homônimos são pessoas diferentes. */
    static boolean samePerson(ProjectMemberRole a, ProjectMemberRole b) {
        if (notBlank(a.getJiraAccountId()) && a.getJiraAccountId().trim().equals(trim(b.getJiraAccountId()))) return true;
        if (notBlank(a.getEmail()) && notBlank(b.getEmail()) && a.getEmail().trim().equalsIgnoreCase(b.getEmail().trim())) return true;
        return notBlank(a.getUserId()) && a.getUserId().equals(b.getUserId());
    }

    private static ProjectMemberRole pickExisting(List<ProjectMemberRole> current, Set<ProjectMemberRole> consumed, ProjectMemberRole in) {
        ProjectMemberRole rosterRow = null;
        for (ProjectMemberRole old : current) {
            if (consumed.contains(old) || !samePerson(old, in)) continue;
            if (old.getRoleKey() != null && old.getRoleKey().equalsIgnoreCase(in.getRoleKey())) return old;
            if (rosterRow == null && !MembershipSource.roleIsHumanDecision(old.getSource())) rosterRow = old;
        }
        if (rosterRow != null) return rosterRow;
        return placeholderByName(current, consumed, in);
    }

    /**
     * Pré-cadastro só por nome (sem conta do Jira, sem e-mail, sem usuário vinculado) que o Jira agora traz com conta/e-mail:
     * é a mesma pessoa, vira uma linha só em vez de um fantasma ao lado. Só vale com um ÚNICO pré-cadastro daquele nome
     * e quando a linha de entrada tem identidade (conta ou e-mail); linha com qualquer identidade própria, ou vinculada a uma
     * conta, nunca é fundida por nome (homônimos).
     */
    private static ProjectMemberRole placeholderByName(List<ProjectMemberRole> current, Set<ProjectMemberRole> consumed, ProjectMemberRole in) {
        String key = PersonIdentity.nameKey(in.getDisplayName());
        if (key.isEmpty() || (!notBlank(in.getJiraAccountId()) && !notBlank(in.getEmail()))) return null;
        ProjectMemberRole only = null;
        for (ProjectMemberRole old : current) {
            if (consumed.contains(old) || !key.equals(PersonIdentity.nameKey(old.getDisplayName()))) continue;
            if (only != null) return null;
            only = old;
        }
        if (only == null || notBlank(only.getJiraAccountId()) || notBlank(only.getEmail()) || notBlank(only.getUserId())) return null;
        return only;
    }

    /**
     * Atualiza a linha existente com o que o Jira devolveu, sem trocar o id. Linha de decisão humana (MANUAL/INVITE/
     * MIGRATION) só ganha o que faltava (identidade, foto): papel e liderança ficam como estão. {@code user_id} existente
     * nunca é trocado. Devolve se algo mudou.
     */
    private static boolean refresh(ProjectMemberRole row, ProjectMemberRole in) {
        boolean changed = false;
        if (!MembershipSource.roleIsHumanDecision(row.getSource())) {
            if (notBlank(in.getRoleName()) && !eq(row.getRoleName(), in.getRoleName())) { row.setRoleName(in.getRoleName()); changed = true; }
            if (notBlank(in.getRoleKey()) && !eq(row.getRoleKey(), in.getRoleKey())) { row.setRoleKey(in.getRoleKey()); changed = true; }
            if (row.isLeadership() != in.isLeadership()) { row.setLeadership(in.isLeadership()); changed = true; }
            if (notBlank(in.getDisplayName()) && !eq(row.getDisplayName(), in.getDisplayName())) { row.setDisplayName(in.getDisplayName()); changed = true; }
            if (notBlank(in.getEmail()) && !eqIgnoreCase(row.getEmail(), in.getEmail())) { row.setEmail(in.getEmail()); changed = true; }
            if (notBlank(in.getJiraAccountId()) && !eq(row.getJiraAccountId(), in.getJiraAccountId())) { row.setJiraAccountId(in.getJiraAccountId()); changed = true; }
            if (notBlank(in.getAvatarUrl()) && !eq(row.getAvatarUrl(), in.getAvatarUrl())) { row.setAvatarUrl(in.getAvatarUrl()); changed = true; }
        } else {
            if (!notBlank(row.getDisplayName()) && notBlank(in.getDisplayName())) { row.setDisplayName(in.getDisplayName()); changed = true; }
            if (!notBlank(row.getEmail()) && notBlank(in.getEmail())) { row.setEmail(in.getEmail()); changed = true; }
            if (!notBlank(row.getJiraAccountId()) && notBlank(in.getJiraAccountId())) { row.setJiraAccountId(in.getJiraAccountId()); changed = true; }
            if (!notBlank(row.getAvatarUrl()) && notBlank(in.getAvatarUrl())) { row.setAvatarUrl(in.getAvatarUrl()); changed = true; }
        }
        if (!notBlank(row.getUserId()) && notBlank(in.getUserId())) { row.setUserId(in.getUserId()); changed = true; }
        return changed;
    }

    private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }
    private static boolean eqIgnoreCase(String a, String b) { return a == null ? b == null : a.equalsIgnoreCase(b); }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static String trim(String s) { return s == null ? null : s.trim(); }
}
