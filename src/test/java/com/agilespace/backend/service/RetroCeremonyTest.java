package com.agilespace.backend.service;

import com.agilespace.backend.domain.RetroBoard;
import com.agilespace.backend.domain.RetroCard;
import com.agilespace.backend.domain.RetroColumnDef;
import com.agilespace.backend.domain.RetroParticipant;
import com.agilespace.backend.repository.RetroBoardRepository;
import com.agilespace.backend.repository.RetroCardRepository;
import com.agilespace.backend.repository.RetroChatMessageRepository;
import com.agilespace.backend.repository.RetroParticipantRepository;
import com.agilespace.backend.websocket.RetroTypingGate;
import com.agilespace.backend.websocket.RetroWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Retro: encerrar/reabrir (somente leitura para participantes), nota livre do check-in e a regra do "escrevendo…". */
@ExtendWith(MockitoExtension.class)
@DisplayName("RetroService - ciclo de vida, check-in livre e TYPING")
class RetroCeremonyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BOARD = "retro-9";
    private static final RetroCaller BOSS = new RetroCaller("boss", "MEMBER");
    private static final RetroCaller ADMIN = new RetroCaller("root", "ADMIN");
    private static final RetroCaller ANA = new RetroCaller("ana", "MEMBER");
    private static final RetroCaller INTRUSO = new RetroCaller("intruso", "MEMBER");

    @Mock private RetroBoardRepository boardRepository;
    @Mock private RetroParticipantRepository participantRepository;
    @Mock private RetroCardRepository cardRepository;
    @Mock private RetroChatMessageRepository chatMessageRepository;
    @Mock private RetroWebSocketHandler webSocketHandler;
    @Mock private SquadAccessService squadAccessService;

    @InjectMocks
    private RetroService service;

    private RetroBoard board;

    @BeforeEach
    void setUp() {
        board = RetroBoard.builder().id(BOARD).creatorId("boss").title("Retro").votingStatus("active").isCardsRevealed(true)
                .maxVotesPerParticipant(5)
                .columns(new ArrayList<>(Arrays.asList(
                        RetroColumnDef.builder().id("good").title("Bem").theme("success").columnOrder(0).build(),
                        RetroColumnDef.builder().id("actions").title("Ações").theme("action").columnOrder(1).build())))
                .build();
        lenient().when(boardRepository.findById(BOARD)).thenReturn(Optional.of(board));
        lenient().when(boardRepository.findByIdForUpdate(BOARD)).thenReturn(Optional.of(board));
        lenient().when(boardRepository.save(any(RetroBoard.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(cardRepository.save(any(RetroCard.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(participantRepository.save(any(RetroParticipant.class))).thenAnswer(i -> i.getArgument(0));
        for (String uid : List.of("ana", "bia")) {
            lenient().when(participantRepository.findByBoardIdAndId(BOARD, uid))
                    .thenReturn(Optional.of(RetroParticipant.builder().id(uid).boardId(BOARD).nickname(uid).build()));
        }
        lenient().when(participantRepository.findByBoardIdAndId(BOARD, "intruso")).thenReturn(Optional.empty());
    }

    private static void assertStatus(HttpStatus expected, org.junit.jupiter.api.function.Executable call) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, call);
        assertEquals(expected, ex.getStatusCode());
    }

    private static RetroCard card(String id, String author) {
        return RetroCard.builder().id(id).boardId(BOARD).columnKey("good").content("texto").authorId(author).order(1L)
                .votes(new LinkedHashSet<>()).build();
    }

    @Nested
    @DisplayName("Encerrar e reabrir")
    class FinishTests {

        @Test
        @DisplayName("Facilitador e ADMIN encerram: lifecycleStatus FINISHED, finishedAt gravado e BOARD_UPDATED publicado")
        void facilitatorAndAdminFinish() {
            RetroBoard saved = service.finish(BOARD, BOSS);

            assertEquals("FINISHED", saved.getLifecycleStatus());
            assertNotNull(saved.getFinishedAt());
            assertTrue(saved.isFinished());
            verify(webSocketHandler).broadcastEvent(eq(BOARD), eq("BOARD_UPDATED"), eq(saved));

            board.setLifecycle(null);
            board.setFinishedAt(null);
            assertEquals("FINISHED", service.finish(BOARD, ADMIN).getLifecycleStatus());
        }

        @Test
        @DisplayName("Participante comum e quem não é do quadro levam 403 ao encerrar ou reabrir")
        void othersAreForbidden() {
            assertStatus(HttpStatus.FORBIDDEN, () -> service.finish(BOARD, ANA));
            assertStatus(HttpStatus.FORBIDDEN, () -> service.finish(BOARD, INTRUSO));
            assertStatus(HttpStatus.FORBIDDEN, () -> service.reopen(BOARD, ANA));
            assertEquals("OPEN", board.getLifecycleStatus());
            verify(boardRepository, never()).save(any());
        }

        @Test
        @DisplayName("Encerrar é idempotente (não muda o instante) e reabrir zera lifecycle e finishedAt")
        void idempotentFinishAndReopen() {
            RetroBoard first = service.finish(BOARD, BOSS);
            LocalDateTime stamp = first.getFinishedAt();
            service.finish(BOARD, BOSS);
            assertEquals(stamp, board.getFinishedAt());
            verify(boardRepository, times(1)).save(any());

            RetroBoard reopened = service.reopen(BOARD, BOSS);
            assertEquals("OPEN", reopened.getLifecycleStatus());
            assertNull(reopened.getFinishedAt());
            service.reopen(BOARD, BOSS);
            verify(boardRepository, times(2)).save(any());
        }

        @Test
        @DisplayName("Quadro inexistente = 404")
        void unknownBoard() {
            when(boardRepository.findByIdForUpdate("nope")).thenReturn(Optional.empty());
            assertStatus(HttpStatus.NOT_FOUND, () -> service.finish("nope", BOSS));
        }

        @Test
        @DisplayName("Retro anterior à V79 (lifecycle nulo) é OPEN e o JSON traz lifecycleStatus e finishedAt nulo")
        void legacyBoardIsOpen() {
            JsonNode out = JSON.valueToTree(board);
            assertEquals("OPEN", out.get("lifecycleStatus").asText());
            assertTrue(out.get("finishedAt").isNull());
            assertFalse(out.has("lifecycle"), "a coluna interna não vai no JSON");
        }

        @Test
        @DisplayName("Corpo de POST/PATCH nunca encerra: lifecycleStatus e finishedAt do corpo são ignorados, inclusive na criação")
        void bodyCannotFinish() throws Exception {
            RetroBoard body = JSON.readValue("{\"title\":\"Nova\",\"lifecycleStatus\":\"FINISHED\",\"lifecycle\":\"FINISHED\","
                    + "\"finishedAt\":\"2020-01-01T00:00:00\"}", RetroBoard.class);
            assertEquals("OPEN", body.getLifecycleStatus());
            assertNull(body.getFinishedAt());

            body.setLifecycle("FINISHED");
            body.setFinishedAt(LocalDateTime.now());
            RetroService.Saved<RetroBoard> created = service.saveOrUpdateBoard(body, new RetroCaller("novo", "MEMBER"));
            assertTrue(created.created());
            assertEquals("OPEN", created.value().getLifecycleStatus());
            assertNull(created.value().getFinishedAt());
        }
    }

    @Nested
    @DisplayName("Quadro encerrado: somente leitura para participantes")
    class ReadOnlyTests {

        @BeforeEach
        void finishIt() {
            board.setLifecycle(RetroBoard.LIFECYCLE_FINISHED);
            board.setFinishedAt(LocalDateTime.now());
        }

        @Test
        @DisplayName("Participante não cria, edita, vota, funde, importa nem apaga card (409)")
        void participantWritesAreRejected() {
            RetroCard mine = card("c1", "ana");
            lenient().when(cardRepository.findById("c1")).thenReturn(Optional.of(mine));
            lenient().when(cardRepository.findById("c2")).thenReturn(Optional.of(card("c2", "bia")));
            RetroCard newCard = RetroCard.builder().id("c9").boardId(BOARD).columnKey("good").content("novo").build();

            assertStatus(HttpStatus.CONFLICT, () -> service.saveOrUpdateCard(newCard, ANA));
            assertStatus(HttpStatus.CONFLICT, () -> service.saveOrUpdateCard(
                    RetroCard.builder().id("c1").boardId(BOARD).columnKey("good").content("editado").build(), ANA));
            assertStatus(HttpStatus.CONFLICT, () -> service.toggleVote(BOARD, "c1", ANA));
            assertStatus(HttpStatus.CONFLICT, () -> service.mergeCards(BOARD, "c1", "c2", ANA));
            assertStatus(HttpStatus.CONFLICT, () -> service.importActions(BOARD, List.of(newCard), ANA));
            assertStatus(HttpStatus.CONFLICT, () -> service.deleteCard(BOARD, "c1", ANA));
            verify(cardRepository, never()).save(any());
            verify(cardRepository, never()).deleteById(anyString());
        }

        @Test
        @DisplayName("Facilitador e ADMIN continuam escrevendo no quadro encerrado")
        void facilitatorStillWrites() {
            RetroCard c = RetroCard.builder().id("c9").boardId(BOARD).columnKey("good").content("ajuste do facilitador").build();
            assertDoesNotThrow(() -> service.saveOrUpdateCard(c, BOSS));
            assertDoesNotThrow(() -> service.saveOrUpdateCard(
                    RetroCard.builder().id("c10").boardId(BOARD).columnKey("good").content("do admin").build(), ADMIN));
        }

        @Test
        @DisplayName("Reabrir devolve a escrita aos participantes")
        void reopenRestoresWriting() {
            service.reopen(BOARD, BOSS);
            RetroCard c = RetroCard.builder().id("c9").boardId(BOARD).columnKey("good").content("de novo").build();
            assertDoesNotThrow(() -> service.saveOrUpdateCard(c, ANA));
        }

        @Test
        @DisplayName("Leitura, entrada na sala e check-in não são bloqueados")
        void readsJoinAndCheckinAreOpen() {
            when(participantRepository.findById(BOARD + "_ana")).thenReturn(Optional.of(
                    RetroParticipant.builder().dbId(BOARD + "_ana").id("ana").boardId(BOARD).nickname("ana").build()));

            assertDoesNotThrow(() -> service.getCards(BOARD, ANA));
            assertDoesNotThrow(() -> service.addOrUpdateParticipant(
                    RetroParticipant.builder().boardId(BOARD).nickname("ana").healthCheckAnswer("good").checkinNote("ok").build(), ANA));
        }

        @Test
        @DisplayName("Retro nunca encerrada se comporta como antes")
        void openBoardUnchanged() {
            board.setLifecycle(null);
            board.setFinishedAt(null);
            RetroCard c = RetroCard.builder().id("c9").boardId(BOARD).columnKey("good").content("normal").build();
            assertDoesNotThrow(() -> service.saveOrUpdateCard(c, ANA));
        }
    }

    @Nested
    @DisplayName("Check-in com texto livre")
    class CheckinNoteTests {

        private RetroParticipant existing(String note) {
            return RetroParticipant.builder().dbId(BOARD + "_ana").id("ana").boardId(BOARD).nickname("Ana").checkinNote(note).build();
        }

        private RetroParticipant body(String note) {
            return RetroParticipant.builder().boardId(BOARD).nickname("Ana").checkinNote(note).build();
        }

        @Test
        @DisplayName("Grava a nota (aparada), mantém quando o campo vem ausente e limpa com vazio")
        void mergeSemantics() {
            RetroParticipant row = existing(null);
            when(participantRepository.findById(BOARD + "_ana")).thenReturn(Optional.of(row));

            service.addOrUpdateParticipant(body("  semana pesada  "), ANA);
            assertEquals("semana pesada", row.getCheckinNote());

            service.addOrUpdateParticipant(body(null), ANA);
            assertEquals("semana pesada", row.getCheckinNote(), "ausente não apaga");

            service.addOrUpdateParticipant(body("   "), ANA);
            assertNull(row.getCheckinNote(), "vazio limpa");
        }

        @Test
        @DisplayName("500 caracteres passam; 501 é 400")
        void limit() {
            RetroParticipant row = existing(null);
            when(participantRepository.findById(BOARD + "_ana")).thenReturn(Optional.of(row));

            service.addOrUpdateParticipant(body("x".repeat(500)), ANA);
            assertEquals(500, row.getCheckinNote().length());
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.addOrUpdateParticipant(body("x".repeat(501)), ANA));
        }

        @Test
        @DisplayName("Primeira entrada já grava a nota; nota vazia na criação vira nula")
        void createWithNote() {
            when(participantRepository.findById(BOARD + "_ana")).thenReturn(Optional.empty());
            RetroService.Saved<RetroParticipant> saved = service.addOrUpdateParticipant(body("oi"), ANA);
            assertEquals("oi", saved.value().getCheckinNote());

            when(participantRepository.findById(BOARD + "_bia")).thenReturn(Optional.empty());
            RetroService.Saved<RetroParticipant> empty = service.addOrUpdateParticipant(body(""), new RetroCaller("bia", "MEMBER"));
            assertNull(empty.value().getCheckinNote());
        }

        @Test
        @DisplayName("Corpo antigo (sem checkinNote) continua funcionando e o id é sempre o do chamador")
        void oldBodyStillWorks() {
            when(participantRepository.findById(BOARD + "_ana")).thenReturn(Optional.empty());
            RetroParticipant legacy = RetroParticipant.builder().boardId(BOARD).id("outra-pessoa").nickname("Ana").healthCheckAnswer("good").build();

            RetroParticipant saved = service.addOrUpdateParticipant(legacy, ANA).value();

            assertEquals("ana", saved.getId());
            assertNull(saved.getCheckinNote());
            assertEquals("good", saved.getHealthCheckAnswer());
        }
    }

    @Nested
    @DisplayName("Regra do TYPING (RetroTypingGate)")
    class TypingGateTests {

        @Test
        @DisplayName("Card antes da revelação dos autores sai anônimo; com autores revelados identifica")
        void cardAnonymityFollowsAuthorsRevealed() {
            board.setIsAuthorsRevealed(false);
            RetroTypingGate.Route hidden = service.route(BOARD, "ana", "MEMBER", "card", null).orElseThrow();
            assertTrue(hidden.anonymous());
            assertNull(hidden.audience(), "a sala inteira");

            board.setIsAuthorsRevealed(true);
            assertFalse(service.route(BOARD, "ana", "MEMBER", "card", null).orElseThrow().anonymous());
        }

        @Test
        @DisplayName("Quem não é do quadro, quadro inexistente e remetente nulo são descartados")
        void nonMembersAreDropped() {
            assertTrue(service.route(BOARD, "intruso", "MEMBER", "card", null).isEmpty());
            assertTrue(service.route("nope", "ana", "MEMBER", "card", null).isEmpty());
            assertTrue(service.route(BOARD, null, "MEMBER", "card", null).isEmpty());
        }

        @Test
        @DisplayName("Chat geral vai à sala; DM só às duas pessoas; canal sem acesso é descartado")
        void chatRespectsChannelAccess() {
            RetroParticipant bia = RetroParticipant.builder().id("bia").boardId(BOARD).nickname("bia").build();
            lenient().when(participantRepository.findByBoardIdAndId(BOARD, "bia")).thenReturn(Optional.of(bia));

            RetroTypingGate.Route general = service.route(BOARD, "ana", "MEMBER", "chat", "geral").orElseThrow();
            assertNull(general.audience());
            assertFalse(general.anonymous());

            RetroTypingGate.Route dm = service.route(BOARD, "ana", "MEMBER", "chat", "dm_ana_bia").orElseThrow();
            assertEquals(Set.of("ana", "bia"), dm.audience());

            assertTrue(service.route(BOARD, "ana", "MEMBER", "chat", "dm_bia_boss").isEmpty(), "DM alheio");
            assertTrue(service.route(BOARD, "ana", "MEMBER", "chat", "role-QA").isEmpty(), "canal de função sem acesso");
            assertTrue(service.route(BOARD, "ana", "MEMBER", "chat", "inventado").isEmpty());
        }
    }
}
