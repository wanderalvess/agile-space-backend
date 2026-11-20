package com.agilespace.backend.service;

import com.agilespace.backend.domain.RetroBoard;
import com.agilespace.backend.domain.RetroCard;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Anonimato da Retro no servidor: o que cada pessoa pode ver de um card, decidido por destinatário.
 * Nunca altera a entidade gerenciada — devolve uma cópia (o card real segue intacto no banco).
 *
 * <ul>
 *   <li>Cards ainda ocultos ({@code isCardsRevealed=false}): o autor vê o próprio card completo; o
 *       facilitador e o ADMIN veem o conteúdo (para conduzir); os demais recebem o card redigido
 *       ({@code hidden=true}, sem texto, autor, votos, reações, histórico, responsável e prazo).</li>
 *   <li>Autoria: {@code authorId} só chega ao autor, ou a todos quando {@code isAuthorsRevealed=true}.</li>
 *   <li>Coluna de ação nunca é redigida.</li>
 * </ul>
 */
public final class RetroCardRedactor {

    private RetroCardRedactor() { }

    /** Estado do board que decide a redação (fotografia no momento da publicação). */
    public record Context(String creatorId, boolean cardsRevealed, boolean authorsRevealed, Set<String> actionColumnIds) {

        public static Context of(RetroBoard board) {
            Set<String> actionIds = new HashSet<>();
            if (board.getColumns() != null) {
                board.getColumns().forEach(c -> {
                    if ("action".equals(c.getTheme()) && c.getId() != null) actionIds.add(c.getId());
                });
            }
            return new Context(board.getCreatorId(), Boolean.TRUE.equals(board.getIsCardsRevealed()),
                    Boolean.TRUE.equals(board.getIsAuthorsRevealed()), actionIds);
        }
    }

    /** Visão do card para quem está vendo; {@code viewerId} pode ser nulo (sessão sem identidade = nada). */
    public static RetroCard view(RetroCard card, Context ctx, String viewerId, boolean viewerIsAdmin) {
        if (card == null) return null;
        if (ctx.actionColumnIds().contains(card.getColumnKey())) {
            return copy(card, true, true);
        }
        boolean isAuthor = viewerId != null && viewerId.equals(card.getAuthorId());
        boolean isFacilitator = viewerIsAdmin || (viewerId != null && viewerId.equals(ctx.creatorId()));
        boolean seesContent = ctx.cardsRevealed() || isAuthor || isFacilitator;
        if (!seesContent) {
            RetroCard hidden = copy(card, false, false);
            hidden.setHidden(true);
            return hidden;
        }
        boolean seesAuthor = isAuthor || ctx.authorsRevealed();
        return copy(card, true, seesAuthor);
    }

    public static List<RetroCard> view(List<RetroCard> cards, Context ctx, String viewerId, boolean viewerIsAdmin) {
        List<RetroCard> out = new ArrayList<>(cards.size());
        for (RetroCard c : cards) out.add(view(c, ctx, viewerId, viewerIsAdmin));
        return out;
    }

    private static RetroCard copy(RetroCard c, boolean withContent, boolean withAuthor) {
        RetroCard r = new RetroCard();
        r.setId(c.getId());
        r.setVersion(c.getVersion());
        r.setBoardId(c.getBoardId());
        r.setColumnKey(c.getColumnKey());
        r.setOrder(c.getOrder());
        r.setParentId(c.getParentId());
        r.setIsDone(c.getIsDone());
        r.setCarriedFromBoardId(c.getCarriedFromBoardId());
        r.setCarriedFromBoardTitle(c.getCarriedFromBoardTitle());
        r.setCarryCount(c.getCarryCount());
        r.setAuthorId(withAuthor ? c.getAuthorId() : null);
        if (withContent) {
            r.setContent(c.getContent());
            r.setAssignee(c.getAssignee());
            r.setDueDate(c.getDueDate());
            r.setVotes(new LinkedHashSet<>(c.getVotes()));
            r.setOriginalTexts(new ArrayList<>(c.getOriginalTexts()));
            r.setReactions(c.getReactions());
        } else {
            r.setContent("");
            r.setVotes(new LinkedHashSet<>());
            r.setOriginalTexts(new ArrayList<>());
            r.setReactions(JsonNodeFactory.instance.objectNode());
        }
        return r;
    }
}
