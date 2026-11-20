package com.agilespace.backend.service;

import com.agilespace.backend.domain.SquadMember;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Identidade de uma pessoa do roster da squad: normalização de e-mail/nome e a regra única de "é a mesma pessoa?" usada por
 * todos os caminhos que criam {@code squad_members} (cadastro manual, planilha, Jira, convite, sync da sprint).
 *
 * <p>Ordem de casamento: (1) id da conta, sem caixa e sem espaços; (2) e-mail normalizado; (3) nome normalizado, só quando
 * não há como serem duas pessoas diferentes: um único candidato com aquele nome, os e-mails não se contradizem (os dois
 * presentes e diferentes = outra pessoa) e pelo menos um dos ids é sintético ({@code manual-...}, {@code import....} ou
 * e-mail), isto é, nenhum dos dois é uma conta real do Jira distinta. Duas contas reais do Jira com o mesmo nome são
 * homônimos e nunca se fundem, EXCETO quando uma é a chave interna do Jira Server ({@code JIRAUSER<n>}, sem e-mail) e a
 * outra é um nome de usuário/e-mail: são os dois nomes da mesma conta (issues x papéis do projeto). Mesmo assim exige
 * nome igual e um único candidato no roster; com dois candidatos (ou dois JIRAUSER de mesmo nome) não funde.
 */
public final class PersonIdentity {

    private PersonIdentity() {}

    /** E-mail sem espaços e em minúsculas; vazio vira null. */
    public static String normEmail(String email) {
        if (email == null) return null;
        String t = email.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    /** Nome com espaços aparados e repetições colapsadas (mantém acentos e caixa para exibição); vazio vira null. */
    public static String cleanName(String name) {
        if (name == null) return null;
        String t = name.trim().replaceAll("\\s+", " ");
        return t.isEmpty() ? null : t;
    }

    /** Chave de comparação do nome: sem acentos, minúsculas, espaços colapsados. */
    public static String nameKey(String name) {
        String c = cleanName(name);
        if (c == null) return "";
        return Normalizer.normalize(c, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    public static String cleanId(String id) {
        return id == null ? null : id.trim();
    }

    /** Linhas do roster (já gravadas) que são a mesma pessoa; mesma regra de {@link #resolve} aplicada a um par. */
    public static boolean sameRosterPerson(SquadMember a, SquadMember b, List<SquadMember> roster) {
        if (a == null || b == null || a == b) return false;
        List<SquadMember> others = new java.util.ArrayList<>(roster == null ? List.<SquadMember>of() : roster);
        others.remove(a);
        SquadMember hit = resolve(others, a.getJiraAccountId(), a.getEmail(), a.getDisplayName());
        return hit == b;
    }

    /** Id gerado pelo sistema (não é uma conta do Jira): {@code manual-...}, {@code import....} ou um e-mail. */
    public static boolean isSyntheticId(String id) {
        if (id == null) return true;
        String t = id.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() || t.startsWith("manual-") || t.startsWith("import.") || t.contains("@");
    }

    /**
     * Chave interna do Jira Server/Data Center ({@code JIRAUSER97280}): a mesma conta aparece com ela nas issues
     * ({@code assignee.key}) e com o nome de usuário ({@code bruna.balves}) nos papéis do projeto. São dois espaços de
     * nomes da MESMA conta, e a chave interna não traz e-mail.
     */
    public static boolean isJiraServerKey(String id) {
        return id != null && id.trim().matches("(?i)JIRAUSER\\d+");
    }

    /**
     * Dois ids reais do Jira de espaços de nomes diferentes (chave interna x nome de usuário) podem ser a mesma conta;
     * dois ids do mesmo espaço (duas chaves internas, ou dois nomes de usuário) são contas distintas.
     */
    static boolean crossJiraNamespaces(String a, String b) {
        if (isSyntheticId(a) || isSyntheticId(b)) return false;
        return isJiraServerKey(a) != isJiraServerKey(b);
    }

    /**
     * A linha existente deve adotar a chave que está chegando? Id gerado -> conta real do Jira, ou chave interna do Jira
     * (JIRAUSER) -> nome de usuário (a chave canônica do roster é a que carrega e-mail/vínculo; a chave interna só
     * serve de apelido, ver {@link #resolve}).
     */
    public static boolean shouldAdoptIncomingId(String existingId, String incomingId) {
        if (existingId == null || incomingId == null || existingId.trim().equalsIgnoreCase(incomingId.trim())) return false;
        if (isSyntheticId(existingId) && !isSyntheticId(incomingId)) return true;
        return isJiraServerKey(existingId) && !isSyntheticId(incomingId) && !isJiraServerKey(incomingId);
    }

    /** Linha do roster que representa a mesma pessoa, ou null. Ver a regra na descrição da classe. */
    public static SquadMember resolve(List<SquadMember> roster, String id, String email, String name) {
        if (roster == null || roster.isEmpty()) return null;
        String cid = cleanId(id);
        if (cid != null && !cid.isEmpty()) {
            for (SquadMember m : roster) {
                if (m.getJiraAccountId() != null && m.getJiraAccountId().trim().equalsIgnoreCase(cid)) return m;
            }
        }
        String ne = normEmail(email);
        if (ne != null) {
            for (SquadMember m : roster) {
                if (ne.equals(normEmail(m.getEmail()))) return m;
            }
        }
        String nk = nameKey(name);
        if (nk.isEmpty()) return null;
        SquadMember only = null;
        for (SquadMember m : roster) {
            if (!nk.equals(nameKey(m.getDisplayName()))) continue;
            if (only != null) return null; // vários homônimos: ambíguo, não funde
            only = m;
        }
        if (only == null) return null;
        String oe = normEmail(only.getEmail());
        if (ne != null && oe != null) return null; // os dois têm e-mail e são diferentes (iguais já casaram acima)
        boolean bothReal = !isSyntheticId(only.getJiraAccountId()) && !isSyntheticId(cid);
        // Duas contas reais: só a mesma conta vista por dois espaços de nomes (JIRAUSER x usuário) e, nesse caso, o lado
        // que é chave interna não pode ter e-mail próprio (a chave interna nunca vem com e-mail).
        if (bothReal) {
            if (!crossJiraNamespaces(only.getJiraAccountId(), cid)) return null;
            if (isJiraServerKey(only.getJiraAccountId()) && oe != null) return null;
            if (isJiraServerKey(cid) && ne != null) return null;
        }
        return only;
    }
}
