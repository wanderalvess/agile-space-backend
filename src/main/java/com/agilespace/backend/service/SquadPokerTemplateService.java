package com.agilespace.backend.service;

import com.agilespace.backend.domain.SquadPokerTemplate;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.SquadPokerTemplateRepository;
import com.agilespace.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Templates de sessão do Scrum Poker por squad. Qualquer membro salva e usa; renomear, atualizar e excluir ficam
 * com quem salvou ou com a liderança da squad. O conteúdo de settings não é interpretado aqui (é o mesmo objeto
 * de configurações da sala); só se garante o formato e um tamanho razoável.
 */
@Service
@RequiredArgsConstructor
public class SquadPokerTemplateService {

    static final int MAX_TEMPLATES = 50;
    static final int NAME_MAX = 60;
    static final int MAX_BACKLOG = 200;
    static final int TITLE_MAX = 500;
    static final int SETTINGS_MAX_CHARS = 20_000;

    private final SquadPokerTemplateRepository repository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<SquadPokerTemplate> list(String squadId) {
        return repository.findBySquadIdIgnoreCaseOrderByCreatedAtDesc(squadId);
    }

    @Transactional
    public SquadPokerTemplate create(String squadId, SquadPokerTemplate in, String callerId) {
        if (in == null) throw bad("Envie o template.");
        if (repository.countBySquadIdIgnoreCase(squadId) >= MAX_TEMPLATES) {
            throw bad("A squad já tem " + MAX_TEMPLATES + " templates. Exclua algum antes de salvar outro.");
        }
        String creatorName = userRepository.findById(callerId).map(User::getName).orElse(null);
        return repository.save(SquadPokerTemplate.builder()
                .id(UUID.randomUUID().toString())
                .squadId(squadId)
                .name(cleanName(in.getName()))
                .deckType(cleanDeck(in.getDeckType()))
                .settings(cleanSettings(in.getSettings()))
                .backlog(cleanBacklog(in.getBacklog()))
                .createdBy(callerId)
                .createdByName(creatorName)
                .build());
    }

    /** Campos ausentes (null) ficam como estão: renomear manda só o nome. */
    @Transactional
    public SquadPokerTemplate update(String squadId, String id, SquadPokerTemplate in, String callerId, boolean canManage) {
        SquadPokerTemplate t = findInSquad(squadId, id);
        requireOwnerOrManager(t, callerId, canManage);
        if (in == null) return t;
        if (in.getName() != null) t.setName(cleanName(in.getName()));
        if (in.getDeckType() != null) t.setDeckType(cleanDeck(in.getDeckType()));
        if (in.getSettings() != null) t.setSettings(cleanSettings(in.getSettings()));
        if (in.getBacklog() != null) t.setBacklog(cleanBacklog(in.getBacklog()));
        return repository.save(t);
    }

    @Transactional
    public void delete(String squadId, String id, String callerId, boolean canManage) {
        SquadPokerTemplate t = findInSquad(squadId, id);
        requireOwnerOrManager(t, callerId, canManage);
        repository.delete(t);
    }

    private SquadPokerTemplate findInSquad(String squadId, String id) {
        return repository.findById(id)
                .filter(t -> t.getSquadId() != null && t.getSquadId().equalsIgnoreCase(squadId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Template não encontrado."));
    }

    private static void requireOwnerOrManager(SquadPokerTemplate t, String callerId, boolean canManage) {
        if (!canManage && !Objects.equals(t.getCreatedBy(), callerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Só quem salvou o template ou a liderança da squad pode alterá-lo.");
        }
    }

    static String cleanName(String name) {
        String n = trimToNull(name);
        if (n == null) throw bad("Dê um nome ao template.");
        return n.length() > NAME_MAX ? n.substring(0, NAME_MAX).trim() : n;
    }

    private static String cleanDeck(String deck) {
        String d = trimToNull(deck);
        if (d == null || d.length() > 40) throw bad("Tipo de baralho inválido.");
        return d;
    }

    private static JsonNode cleanSettings(JsonNode settings) {
        if (settings == null || settings.isNull()) return JsonNodeFactory.instance.objectNode();
        if (!settings.isObject()) throw bad("As configurações do template precisam ser um objeto.");
        if (settings.toString().length() > SETTINGS_MAX_CHARS) throw bad("Configurações grandes demais para um template.");
        return settings;
    }

    /** Guarda só título, link do Jira e tipo de cada tarefa; itens sem título são ignorados. */
    private static JsonNode cleanBacklog(JsonNode backlog) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        if (backlog == null || backlog.isNull()) return out;
        if (!backlog.isArray()) throw bad("A lista de tarefas do template precisa ser uma lista.");
        if (backlog.size() > MAX_BACKLOG) throw bad("No máximo " + MAX_BACKLOG + " tarefas por template.");
        for (JsonNode item : backlog) {
            String title = trimToNull(item.path("title").asText(null));
            if (title == null) continue;
            ObjectNode o = out.addObject();
            o.put("title", title.length() > TITLE_MAX ? title.substring(0, TITLE_MAX) : title);
            String link = trimToNull(item.path("jiraLink").asText(null));
            if (link != null) o.put("jiraLink", link);
            String type = trimToNull(item.path("type").asText(null));
            if (type != null) o.put("type", type);
        }
        return out;
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
