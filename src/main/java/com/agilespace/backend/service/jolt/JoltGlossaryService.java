package com.agilespace.backend.service.jolt;

import com.agilespace.backend.domain.JoltGlossaryTerm;
import com.agilespace.backend.repository.JoltGlossaryTermRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Glossário que o Gerador aprende com a equipe: quando uma origem escolhida pela pessoa produz a saída esperada (ou
 * cumpre o contrato), o par "campo da saída ← campo da entrada" fica gravado e, na próxima rota, já vem ligado.
 * Só aprende o que foi provado no motor; guarda só nomes de campo, nunca valores.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JoltGlossaryService {

    static final int MAX_TERM_LENGTH = 120;
    static final int MAX_PAIRS_PER_LEARN = 500;

    private final JoltGlossaryTermRepository repository;

    public record Pair(String from, String to) {
    }

    /** Campo da saída (termKey) → campos da entrada já ligados a ele. */
    public Map<String, Set<String>> load() {
        Map<String, Set<String>> map = new HashMap<>();
        for (JoltGlossaryTerm t : repository.findAll()) {
            map.computeIfAbsent(t.getTermFrom(), k -> new HashSet<>()).add(t.getTermTo());
        }
        return map;
    }

    /** Grava (ou conta mais um uso de) cada par; nomes iguais não são gravados, o gerador já liga sozinho. */
    public int learn(List<Pair> pairs) {
        Set<Pair> unique = new LinkedHashSet<>();
        for (Pair p : pairs) {
            String from = JoltSpecGenerator.termKey(p.from());
            String to = JoltSpecGenerator.termKey(p.to());
            if (from.isEmpty() || to.isEmpty() || from.equals(to)) continue;
            if (from.length() > MAX_TERM_LENGTH || to.length() > MAX_TERM_LENGTH) continue;
            unique.add(new Pair(from, to));
            if (unique.size() >= MAX_PAIRS_PER_LEARN) break;
        }
        int saved = 0;
        for (Pair p : unique) {
            try {
                JoltGlossaryTerm term = repository.findByTermFromAndTermTo(p.from(), p.to())
                        .orElseGet(() -> JoltGlossaryTerm.builder().termFrom(p.from()).termTo(p.to()).uses(0).build());
                term.setUses(term.getUses() + 1);
                repository.save(term);
                saved++;
            } catch (DataIntegrityViolationException e) {
                // Outra pessoa gravou o mesmo par ao mesmo tempo: já está no glossário.
                log.debug("Par do glossário Jolt já gravado: {} ← {}", p.from(), p.to());
            }
        }
        return saved;
    }
}
