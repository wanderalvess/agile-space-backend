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
 * homônimos e nunca se fundem.
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

    /** Id gerado pelo sistema (não é uma conta do Jira): {@code manual-...}, {@code import....} ou um e-mail. */
    public static boolean isSyntheticId(String id) {
        if (id == null) return true;
        String t = id.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() || t.startsWith("manual-") || t.startsWith("import.") || t.contains("@");
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
        if (!isSyntheticId(only.getJiraAccountId()) && !isSyntheticId(cid)) return null;
        return only;
    }
}
