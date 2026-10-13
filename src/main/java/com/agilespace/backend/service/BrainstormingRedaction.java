package com.agilespace.backend.service;

import com.agilespace.backend.domain.BrainstormingBoard;
import com.agilespace.backend.domain.BrainstormingIdea;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * Saída das ideias por destinatário: o servidor só envia o que cada pessoa pode ver (REST e WebSocket).
 * <ul>
 *   <li>Ideias ocultas ({@code settings.isRevealed == false}): quem não é o autor, o facilitador nem ADMIN
 *       recebe a ideia REDIGIDA: texto vazio, autor nulo, votos vazios e {@code hidden = true}
 *       (id, mural, grupo, posição e ligação ficam, para o layout).</li>
 *   <li>Autores ocultos ({@code settings.isAnonymous == true}): o autor só aparece para o próprio autor,
 *       nunca para o facilitador nem para ADMIN.</li>
 * </ul>
 * Matriz, Plano, votos e fusão usam os dados reais no servidor; só a saída é filtrada. Nunca altera a entidade
 * recebida (ela pode estar gerenciada pelo JPA): devolve uma cópia quando precisa mudar algo.
 */
public final class BrainstormingRedaction {

    private BrainstormingRedaction() {
    }

    public static boolean ideasHidden(BrainstormingBoard board) {
        JsonNode settings = board == null ? null : board.getSettings();
        return settings != null && settings.path("isRevealed").isBoolean() && !settings.path("isRevealed").asBoolean();
    }

    public static boolean authorsHidden(BrainstormingBoard board) {
        JsonNode settings = board == null ? null : board.getSettings();
        return settings != null && settings.path("isAnonymous").asBoolean(false);
    }

    public static BrainstormingIdea redact(BrainstormingIdea idea, BrainstormingBoard board, CeremonyCaller caller) {
        if (idea == null || board == null) {
            return idea;
        }
        boolean author = caller != null && caller.is(idea.getAuthorId());
        if (author) {
            return idea;
        }
        boolean facilitator = caller != null && (caller.isAdmin() || caller.is(board.getCreatorId()));
        if (ideasHidden(board) && !facilitator) {
            BrainstormingIdea copy = copy(idea);
            copy.setContent("");
            copy.setAuthorId(null);
            copy.setVotes(JsonNodeFactory.instance.arrayNode());
            copy.setHidden(true);
            return copy;
        }
        if (authorsHidden(board) && idea.getAuthorId() != null) {
            BrainstormingIdea copy = copy(idea);
            copy.setAuthorId(null);
            return copy;
        }
        return idea;
    }

    private static BrainstormingIdea copy(BrainstormingIdea idea) {
        BrainstormingIdea copy = new BrainstormingIdea();
        copy.setId(idea.getId());
        copy.setBoardId(idea.getBoardId());
        copy.setContent(idea.getContent());
        copy.setAuthorId(idea.getAuthorId());
        copy.setParentId(idea.getParentId());
        copy.setGroupId(idea.getGroupId());
        copy.setCreatedAt(idea.getCreatedAt());
        copy.setColor(idea.getColor());
        copy.setVotes(idea.getVotes());
        copy.setPosition(idea.getPosition());
        copy.setQualifiers(idea.getQualifiers());
        return copy;
    }
}
