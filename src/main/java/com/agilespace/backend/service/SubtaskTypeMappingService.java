package com.agilespace.backend.service;

import com.agilespace.backend.domain.JiraIssueTypeCache;
import com.agilespace.backend.domain.SquadSubtaskTypeMapping;
import com.agilespace.backend.domain.SubtaskClass;
import com.agilespace.backend.domain.SubtaskMappingOrigin;
import com.agilespace.backend.repository.JiraIssueTypeCacheRepository;
import com.agilespace.backend.repository.SquadSubtaskTypeMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Classe de cada tipo de subtarefa do Jira no Sprint Planner (a classe vem do TIPO, não do título).
 * <p>Ordem de resolução para (squad, sprint): linhas gravadas para a própria sprint; senão as da sprint anterior da squad
 * (a gravada mais recentemente, origem HERDADO); senão a semente (origem PADRAO); senão SEM_CLASSIFICACAO.
 * <p>Semente: casa pelo id do issuetype e, se esse id não existe na instância (instalação nova pode ter outros ids),
 * pelo NOME do tipo (sem acento, sem caixa). Exceção: Code Review com a palavra "QA" no título da subtarefa = QA.
 */
@Service
@RequiredArgsConstructor
public class SubtaskTypeMappingService {

    public static final String CODE_REVIEW_SEED_ID = "12300";

    record Seed(String id, String name, SubtaskClass subtaskClass, BigDecimal defaultHours, boolean qaByTitleException) {}

    static final List<Seed> SEEDS = List.of(
            new Seed("10036", "Codificação", SubtaskClass.DEV, null, false),
            new Seed(CODE_REVIEW_SEED_ID, "Code Review", SubtaskClass.DEV, BigDecimal.ONE, true),
            new Seed("10013", "Execução de TI", SubtaskClass.QA, null, false),
            new Seed("10069", "Teste Automatizado", SubtaskClass.QA, null, false),
            new Seed("10063", "Documento Técnico", SubtaskClass.QA, null, false));

    private static final Pattern QA_WORD = Pattern.compile("(?<![\\p{L}\\p{N}])QA(?![\\p{L}\\p{N}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** Tipo de issue visto em algum lugar (cache da instância ou snapshot do backlog). */
    public record TypeRef(String typeId, String typeName) {}

    public record ResolvedType(String typeId, String typeName, SubtaskClass subtaskClass, SubtaskMappingOrigin origin,
                               boolean qaByTitleException, BigDecimal defaultHours, String inheritedFromSprint,
                               String updatedBy, String updatedAt) {}

    public record UpdateRequest(String typeId, String typeName, SubtaskClass subtaskClass, BigDecimal defaultHours,
                                Boolean qaByTitleException) {}

    private final SquadSubtaskTypeMappingRepository mappingRepository;
    private final JiraIssueTypeCacheRepository typeCacheRepository;

    // ===================== Regras puras =====================

    static String normalizeName(String name) {
        if (name == null) return "";
        String noAccent = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noAccent.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * Semente que vale para o tipo: pelo id; ou, se o id da semente não existe nesta instância, pelo nome.
     * {@code instanceTypeIds} vazio (cache ainda não buscado) = nenhum id conhecido, então o nome também vale.
     */
    static Seed seedFor(String typeId, String typeName, Set<String> instanceTypeIds) {
        for (Seed s : SEEDS) {
            if (s.id().equals(typeId)) return s;
        }
        String normalized = normalizeName(typeName);
        if (normalized.isEmpty()) return null;
        for (Seed s : SEEDS) {
            if (!instanceTypeIds.contains(s.id()) && normalizeName(s.name()).equals(normalized)) return s;
        }
        return null;
    }

    /** Classe final de UMA subtarefa: a do tipo, com a exceção do Code Review com "QA" no título. */
    public static SubtaskClass classify(ResolvedType type, String subtaskTitle) {
        if (type == null) return SubtaskClass.SEM_CLASSIFICACAO;
        if (type.qaByTitleException() && subtaskTitle != null && QA_WORD.matcher(subtaskTitle).find()) {
            return SubtaskClass.QA;
        }
        return type.subtaskClass();
    }

    // ===================== Resolução =====================

    public static String normalizeSprintKey(String sprintKey) {
        if (sprintKey == null || sprintKey.isBlank()) return SquadSubtaskTypeMapping.GLOBAL_SPRINT_KEY;
        String k = sprintKey.trim();
        if (!SAFE_KEY.matcher(k).matches()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Sprint de referência inválida.");
        }
        return k;
    }

    /** Lista resolvida para a sprint: tipos de subtarefa do cache + {@code extraTypes} + o que já tem mapeamento. */
    @Transactional(readOnly = true)
    public List<ResolvedType> resolve(String squadId, String sprintKey, Collection<TypeRef> extraTypes) {
        return new ArrayList<>(resolveMap(squadId, sprintKey, extraTypes).values());
    }

    @Transactional(readOnly = true)
    public Map<String, ResolvedType> resolveMap(String squadId, String sprintKey, Collection<TypeRef> extraTypes) {
        String key = normalizeSprintKey(sprintKey);
        List<JiraIssueTypeCache> cache = typeCacheRepository.findBySquadIdOrderByNameAsc(squadId);
        Set<String> instanceIds = new HashSet<>();
        Map<String, String> knownSubtaskTypes = new LinkedHashMap<>();
        for (JiraIssueTypeCache c : cache) {
            instanceIds.add(c.getTypeId());
            if (c.isSubtask()) knownSubtaskTypes.put(c.getTypeId(), c.getName());
        }
        if (extraTypes != null) {
            for (TypeRef t : extraTypes) {
                if (t != null && t.typeId() != null && !t.typeId().isBlank()) {
                    knownSubtaskTypes.putIfAbsent(t.typeId(), t.typeName() != null ? t.typeName() : t.typeId());
                }
            }
        }

        Map<String, ResolvedType> out = new LinkedHashMap<>();
        List<SquadSubtaskTypeMapping> own = mappingRepository.findBySquadIdAndSprintKey(squadId, key);
        if (!own.isEmpty()) {
            for (SquadSubtaskTypeMapping m : own) out.put(m.getTypeId(), fromRow(m, m.getOrigin(), null));
        } else {
            String previous = previousSprintKey(squadId, key);
            if (previous != null) {
                for (SquadSubtaskTypeMapping m : mappingRepository.findBySquadIdAndSprintKey(squadId, previous)) {
                    out.put(m.getTypeId(), fromRow(m, SubtaskMappingOrigin.HERDADO, previous));
                }
            }
        }
        for (Map.Entry<String, String> t : knownSubtaskTypes.entrySet()) {
            out.computeIfAbsent(t.getKey(), id -> seeded(id, t.getValue(), instanceIds));
        }
        return out;
    }

    private String previousSprintKey(String squadId, String currentKey) {
        for (SquadSubtaskTypeMapping m : mappingRepository.findBySquadIdOrderByUpdatedAtDesc(squadId)) {
            if (!currentKey.equals(m.getSprintKey())) return m.getSprintKey();
        }
        return null;
    }

    private static ResolvedType fromRow(SquadSubtaskTypeMapping m, SubtaskMappingOrigin origin, String inheritedFrom) {
        return new ResolvedType(m.getTypeId(), m.getTypeName(), m.getSubtaskClass(), origin, m.isQaByTitleException(),
                m.getDefaultHours(), inheritedFrom, m.getUpdatedBy(), m.getUpdatedAt());
    }

    static ResolvedType seeded(String typeId, String typeName, Set<String> instanceIds) {
        Seed s = seedFor(typeId, typeName, instanceIds);
        if (s == null) {
            return new ResolvedType(typeId, typeName, SubtaskClass.SEM_CLASSIFICACAO, SubtaskMappingOrigin.PADRAO,
                    false, null, null, null, null);
        }
        return new ResolvedType(typeId, typeName, s.subtaskClass(), SubtaskMappingOrigin.PADRAO,
                s.qaByTitleException(), s.defaultHours(), null, null, null);
    }

    // ===================== Escrita =====================

    /**
     * Ajuste manual. Na primeira escrita da sprint, grava antes a lista resolvida inteira (herdada/semente) para ela
     * ficar estável, e só então aplica as mudanças com origem MANUAL.
     */
    @Transactional
    public List<ResolvedType> update(String squadId, String sprintKey, List<UpdateRequest> changes, String userId) {
        String key = normalizeSprintKey(sprintKey);
        if (changes == null || changes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Informe ao menos um tipo.");
        }
        for (UpdateRequest c : changes) {
            if (c == null || c.typeId() == null || !SAFE_KEY.matcher(c.typeId().trim()).matches() || c.typeId().length() > 32) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Tipo de subtarefa inválido.");
            }
            if (c.subtaskClass() == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Classe inválida: use DEV, QA, FORA_DA_CONTA ou SEM_CLASSIFICACAO.");
            }
            if (c.defaultHours() != null && (c.defaultHours().signum() < 0 || c.defaultHours().compareTo(new BigDecimal("999")) > 0)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Horas padrão fora do intervalo.");
            }
        }
        String now = Instant.now().toString();
        Map<String, ResolvedType> resolved = resolveMap(squadId, key, List.of());
        Map<String, SquadSubtaskTypeMapping> rows = new LinkedHashMap<>();
        boolean materialize = mappingRepository.findBySquadIdAndSprintKey(squadId, key).isEmpty();
        if (!materialize) {
            for (SquadSubtaskTypeMapping m : mappingRepository.findBySquadIdAndSprintKey(squadId, key)) rows.put(m.getTypeId(), m);
        } else {
            for (ResolvedType r : resolved.values()) {
                rows.put(r.typeId(), SquadSubtaskTypeMapping.builder()
                        .id(SquadSubtaskTypeMapping.idFor(squadId, key, r.typeId())).squadId(squadId).sprintKey(key)
                        .typeId(r.typeId()).typeName(safeName(r.typeName(), r.typeId())).subtaskClass(r.subtaskClass())
                        .origin(r.origin()).qaByTitleException(r.qaByTitleException()).defaultHours(r.defaultHours())
                        .updatedBy(r.updatedBy()).updatedAt(now).build());
            }
        }
        Map<String, String> cacheNames = new HashMap<>();
        for (JiraIssueTypeCache c : typeCacheRepository.findBySquadIdOrderByNameAsc(squadId)) cacheNames.put(c.getTypeId(), c.getName());
        for (UpdateRequest c : changes) {
            String typeId = c.typeId().trim();
            SquadSubtaskTypeMapping row = rows.get(typeId);
            if (row == null) {
                ResolvedType base = seeded(typeId, firstNonBlank(c.typeName(), cacheNames.get(typeId)), cacheNames.keySet());
                row = SquadSubtaskTypeMapping.builder()
                        .id(SquadSubtaskTypeMapping.idFor(squadId, key, typeId)).squadId(squadId).sprintKey(key).typeId(typeId)
                        .typeName(safeName(firstNonBlank(c.typeName(), cacheNames.get(typeId)), typeId))
                        .qaByTitleException(base.qaByTitleException()).defaultHours(base.defaultHours()).build();
                rows.put(typeId, row);
            }
            row.setSubtaskClass(c.subtaskClass());
            row.setOrigin(SubtaskMappingOrigin.MANUAL);
            if (c.defaultHours() != null) row.setDefaultHours(c.defaultHours());
            if (c.qaByTitleException() != null) row.setQaByTitleException(c.qaByTitleException());
            row.setUpdatedBy(userId);
            row.setUpdatedAt(now);
        }
        mappingRepository.saveAll(rows.values());
        return resolve(squadId, key, List.of());
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String safeName(String name, String fallback) {
        String n = name != null && !name.isBlank() ? name.trim() : fallback;
        return n.length() > 255 ? n.substring(0, 255) : n;
    }

    /** Tipos conhecidos da instância (cache), para a tela de mapeamento. */
    @Transactional(readOnly = true)
    public List<JiraIssueTypeCache> knownTypes(String squadId) {
        return typeCacheRepository.findBySquadIdOrderByNameAsc(squadId);
    }
}
