package com.agilespace.backend.service;

import com.agilespace.backend.domain.UserKanbanCard;
import com.agilespace.backend.domain.UserStickyNote;
import com.agilespace.backend.domain.UserQuickLink;
import com.agilespace.backend.domain.UserSnippet;
import com.agilespace.backend.repository.UserKanbanCardRepository;
import com.agilespace.backend.repository.UserStickyNoteRepository;
import com.agilespace.backend.repository.UserQuickLinkRepository;
import com.agilespace.backend.repository.UserSnippetRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class WorkspaceService {

    static final int MAX_ITEMS_PER_USER = 2000;
    static final int MAX_TITLE = 255;
    static final int MAX_TAG = 60;
    static final int MAX_TEXT = 20_000;
    static final int MAX_NOTE = 20_000;
    static final int MAX_SNIPPET = 200_000;
    static final int MAX_URL = 2048;
    static final Set<String> KANBAN_STATUS = Set.of("todo", "doing", "done");
    static final Set<String> KANBAN_PRIORITY = Set.of("baixa", "media", "alta", "critica");
    static final String DEFAULT_NOTE_COLOR = "bg-amber-50 border-amber-200/60 text-amber-900";

    @Autowired
    private UserKanbanCardRepository kanbanRepository;

    @Autowired
    private UserStickyNoteRepository noteRepository;

    @Autowired
    private UserQuickLinkRepository linkRepository;

    @Autowired
    private UserSnippetRepository snippetRepository;

    // --- Kanban ---
    @Transactional(readOnly = true)
    public List<UserKanbanCard> getKanbanCards(String userId) {
        return kanbanRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    /** Criação ou substituição do card. Para mudar só alguns campos use {@link #patchKanbanCard}. */
    @Transactional
    public UserKanbanCard saveKanbanCard(UserKanbanCard card) {
        card.setTitle(requireText(card.getTitle(), "O título da tarefa", MAX_TITLE));
        card.setDescription(optionalText(card.getDescription(), "A descrição", MAX_TEXT));
        card.setStatus(oneOf(card.getStatus(), KANBAN_STATUS, "todo", "Status"));
        card.setPriority(oneOf(card.getPriority(), KANBAN_PRIORITY, "media", "Prioridade"));
        card.setTag(optionalText(card.getTag(), "A etiqueta", MAX_TAG));
        card.setOriginLink(normalizeOriginLink(card.getOriginLink()));

        if (card.getId() == null || card.getId().isEmpty()) {
            requireCapacity(kanbanRepository.countByUserId(card.getUserId()));
            card.setId(UUID.randomUUID().toString());
        } else {
            Optional<UserKanbanCard> existing = kanbanRepository.findById(card.getId());
            requireOwner(existing.map(UserKanbanCard::getUserId), card.getUserId());
            // Origem e exportação não vêm da tela de edição: não apagar o que já existia.
            existing.ifPresent(old -> {
                if (card.getOriginLink() == null) card.setOriginLink(old.getOriginLink());
                if (card.getExportedAt() == null) card.setExportedAt(old.getExportedAt());
                if (card.getTag() == null) card.setTag(old.getTag());
            });
        }
        card.setUpdatedAt(LocalDateTime.now());
        return kanbanRepository.save(card);
    }

    /**
     * Escrita parcial: só os campos presentes no corpo mudam ({@code null} explícito limpa descrição,
     * prazo e etiqueta). Resolve o arrastar entre colunas, que antes mandava um card incompleto.
     */
    @Transactional
    public UserKanbanCard patchKanbanCard(String id, String callerId, Map<String, Object> changes) {
        UserKanbanCard card = kanbanRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tarefa não encontrada"));
        requireOwner(Optional.of(card.getUserId()), callerId);
        if (changes.containsKey("title")) card.setTitle(requireText(asString(changes.get("title")), "O título da tarefa", MAX_TITLE));
        if (changes.containsKey("description")) card.setDescription(optionalText(asString(changes.get("description")), "A descrição", MAX_TEXT));
        if (changes.containsKey("status")) card.setStatus(oneOf(asString(changes.get("status")), KANBAN_STATUS, null, "Status"));
        if (changes.containsKey("priority")) card.setPriority(oneOf(asString(changes.get("priority")), KANBAN_PRIORITY, null, "Prioridade"));
        if (changes.containsKey("tag")) card.setTag(optionalText(asString(changes.get("tag")), "A etiqueta", MAX_TAG));
        if (changes.containsKey("dueDate")) card.setDueDate(parseDate(changes.get("dueDate")));
        card.setUpdatedAt(LocalDateTime.now());
        return kanbanRepository.save(card);
    }

    @Transactional
    public void deleteKanbanCard(String id, String callerId) {
        requireOwner(kanbanRepository.findById(id).map(UserKanbanCard::getUserId), callerId);
        kanbanRepository.deleteById(id);
    }

    // --- Sticky Notes ---
    @Transactional(readOnly = true)
    public List<UserStickyNote> getStickyNotes(String userId) {
        return noteRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    @Transactional
    public UserStickyNote saveStickyNote(UserStickyNote note) {
        note.setContent(note.getContent() == null ? "" : limit(note.getContent(), "A nota", MAX_NOTE));
        note.setColor(note.getColor() == null || note.getColor().isBlank() ? DEFAULT_NOTE_COLOR : limit(note.getColor(), "A cor", 255));
        if (note.getId() == null || note.getId().isEmpty()) {
            requireCapacity(noteRepository.countByUserId(note.getUserId()));
            note.setId(UUID.randomUUID().toString());
        } else {
            requireOwner(noteRepository.findById(note.getId()).map(UserStickyNote::getUserId), note.getUserId());
        }
        note.setUpdatedAt(LocalDateTime.now());
        return noteRepository.save(note);
    }

    /** Escrita parcial da nota (texto, cor ou fixada) sem tocar nos demais campos. */
    @Transactional
    public UserStickyNote patchStickyNote(String id, String callerId, Map<String, Object> changes) {
        UserStickyNote note = noteRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nota não encontrada"));
        requireOwner(Optional.of(note.getUserId()), callerId);
        if (changes.containsKey("content")) {
            String content = asString(changes.get("content"));
            note.setContent(content == null ? "" : limit(content, "A nota", MAX_NOTE));
        }
        if (changes.containsKey("color")) {
            String color = asString(changes.get("color"));
            if (color != null && !color.isBlank()) note.setColor(limit(color, "A cor", 255));
        }
        if (changes.containsKey("isPinned") || changes.containsKey("pinned")) {
            Object pinned = changes.containsKey("isPinned") ? changes.get("isPinned") : changes.get("pinned");
            note.setPinned(Boolean.TRUE.equals(pinned));
        }
        note.setUpdatedAt(LocalDateTime.now());
        return noteRepository.save(note);
    }

    @Transactional
    public void deleteStickyNote(String id, String callerId) {
        requireOwner(noteRepository.findById(id).map(UserStickyNote::getUserId), callerId);
        noteRepository.deleteById(id);
    }

    // --- Quick Links ---
    @Transactional(readOnly = true)
    public List<UserQuickLink> getQuickLinks(String userId) {
        return linkRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public UserQuickLink saveQuickLink(UserQuickLink link) {
        link.setTitle(requireText(link.getTitle(), "O nome do atalho", MAX_TITLE));
        link.setUrl(requireHttpUrl(link.getUrl()));
        link.setIconType(optionalText(link.getIconType(), "O ícone", 32));
        link.setColor(optionalText(link.getColor(), "A cor", 160));
        if (link.getId() == null || link.getId().isEmpty()) {
            requireCapacity(linkRepository.countByUserId(link.getUserId()));
            link.setId(UUID.randomUUID().toString());
        } else {
            requireOwner(linkRepository.findById(link.getId()).map(UserQuickLink::getUserId), link.getUserId());
        }
        if (link.getCreatedAt() == null) {
            link.setCreatedAt(LocalDateTime.now());
        }
        return linkRepository.save(link);
    }

    @Transactional
    public void deleteQuickLink(String id, String callerId) {
        requireOwner(linkRepository.findById(id).map(UserQuickLink::getUserId), callerId);
        linkRepository.deleteById(id);
    }

    // --- Snippets ---
    @Transactional(readOnly = true)
    public List<UserSnippet> getSnippets(String userId) {
        return snippetRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public UserSnippet saveSnippet(UserSnippet snippet) {
        snippet.setTitle(requireText(snippet.getTitle(), "O título do snippet", MAX_TITLE));
        snippet.setContent(optionalText(snippet.getContent(), "O código", MAX_SNIPPET));
        snippet.setLanguage(optionalText(snippet.getLanguage(), "A linguagem", 64));
        if (snippet.getId() == null || snippet.getId().isEmpty()) {
            requireCapacity(snippetRepository.countByUserId(snippet.getUserId()));
            snippet.setId(UUID.randomUUID().toString());
        } else {
            requireOwner(snippetRepository.findById(snippet.getId()).map(UserSnippet::getUserId), snippet.getUserId());
        }
        if (snippet.getCreatedAt() == null) {
            snippet.setCreatedAt(LocalDateTime.now());
        }
        return snippetRepository.save(snippet);
    }

    @Transactional
    public void deleteSnippet(String id, String callerId) {
        requireOwner(snippetRepository.findById(id).map(UserSnippet::getUserId), callerId);
        snippetRepository.deleteById(id);
    }

    /**
     * Id inexistente passa (save cria, delete é no-op); id de outro usuário é recusado — sem isso
     * um POST com o id de um card alheio "roubava" o card e o DELETE apagava o de qualquer um.
     */
    private static void requireOwner(Optional<String> existingOwner, String callerId) {
        if (existingOwner.isPresent() && (callerId == null || !callerId.equals(existingOwner.get()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Item pertence a outro usuário");
        }
    }

    private static void requireCapacity(long current) {
        if (current >= MAX_ITEMS_PER_USER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Limite de " + MAX_ITEMS_PER_USER + " itens atingido. Exclua algum item antes de criar outro.");
        }
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static String requireText(String value, String label, int max) {
        if (value == null || value.isBlank()) {
            throw invalid(label + " é obrigatório.");
        }
        return limit(value.trim(), label, max);
    }

    private static String optionalText(String value, String label, int max) {
        if (value == null) return null;
        return limit(value, label, max);
    }

    private static String limit(String value, String label, int max) {
        if (value.length() > max) {
            throw invalid(label + " passa do limite de " + max + " caracteres.");
        }
        return value;
    }

    private static String oneOf(String value, Set<String> allowed, String fallback, String label) {
        if (value == null || value.isBlank()) {
            if (fallback == null) throw invalid(label + " inválido.");
            return fallback;
        }
        String normalized = value.trim().toLowerCase();
        if (!allowed.contains(normalized)) {
            throw invalid(label + " inválido: " + value);
        }
        return normalized;
    }

    private static LocalDateTime parseDate(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        String text = value.toString().trim();
        try {
            return text.length() <= 10 ? LocalDate.parse(text).atStartOfDay() : LocalDateTime.parse(text.replace("Z", ""));
        } catch (DateTimeParseException e) {
            throw invalid("Data inválida: " + text);
        }
    }

    /** Aceita só http/https com host (bloqueia javascript:, data:, file: etc.). */
    static String requireHttpUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid("O endereço do atalho é obrigatório.");
        }
        String url = raw.trim();
        if (url.length() > MAX_URL) {
            throw invalid("O endereço passa do limite de " + MAX_URL + " caracteres.");
        }
        if (!isHttpUrl(url)) {
            throw invalid("Endereço inválido: use um link http:// ou https://.");
        }
        return url;
    }

    private static boolean isHttpUrl(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            return scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    /** Link de origem de um card: caminho interno ("/retro/abc") ou http(s); vazio vira nulo. */
    static String normalizeOriginLink(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String link = raw.trim();
        if (link.length() > MAX_URL) {
            throw invalid("O link de origem passa do limite de " + MAX_URL + " caracteres.");
        }
        boolean internal = link.startsWith("/") && !link.startsWith("//") && !link.contains("\\");
        if (!internal && !isHttpUrl(link)) {
            throw invalid("Link de origem inválido.");
        }
        return link;
    }
}
