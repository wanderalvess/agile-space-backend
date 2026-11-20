package com.agilespace.backend.service;

import com.agilespace.backend.domain.ShowcaseTask;
import com.agilespace.backend.domain.ShowcaseTaskOrigin;
import com.agilespace.backend.dto.ShowcaseFieldOriginDto;
import com.agilespace.backend.repository.ShowcaseTaskOriginRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Guarda, por card da Review, de onde veio o texto de Problema, Solução e Critérios quando o card nasceu da importação do
 * Jira, e o que o Jira tinha, para "Restaurar do Jira" depois de uma edição (docs/jira-import-origem-api.md).
 *
 * <p>Os dados andam junto do card ({@code origins}), sem endpoint próprio. Cliente que não conhece o campo não manda
 * {@code origins}: nada muda para ele. {@code origins: []} apaga as origens do card.
 */
@Service
@RequiredArgsConstructor
public class ShowcaseTaskOriginService {

    static final Set<String> FIELDS = Set.of("problem", "solution", "acceptanceCriteria");
    static final Set<String> KINDS = Set.of("campo", "comentario", "descricao", "subtarefa", "pai");
    static final int MAX_TEXT = 100_000;
    static final int MAX_LABEL = 300;

    private final ShowcaseTaskOriginRepository repository;

    /** Preenche {@code origins} dos cards da sessão. */
    public void attach(String sessionId, List<ShowcaseTask> tasks) {
        if (tasks == null || tasks.isEmpty()) return;
        Map<String, List<ShowcaseFieldOriginDto>> byTask = repository.findBySessionId(sessionId).stream()
                .collect(Collectors.groupingBy(ShowcaseTaskOrigin::getTaskId, LinkedHashMap::new,
                        Collectors.mapping(ShowcaseTaskOriginService::toDto, Collectors.toList())));
        for (ShowcaseTask task : tasks) {
            task.setOrigins(byTask.getOrDefault(task.getId(), Collections.emptyList()));
        }
    }

    /** Troca as origens do card pelas enviadas. Campo ou tipo de fonte inválido = 400 e nada é gravado. */
    public void replace(String sessionId, String taskId, List<ShowcaseFieldOriginDto> incoming, String callerId) {
        List<ShowcaseTaskOrigin> toSave = validate(sessionId, taskId, incoming, callerId);
        List<ShowcaseTaskOrigin> stored = repository.findBySessionIdAndTaskId(sessionId, taskId);
        // O cliente devolve as origens que leu em todo save da Review: igual ao gravado não mexe em nada (nem recarimba
        // quem importou e quando).
        if (sameContent(stored, toSave)) return;
        repository.deleteAll(stored);
        repository.flush();
        if (!toSave.isEmpty()) {
            repository.saveAll(toSave);
        }
    }

    private static boolean sameContent(List<ShowcaseTaskOrigin> stored, List<ShowcaseTaskOrigin> incoming) {
        if (stored.size() != incoming.size()) return false;
        Map<String, ShowcaseTaskOrigin> byField = stored.stream()
                .collect(Collectors.toMap(ShowcaseTaskOrigin::getField, o -> o, (a, b) -> b));
        for (ShowcaseTaskOrigin in : incoming) {
            ShowcaseTaskOrigin old = byField.get(in.getField());
            if (old == null || !in.getSourceKind().equals(old.getSourceKind())
                    || !in.getSourceLabel().equals(old.getSourceLabel())
                    || !in.getOriginalText().equals(old.getOriginalText())) {
                return false;
            }
        }
        return true;
    }

    /** Valida o que o cliente mandou antes de a sessão ser regravada (para um 400 não deixar a Review pela metade). */
    public void validateOnly(String sessionId, String taskId, List<ShowcaseFieldOriginDto> incoming) {
        validate(sessionId, taskId, incoming, null);
    }

    /** Card que saiu da Review leva as origens junto. */
    public void removeOrphans(String sessionId, Set<String> keepTaskIds) {
        List<ShowcaseTaskOrigin> orphans = repository.findBySessionId(sessionId).stream()
                .filter(o -> !keepTaskIds.contains(o.getTaskId())).toList();
        if (!orphans.isEmpty()) {
            repository.deleteAll(orphans);
        }
    }

    private List<ShowcaseTaskOrigin> validate(String sessionId, String taskId, List<ShowcaseFieldOriginDto> incoming, String callerId) {
        if (incoming == null || incoming.isEmpty()) return List.of();
        if (incoming.size() > FIELDS.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Um card tem no máximo " + FIELDS.size() + " origens (uma por campo).");
        }
        Map<String, ShowcaseTaskOrigin> byField = new LinkedHashMap<>();
        LocalDateTime now = LocalDateTime.now();
        for (ShowcaseFieldOriginDto dto : incoming) {
            if (dto == null || dto.field() == null || !FIELDS.contains(dto.field())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "origins.field deve ser problem, solution ou acceptanceCriteria.");
            }
            if (dto.sourceKind() == null || !KINDS.contains(dto.sourceKind())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "origins.sourceKind deve ser campo, comentario, descricao, subtarefa ou pai.");
            }
            String text = dto.originalText() == null ? "" : dto.originalText();
            if (text.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "origins.originalText não pode ser vazio.");
            }
            String label = dto.sourceLabel() == null || dto.sourceLabel().isBlank() ? dto.sourceKind() : dto.sourceLabel().strip();
            byField.put(dto.field(), ShowcaseTaskOrigin.builder()
                    .id(UUID.randomUUID().toString())
                    .sessionId(sessionId).taskId(taskId).field(dto.field()).sourceKind(dto.sourceKind())
                    .sourceLabel(label.length() > MAX_LABEL ? label.substring(0, MAX_LABEL) : label)
                    .originalText(text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text)
                    .importedBy(callerId).importedAt(now).build());
        }
        return new ArrayList<>(byField.values());
    }

    private static ShowcaseFieldOriginDto toDto(ShowcaseTaskOrigin o) {
        return new ShowcaseFieldOriginDto(o.getField(), o.getSourceKind(), o.getSourceLabel(), o.getOriginalText(), o.getImportedBy(), o.getImportedAt());
    }
}
