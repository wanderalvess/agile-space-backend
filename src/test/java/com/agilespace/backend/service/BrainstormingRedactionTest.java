package com.agilespace.backend.service;

import com.agilespace.backend.domain.BrainstormingBoard;
import com.agilespace.backend.domain.BrainstormingIdea;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BrainstormingRedaction - o servidor só envia a cada pessoa o que ela pode ver")
class BrainstormingRedactionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CeremonyCaller AUTHOR = new CeremonyCaller("autor", "MEMBER");
    private static final CeremonyCaller OTHER = new CeremonyCaller("outro", "MEMBER");
    private static final CeremonyCaller FACILITATOR = new CeremonyCaller("fac", "MEMBER");
    private static final CeremonyCaller ADMIN = new CeremonyCaller("adm", "ADMIN");

    private static BrainstormingBoard board(String settingsJson) {
        try {
            return BrainstormingBoard.builder().id("b1").creatorId("fac").settings(JSON.readTree(settingsJson)).build();
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static BrainstormingIdea idea() {
        try {
            return BrainstormingIdea.builder().id("i1").boardId("b1").content("segredo").authorId("autor").groupId("g1")
                    .parentId("p1").createdAt("t").votes(JSON.readTree("[\"x\",\"y\"]"))
                    .position(JSON.readTree("{\"x\":1,\"y\":2}")).qualifiers(JSON.readTree("{\"roi\":80}")).build();
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    @DisplayName("Ideias ocultas: quem não é autor/facilitador/ADMIN recebe texto vazio, sem autor nem votos, marcada hidden")
    void hiddenIdeaIsRedactedForOthers() {
        BrainstormingIdea original = idea();

        BrainstormingIdea out = BrainstormingRedaction.redact(original, board("{\"isRevealed\":false}"), OTHER);

        assertEquals("", out.getContent());
        assertNull(out.getAuthorId());
        assertEquals(0, out.getVotes().size());
        assertTrue(out.getHidden());
        assertEquals("i1", out.getId());
        assertEquals("b1", out.getBoardId());
        assertEquals("g1", out.getGroupId());
        assertEquals("p1", out.getParentId());
        assertEquals(1, out.getPosition().path("x").asInt());
        assertEquals("segredo", original.getContent(), "a entidade original não pode ser alterada");
        assertEquals("autor", original.getAuthorId());
        assertEquals(2, original.getVotes().size());
        assertNull(original.getHidden());
    }

    @Test
    @DisplayName("Ideias ocultas: o autor vê a própria; facilitador e ADMIN veem o texto")
    void hiddenIdeaVisibleToAuthorFacilitatorAdmin() {
        BrainstormingBoard hidden = board("{\"isRevealed\":false}");

        assertEquals("segredo", BrainstormingRedaction.redact(idea(), hidden, AUTHOR).getContent());
        assertEquals("autor", BrainstormingRedaction.redact(idea(), hidden, AUTHOR).getAuthorId());
        BrainstormingIdea forFacilitator = BrainstormingRedaction.redact(idea(), hidden, FACILITATOR);
        assertEquals("segredo", forFacilitator.getContent());
        assertNull(forFacilitator.getHidden());
        assertEquals("segredo", BrainstormingRedaction.redact(idea(), hidden, ADMIN).getContent());
    }

    @Test
    @DisplayName("Autores ocultos: texto visível, mas o autor só aparece para o próprio autor (nem facilitador nem ADMIN)")
    void authorsHiddenOnlyForAuthor() {
        BrainstormingBoard anonymous = board("{\"isAnonymous\":true}");

        assertEquals("autor", BrainstormingRedaction.redact(idea(), anonymous, AUTHOR).getAuthorId());
        for (CeremonyCaller caller : new CeremonyCaller[]{OTHER, FACILITATOR, ADMIN}) {
            BrainstormingIdea out = BrainstormingRedaction.redact(idea(), anonymous, caller);
            assertEquals("segredo", out.getContent());
            assertNull(out.getAuthorId());
            assertEquals(2, out.getVotes().size());
            assertNull(out.getHidden());
        }
    }

    @Test
    @DisplayName("Ocultas e anônimas ao mesmo tempo: o facilitador lê o texto mas não vê o autor")
    void hiddenAndAnonymousFacilitatorSeesTextNotAuthor() {
        BrainstormingIdea out = BrainstormingRedaction.redact(idea(), board("{\"isRevealed\":false,\"isAnonymous\":true}"), FACILITATOR);

        assertEquals("segredo", out.getContent());
        assertNull(out.getAuthorId());
    }

    @Test
    @DisplayName("Reveladas e com autores abertos (padrão): nada muda para ninguém")
    void defaultBoardUntouched() {
        BrainstormingIdea original = idea();
        for (String settings : new String[]{"{}", "{\"isRevealed\":true,\"isAnonymous\":false}"}) {
            assertSame(original, BrainstormingRedaction.redact(original, board(settings), OTHER));
        }
    }

    @Test
    @DisplayName("Mural ausente não quebra a saída")
    void nullBoard() {
        BrainstormingIdea original = idea();
        assertSame(original, BrainstormingRedaction.redact(original, null, OTHER));
    }
}
