package com.agilespace.backend.service;

import com.agilespace.backend.domain.BrainstormingBoard;
import com.agilespace.backend.domain.BrainstormingGroup;
import com.agilespace.backend.domain.BrainstormingIdea;
import com.agilespace.backend.domain.BrainstormingParticipant;
import com.agilespace.backend.repository.BrainstormingBoardRepository;
import com.agilespace.backend.repository.BrainstormingGroupRepository;
import com.agilespace.backend.repository.BrainstormingIdeaRepository;
import com.agilespace.backend.repository.BrainstormingParticipantRepository;
import com.agilespace.backend.websocket.BrainstormingWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BrainstormingService - autorização, voto/fusão atômicos e eventos pós-commit")
class BrainstormingServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private BrainstormingBoardRepository boardRepository;
    @Mock private BrainstormingIdeaRepository ideaRepository;
    @Mock private BrainstormingGroupRepository groupRepository;
    @Mock private BrainstormingParticipantRepository participantRepository;
    @Mock private BrainstormingWebSocketHandler webSocketHandler;

    @InjectMocks
    private BrainstormingService service;

    private static final CeremonyCaller FACILITATOR = new CeremonyCaller("fac", "MEMBER");
    private static final CeremonyCaller OTHER = new CeremonyCaller("u2", "MEMBER");
    private static final CeremonyCaller ADMIN = new CeremonyCaller("adm", "ADMIN");

    @AfterEach
    void cleanSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static JsonNode json(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static BrainstormingBoard board() {
        return BrainstormingBoard.builder().id("b1").creatorId("fac").title("Sessão").phase("ideation")
                .settings(json("{\"isAnonymous\":true,\"isRevealed\":false}"))
                .timer(json("{\"status\":\"stopped\",\"endTime\":null,\"initialDuration\":600,\"remainingOnPause\":600}"))
                .build();
    }

    private static BrainstormingIdea idea(String id, String content, String author, String votesJson) {
        return BrainstormingIdea.builder().id(id).boardId("b1").content(content).authorId(author)
                .votes(json(votesJson)).position(json("{\"x\":1,\"y\":2}")).build();
    }

    private static void assertStatus(HttpStatus expected, Runnable action) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(expected, ex.getStatusCode());
    }

    @Nested
    @DisplayName("Mural")
    class BoardTests {

        @Test
        @DisplayName("Cria mural com criador vindo do login (o do corpo é ignorado) e publica BOARD_UPDATED")
        void createsBoardWithCreatorFromToken() {
            BrainstormingBoard body = BrainstormingBoard.builder().creatorId("impostor").title("  Ideias  ").phase("diagram").build();
            when(boardRepository.save(any(BrainstormingBoard.class))).thenAnswer(i -> i.getArgument(0));

            BrainstormingService.Saved<BrainstormingBoard> saved = service.saveOrUpdateBoard(body, FACILITATOR);

            assertTrue(saved.created());
            assertEquals("fac", saved.value().getCreatorId());
            assertEquals("Ideias", saved.value().getTitle());
            assertEquals("diagram", saved.value().getPhase());
            assertNotNull(saved.value().getId());
            assertEquals("stopped", saved.value().getTimer().path("status").asText());
            verify(webSocketHandler).broadcastEvent(eq(saved.value().getId()), eq("BOARD_UPDATED"), eq(saved.value()));
        }

        @Test
        @DisplayName("Mural sem título é recusado")
        void rejectsBoardWithoutTitle() {
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.saveOrUpdateBoard(BrainstormingBoard.builder().title("  ").build(), FACILITATOR));
            verify(boardRepository, never()).save(any());
        }

        @Test
        @DisplayName("Quem não é facilitador não regrava mural existente")
        void nonFacilitatorCannotRewriteExistingBoard() {
            when(boardRepository.findByIdForUpdate("b1")).thenReturn(Optional.of(board()));

            assertStatus(HttpStatus.FORBIDDEN, () -> service.saveOrUpdateBoard(BrainstormingBoard.builder().id("b1").title("x").build(), OTHER));
            verify(boardRepository, never()).save(any());
        }

        @Test
        @DisplayName("PATCH mescla as configurações chave a chave (alternar anonimato não desfaz 'ocultar ideias')")
        void patchMergesSettingsKeyByKey() {
            BrainstormingBoard board = board();
            when(boardRepository.findByIdForUpdate("b1")).thenReturn(Optional.of(board));
            when(boardRepository.save(any(BrainstormingBoard.class))).thenAnswer(i -> i.getArgument(0));

            BrainstormingBoard saved = service.patchBoard("b1", json("{\"settings\":{\"isAnonymous\":false,\"hack\":true}}"), FACILITATOR);

            assertFalse(saved.getSettings().path("isAnonymous").asBoolean());
            assertFalse(saved.getSettings().path("isRevealed").asBoolean(true), "isRevealed continua como estava");
            assertFalse(saved.getSettings().has("hack"), "chaves desconhecidas são descartadas");
            assertEquals("ideation", saved.getPhase(), "campo não enviado não muda");
        }

        @Test
        @DisplayName("PATCH: participante comum recebe 403; ADMIN pode")
        void patchRequiresFacilitatorOrAdmin() {
            when(boardRepository.findByIdForUpdate("b1")).thenReturn(Optional.of(board()));

            assertStatus(HttpStatus.FORBIDDEN, () -> service.patchBoard("b1", json("{\"phase\":\"grouping\"}"), OTHER));

            when(boardRepository.save(any(BrainstormingBoard.class))).thenAnswer(i -> i.getArgument(0));
            assertEquals("grouping", service.patchBoard("b1", json("{\"phase\":\"grouping\"}"), ADMIN).getPhase());
        }

        @Test
        @DisplayName("PATCH valida fase e timer")
        void patchValidatesPhaseAndTimer() {
            when(boardRepository.findByIdForUpdate("b1")).thenReturn(Optional.of(board()));

            assertStatus(HttpStatus.BAD_REQUEST, () -> service.patchBoard("b1", json("{\"phase\":\"nope\"}"), FACILITATOR));
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.patchBoard("b1", json("{\"timer\":{\"status\":\"zzz\",\"initialDuration\":60}}"), FACILITATOR));
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.patchBoard("b1", json("{\"timer\":{\"status\":\"running\",\"initialDuration\":0}}"), FACILITATOR));
        }

        @Test
        @DisplayName("PATCH grava timer rodando com endTime")
        void patchStoresTimer() {
            when(boardRepository.findByIdForUpdate("b1")).thenReturn(Optional.of(board()));
            when(boardRepository.save(any(BrainstormingBoard.class))).thenAnswer(i -> i.getArgument(0));

            BrainstormingBoard saved = service.patchBoard("b1",
                    json("{\"timer\":{\"status\":\"running\",\"endTime\":1700000000000,\"initialDuration\":300,\"remainingOnPause\":300}}"), FACILITATOR);

            assertEquals("running", saved.getTimer().path("status").asText());
            assertEquals(1700000000000L, saved.getTimer().path("endTime").asLong());
        }

        @Test
        @DisplayName("Só facilitador/ADMIN apaga o mural; apagar cascateia ideias, grupos e participantes")
        void deleteBoardAuthorizationAndCascade() {
            when(boardRepository.findByIdForUpdate("b1")).thenReturn(Optional.of(board()));

            assertStatus(HttpStatus.FORBIDDEN, () -> service.deleteBoard("b1", OTHER));
            verify(ideaRepository, never()).deleteByBoardId(anyString());

            service.deleteBoard("b1", FACILITATOR);

            verify(ideaRepository).deleteByBoardId("b1");
            verify(groupRepository).deleteByBoardId("b1");
            verify(participantRepository).deleteByBoardId("b1");
            verify(boardRepository).deleteById("b1");
            verify(webSocketHandler).broadcastEvent(eq("b1"), eq("BOARD_DELETED"), any());
        }

        @Test
        @DisplayName("Mural inexistente devolve 404")
        void unknownBoardIs404() {
            when(boardRepository.findByIdForUpdate("zz")).thenReturn(Optional.empty());

            assertStatus(HttpStatus.NOT_FOUND, () -> service.patchBoard("zz", json("{}"), FACILITATOR));
        }
    }

    @Nested
    @DisplayName("Participantes")
    class ParticipantTests {

        @Test
        @DisplayName("Entrada usa o id do login e marca o criador; o id do corpo é ignorado")
        void joinUsesCallerId() {
            when(boardRepository.findById("b1")).thenReturn(Optional.of(board()));
            when(participantRepository.findByBoardIdAndId("b1", "fac")).thenReturn(Optional.empty());
            when(participantRepository.save(any(BrainstormingParticipant.class))).thenAnswer(i -> i.getArgument(0));
            BrainstormingParticipant body = BrainstormingParticipant.builder().id("outro").nickname("Ana").role("DEV").isCreator(false).build();

            BrainstormingParticipant saved = service.joinBoard("b1", body, FACILITATOR);

            assertEquals("fac", saved.getId());
            assertEquals("b1_fac", saved.getDbId());
            assertTrue(saved.getIsCreator());
            assertEquals("Ana", saved.getNickname());
        }

        @Test
        @DisplayName("Sair: a própria pessoa e o facilitador podem; outro participante não")
        void leaveRules() {
            when(boardRepository.findById("b1")).thenReturn(Optional.of(board()));

            assertStatus(HttpStatus.FORBIDDEN, () -> service.leaveBoard("b1", "fac", OTHER));
            verify(participantRepository, never()).deleteByBoardIdAndId(anyString(), anyString());

            service.leaveBoard("b1", "u2", OTHER);
            service.leaveBoard("b1", "u2", FACILITATOR);
            verify(participantRepository, times(2)).deleteByBoardIdAndId("b1", "u2");
        }
    }

    @Nested
    @DisplayName("Ideias")
    class IdeaTests {

        @Test
        @DisplayName("Ideia nova: autor é quem chama e os votos começam vazios, mesmo que o corpo traga outros")
        void newIdeaAuthorAndVotesFromServer() {
            when(boardRepository.findById("b1")).thenReturn(Optional.of(board()));
            when(ideaRepository.save(any(BrainstormingIdea.class))).thenAnswer(i -> i.getArgument(0));
            BrainstormingIdea body = BrainstormingIdea.builder().content("  Migrar  ").authorId("impostor")
                    .votes(json("[\"a\",\"b\",\"c\"]")).boardId("outro").build();

            BrainstormingService.Saved<BrainstormingIdea> saved = service.saveOrUpdateIdea("b1", body, OTHER);

            assertTrue(saved.created());
            assertEquals("u2", saved.value().getAuthorId());
            assertEquals("b1", saved.value().getBoardId());
            assertEquals("Migrar", saved.value().getContent());
            assertEquals(0, saved.value().getVotes().size());
            verify(webSocketHandler).broadcastEvent(eq("b1"), eq("IDEA_SAVED"), eq(saved.value()));
        }

        @Test
        @DisplayName("Ideia sem texto é recusada")
        void emptyContentRejected() {
            when(boardRepository.findById("b1")).thenReturn(Optional.of(board()));

            assertStatus(HttpStatus.BAD_REQUEST, () -> service.saveOrUpdateIdea("b1", BrainstormingIdea.builder().content("   ").build(), OTHER));
        }

        @Test
        @DisplayName("Gravar ideia inteira (aba antiga) não altera votos nem autor; ideia de outro mural dá 409")
        void legacyWholeObjectWriteKeepsVotes() {
            when(boardRepository.findById("b1")).thenReturn(Optional.of(board()));
            BrainstormingIdea stored = idea("i1", "Texto", "u9", "[\"x\",\"y\"]");
            when(ideaRepository.findByIdForUpdate("i1")).thenReturn(Optional.of(stored));
            when(ideaRepository.save(any(BrainstormingIdea.class))).thenAnswer(i -> i.getArgument(0));
            BrainstormingIdea body = BrainstormingIdea.builder().id("i1").content("Novo texto").authorId("impostor")
                    .votes(json("[]")).position(json("{\"x\":5,\"y\":6}")).build();

            BrainstormingService.Saved<BrainstormingIdea> saved = service.saveOrUpdateIdea("b1", body, OTHER);

            assertFalse(saved.created());
            assertEquals("Novo texto", saved.value().getContent());
            assertEquals("u9", saved.value().getAuthorId());
            assertEquals(2, saved.value().getVotes().size());
            assertEquals(5, saved.value().getPosition().path("x").asInt());

            BrainstormingIdea foreign = idea("i7", "x", "u1", "[]");
            foreign.setBoardId("b-outro");
            when(ideaRepository.findByIdForUpdate("i7")).thenReturn(Optional.of(foreign));
            assertStatus(HttpStatus.CONFLICT, () -> service.saveOrUpdateIdea("b1", BrainstormingIdea.builder().id("i7").content("x").build(), OTHER));
        }

        @Test
        @DisplayName("PATCH altera só os campos enviados e preserva votos")
        void patchChangesOnlySentFields() {
            when(boardRepository.findById("b1")).thenReturn(Optional.of(board()));
            BrainstormingIdea stored = idea("i1", "Texto", "u9", "[\"x\"]");
            stored.setGroupId("g1");
            when(ideaRepository.findByIdForUpdate("i1")).thenReturn(Optional.of(stored));
            when(ideaRepository.save(any(BrainstormingIdea.class))).thenAnswer(i -> i.getArgument(0));

            BrainstormingIdea saved = service.patchIdea("b1", "i1", json("{\"position\":{\"x\":10,\"y\":20},\"qualifiers\":{\"roi\":80,\"effort\":20}}"), OTHER);

            assertEquals(10, saved.getPosition().path("x").asInt());
            assertEquals(80, saved.getQualifiers().path("roi").asInt());
            assertEquals("Texto", saved.getContent());
            assertEquals("g1", saved.getGroupId());
            assertEquals(1, saved.getVotes().size());
        }

        @Test
        @DisplayName("PATCH: ideia de outro mural vira 404 e ROI fora de 0..100 vira 400")
        void patchRejectsForeignIdeaAndBadQualifiers() {
            when(boardRepository.findById("b1")).thenReturn(Optional.of(board()));
            BrainstormingIdea foreign = idea("i7", "x", "u1", "[]");
            foreign.setBoardId("b-outro");
            when(ideaRepository.findByIdForUpdate("i7")).thenReturn(Optional.of(foreign));
            assertStatus(HttpStatus.NOT_FOUND, () -> service.patchIdea("b1", "i7", json("{\"content\":\"x\"}"), OTHER));

            when(ideaRepository.findByIdForUpdate("i1")).thenReturn(Optional.of(idea("i1", "T", "u1", "[]")));
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.patchIdea("b1", "i1", json("{\"qualifiers\":{\"roi\":150}}"), OTHER));
        }

        @Test
        @DisplayName("PATCH: grupo de outro mural é recusado e ligação que fecha ciclo é recusada")
        void patchValidatesGroupAndParent() {
            when(boardRepository.findById("b1")).thenReturn(Optional.of(board()));
            when(ideaRepository.findByIdForUpdate("i1")).thenReturn(Optional.of(idea("i1", "T", "u1", "[]")));
            BrainstormingGroup foreignGroup = BrainstormingGroup.builder().id("g9").boardId("b-outro").build();
            when(groupRepository.findById("g9")).thenReturn(Optional.of(foreignGroup));
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.patchIdea("b1", "i1", json("{\"groupId\":\"g9\"}"), OTHER));

            BrainstormingIdea child = idea("i2", "C", "u1", "[]");
            child.setParentId("i1");
            when(ideaRepository.findById("i2")).thenReturn(Optional.of(child));
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.patchIdea("b1", "i1", json("{\"parentId\":\"i2\"}"), OTHER));
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.patchIdea("b1", "i1", json("{\"parentId\":\"i1\"}"), OTHER));
        }

        @Test
        @DisplayName("PATCH com parentId null desliga a ligação")
        void patchClearsParent() {
            when(boardRepository.findById("b1")).thenReturn(Optional.of(board()));
            BrainstormingIdea stored = idea("i1", "T", "u1", "[]");
            stored.setParentId("i0");
            when(ideaRepository.findByIdForUpdate("i1")).thenReturn(Optional.of(stored));
            when(ideaRepository.save(any(BrainstormingIdea.class))).thenAnswer(i -> i.getArgument(0));

            assertNull(service.patchIdea("b1", "i1", json("{\"parentId\":null}"), OTHER).getParentId());
        }
    }

    @Nested
    @DisplayName("Voto atômico")
    class VoteTests {

        @Test
        @DisplayName("Voto liga e desliga para quem chama, lendo a ideia com lock (sem sobrescrever votos de outros)")
        void toggleVoteAddsAndRemoves() {
            when(boardRepository.findById("b1")).thenReturn(Optional.of(board()));
            BrainstormingIdea stored = idea("i1", "T", "u1", "[\"a\",\"b\"]");
            when(ideaRepository.findByIdForUpdate("i1")).thenReturn(Optional.of(stored));
            when(ideaRepository.save(any(BrainstormingIdea.class))).thenAnswer(i -> i.getArgument(0));

            BrainstormingIdea voted = service.toggleVote("b1", "i1", OTHER);
            assertEquals(List.of("a", "b", "u2"), texts(voted.getVotes()));

            BrainstormingIdea unvoted = service.toggleVote("b1", "i1", OTHER);
            assertEquals(List.of("a", "b"), texts(unvoted.getVotes()));
            verify(ideaRepository, times(2)).findByIdForUpdate("i1");
            verify(webSocketHandler, times(2)).broadcastEvent(eq("b1"), eq("IDEA_SAVED"), any());
        }

        @Test
        @DisplayName("Voto em ideia de outro mural ou inexistente vira 404")
        void voteOnForeignIdea() {
            when(boardRepository.findById("b1")).thenReturn(Optional.of(board()));
            BrainstormingIdea foreign = idea("i7", "x", "u1", "[]");
            foreign.setBoardId("b-outro");
            when(ideaRepository.findByIdForUpdate("i7")).thenReturn(Optional.of(foreign));
            when(ideaRepository.findByIdForUpdate("nada")).thenReturn(Optional.empty());

            assertStatus(HttpStatus.NOT_FOUND, () -> service.toggleVote("b1", "i7", OTHER));
            assertStatus(HttpStatus.NOT_FOUND, () -> service.toggleVote("b1", "nada", OTHER));
        }

        private List<String> texts(JsonNode array) {
            List<String> out = new ArrayList<>();
            array.forEach(n -> out.add(n.asText()));
            return out;
        }
    }

    @Nested
    @DisplayName("Fusão")
    class MergeTests {

        @Test
        @DisplayName("Funde texto, une votos sem repetir, move filhos para o destino e apaga a origem")
        void mergesInOneOperation() {
            when(boardRepository.findById("b1")).thenReturn(Optional.of(board()));
            BrainstormingIdea target = idea("a", "Alvo", "u1", "[\"x\",\"y\"]");
            BrainstormingIdea source = idea("b", "Origem", "u2", "[\"y\",\"z\"]");
            BrainstormingIdea child = idea("c", "Filha", "u3", "[]");
            child.setParentId("b");
            when(ideaRepository.findByIdForUpdate("a")).thenReturn(Optional.of(target));
            when(ideaRepository.findByIdForUpdate("b")).thenReturn(Optional.of(source));
            when(ideaRepository.findByBoardIdAndParentId("b1", "b")).thenReturn(List.of(child));
            when(ideaRepository.save(any(BrainstormingIdea.class))).thenAnswer(i -> i.getArgument(0));

            BrainstormingIdea merged = service.mergeIdeas("b1", "a", "b", OTHER);

            assertEquals("Alvo\n- Origem", merged.getContent());
            assertEquals(3, merged.getVotes().size(), "quem votou nas duas conta uma vez");
            assertEquals("a", child.getParentId());
            verify(ideaRepository).deleteById("b");
            verify(webSocketHandler).broadcastEvent(eq("b1"), eq("IDEA_DELETED"), any());
        }

        @Test
        @DisplayName("Fusão em ordem de lock fixa: destino 'b' e origem 'a' também funciona")
        void mergeWithReversedIds() {
            when(boardRepository.findById("b1")).thenReturn(Optional.of(board()));
            BrainstormingIdea target = idea("b", "Alvo", "u1", "[]");
            BrainstormingIdea source = idea("a", "Origem", "u2", "[]");
            when(ideaRepository.findByIdForUpdate("a")).thenReturn(Optional.of(source));
            when(ideaRepository.findByIdForUpdate("b")).thenReturn(Optional.of(target));
            when(ideaRepository.findByBoardIdAndParentId("b1", "a")).thenReturn(List.of());
            when(ideaRepository.save(any(BrainstormingIdea.class))).thenAnswer(i -> i.getArgument(0));

            BrainstormingIdea merged = service.mergeIdeas("b1", "b", "a", OTHER);

            assertEquals("b", merged.getId());
            assertEquals("Alvo\n- Origem", merged.getContent());
            verify(ideaRepository).deleteById("a");
        }

        @Test
        @DisplayName("Fundir uma ideia nela mesma ou estourar o limite de texto é recusado")
        void mergeValidation() {
            when(boardRepository.findById("b1")).thenReturn(Optional.of(board()));
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.mergeIdeas("b1", "a", "a", OTHER));

            BrainstormingIdea target = idea("a", "x".repeat(3000), "u1", "[]");
            BrainstormingIdea source = idea("b", "y".repeat(3000), "u2", "[]");
            when(ideaRepository.findByIdForUpdate("a")).thenReturn(Optional.of(target));
            when(ideaRepository.findByIdForUpdate("b")).thenReturn(Optional.of(source));
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.mergeIdeas("b1", "a", "b", OTHER));
            verify(ideaRepository, never()).deleteById(anyString());
        }
    }

    @Nested
    @DisplayName("Exclusão de ideia e grupos")
    class DeleteTests {

        @Test
        @DisplayName("Apagar ideia: autor, facilitador ou ADMIN; outro participante recebe 403")
        void deleteIdeaAuthorization() {
            when(boardRepository.findById("b1")).thenReturn(Optional.of(board()));
            when(ideaRepository.findByIdForUpdate("i1")).thenReturn(Optional.of(idea("i1", "T", "u2", "[]")));
            when(ideaRepository.findByBoardIdAndParentId("b1", "i1")).thenReturn(List.of());

            assertStatus(HttpStatus.FORBIDDEN, () -> service.deleteIdea("b1", "i1", new CeremonyCaller("u3", "MEMBER")));
            verify(ideaRepository, never()).deleteById(anyString());

            service.deleteIdea("b1", "i1", OTHER);
            service.deleteIdea("b1", "i1", FACILITATOR);
            service.deleteIdea("b1", "i1", ADMIN);
            verify(ideaRepository, times(3)).deleteById("i1");
        }

        @Test
        @DisplayName("Apagar ideia solta a ligação das filhas e publica cada uma")
        void deleteIdeaDetachesChildren() {
            when(boardRepository.findById("b1")).thenReturn(Optional.of(board()));
            when(ideaRepository.findByIdForUpdate("i1")).thenReturn(Optional.of(idea("i1", "T", "u2", "[]")));
            BrainstormingIdea child = idea("i2", "C", "u2", "[]");
            child.setParentId("i1");
            when(ideaRepository.findByBoardIdAndParentId("b1", "i1")).thenReturn(List.of(child));
            when(ideaRepository.save(any(BrainstormingIdea.class))).thenAnswer(i -> i.getArgument(0));

            service.deleteIdea("b1", "i1", OTHER);

            assertNull(child.getParentId());
            verify(webSocketHandler).broadcastEvent(eq("b1"), eq("IDEA_SAVED"), eq(child));
            verify(webSocketHandler).broadcastEvent(eq("b1"), eq("IDEA_DELETED"), any());
        }

        @Test
        @DisplayName("Apagar grupo devolve as ideias a 'Sem grupo' e publica cada ideia e o grupo apagado")
        void deleteGroupReleasesIdeas() {
            when(boardRepository.findById("b1")).thenReturn(Optional.of(board()));
            when(groupRepository.findById("g1")).thenReturn(Optional.of(BrainstormingGroup.builder().id("g1").boardId("b1").build()));
            BrainstormingIdea inGroup = idea("i1", "T", "u1", "[]");
            inGroup.setGroupId("g1");
            when(ideaRepository.findByBoardIdAndGroupId("b1", "g1")).thenReturn(List.of(inGroup));
            when(ideaRepository.save(any(BrainstormingIdea.class))).thenAnswer(i -> i.getArgument(0));

            service.deleteGroup("b1", "g1", OTHER);

            assertNull(inGroup.getGroupId());
            verify(groupRepository).deleteById("g1");
            verify(webSocketHandler).broadcastEvent(eq("b1"), eq("IDEA_SAVED"), eq(inGroup));
            verify(webSocketHandler).broadcastEvent(eq("b1"), eq("GROUP_DELETED"), any());
        }

        @Test
        @DisplayName("Grupo de outro mural não é apagado")
        void deleteForeignGroup() {
            when(boardRepository.findById("b1")).thenReturn(Optional.of(board()));
            when(groupRepository.findById("g9")).thenReturn(Optional.of(BrainstormingGroup.builder().id("g9").boardId("b-outro").build()));

            assertStatus(HttpStatus.NOT_FOUND, () -> service.deleteGroup("b1", "g9", OTHER));
            verify(groupRepository, never()).deleteById(anyString());
        }

        @Test
        @DisplayName("Salvar grupo exige nome e não toma grupo de outro mural")
        void saveGroupValidation() {
            when(boardRepository.findById("b1")).thenReturn(Optional.of(board()));
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.saveOrUpdateGroup("b1", BrainstormingGroup.builder().title(" ").build(), OTHER));

            when(groupRepository.findById("g9")).thenReturn(Optional.of(BrainstormingGroup.builder().id("g9").boardId("b-outro").title("x").build()));
            assertStatus(HttpStatus.CONFLICT, () -> service.saveOrUpdateGroup("b1", BrainstormingGroup.builder().id("g9").title("novo").build(), OTHER));

            when(groupRepository.findByBoardIdOrderByOrderAsc("b1")).thenReturn(List.of());
            when(groupRepository.save(any(BrainstormingGroup.class))).thenAnswer(i -> i.getArgument(0));
            BrainstormingService.Saved<BrainstormingGroup> saved = service.saveOrUpdateGroup("b1", BrainstormingGroup.builder().title(" Tema ").build(), OTHER);
            assertTrue(saved.created());
            assertEquals("Tema", saved.value().getTitle());
            assertEquals(0, saved.value().getOrder());
        }
    }

    @Nested
    @DisplayName("Eventos do WebSocket")
    class PublishTests {

        @Test
        @DisplayName("Dentro de transação o evento só sai depois do commit")
        void publishesOnlyAfterCommit() {
            TransactionSynchronizationManager.initSynchronization();
            when(boardRepository.findById("b1")).thenReturn(Optional.of(board()));
            when(ideaRepository.findByIdForUpdate("i1")).thenReturn(Optional.of(idea("i1", "T", "u1", "[]")));
            when(ideaRepository.save(any(BrainstormingIdea.class))).thenAnswer(i -> i.getArgument(0));

            service.toggleVote("b1", "i1", OTHER);

            verify(webSocketHandler, never()).broadcastEvent(anyString(), anyString(), any());
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            verify(webSocketHandler).broadcastEvent(eq("b1"), eq("IDEA_SAVED"), any());
        }
    }
}
