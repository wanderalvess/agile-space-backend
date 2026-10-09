package com.agilespace.backend.service;

import com.agilespace.backend.domain.RetroBoard;
import com.agilespace.backend.domain.RetroCard;
import com.agilespace.backend.domain.RetroColumnDef;
import com.agilespace.backend.domain.RetroParticipant;
import com.agilespace.backend.repository.RetroBoardRepository;
import com.agilespace.backend.repository.RetroCardRepository;
import com.agilespace.backend.repository.RetroChatMessageRepository;
import com.agilespace.backend.repository.RetroParticipantRepository;
import com.agilespace.backend.websocket.RetroWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RetroService - Quadros, participantes, cartões, votos e autorização")
class RetroServiceTest {

    @Mock private RetroBoardRepository boardRepository;
    @Mock private RetroParticipantRepository participantRepository;
    @Mock private RetroCardRepository cardRepository;
    @Mock private RetroChatMessageRepository chatMessageRepository;
    @Mock private RetroWebSocketHandler webSocketHandler;
    @Mock private SquadAccessService squadAccessService;

    @InjectMocks
    private RetroService service;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BOARD = "retro-123";

    private static final RetroCaller CREATOR = new RetroCaller("boss", "MEMBER");
    private static final RetroCaller ADMIN = new RetroCaller("root", "ADMIN");
    private static final RetroCaller ANA = new RetroCaller("ana", "MEMBER");
    private static final RetroCaller BIA = new RetroCaller("bia", "MEMBER");
    private static final RetroCaller INTRUSO = new RetroCaller("intruso", "MEMBER");

    private RetroBoard board;

    @BeforeEach
    void setUp() {
        board = RetroBoard.builder()
                .id(BOARD)
                .creatorId("boss")
                .title("Retrospectiva Sprint 45")
                .votingStatus("active")
                .isCardsRevealed(true)
                .maxVotesPerParticipant(2)
                .columns(new ArrayList<>(Arrays.asList(
                        RetroColumnDef.builder().id("good").title("Bem").theme("success").columnOrder(0).build(),
                        RetroColumnDef.builder().id("bad").title("Melhorar").theme("warning").columnOrder(1).build(),
                        RetroColumnDef.builder().id("actions").title("Ações").theme("action").columnOrder(2).build())))
                .build();
        lenient().when(boardRepository.findById(BOARD)).thenReturn(Optional.of(board));
        lenient().when(boardRepository.findByIdForUpdate(BOARD)).thenReturn(Optional.of(board));
        lenient().when(boardRepository.save(any(RetroBoard.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(cardRepository.save(any(RetroCard.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(participantRepository.save(any(RetroParticipant.class))).thenAnswer(i -> i.getArgument(0));
        // boss é o criador; ana e bia são participantes; intruso não é nada
        for (String uid : List.of("ana", "bia")) {
            lenient().when(participantRepository.findByBoardIdAndId(BOARD, uid))
                    .thenReturn(Optional.of(RetroParticipant.builder().id(uid).boardId(BOARD).nickname(uid).build()));
        }
        lenient().when(participantRepository.findByBoardIdAndId(BOARD, "intruso")).thenReturn(Optional.empty());
    }

    @AfterEach
    void clearSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static RetroCard card(String id, String column, String author, String... votes) {
        return RetroCard.builder().id(id).boardId(BOARD).columnKey(column).content("texto " + id)
                .authorId(author).order(1L).votes(new LinkedHashSet<>(Arrays.asList(votes))).build();
    }

    private static JsonNode json(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertStatus(HttpStatus expected, org.junit.jupiter.api.function.Executable call) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, call);
        assertEquals(expected, ex.getStatusCode());
    }

    // ---------------------------------------------------------------- votos

    @Nested
    @DisplayName("Voto (POST /cards/{id}/vote)")
    class VoteTests {

        @Test
        @DisplayName("alterna: adiciona e depois remove o voto do chamador, e publica CARD_SAVED")
        void togglesVote() {
            RetroCard c = card("c1", "good", "bia");
            when(cardRepository.findById("c1")).thenReturn(Optional.of(c));
            when(cardRepository.findByBoardId(BOARD)).thenReturn(List.of(c));

            RetroCard afterAdd = service.toggleVote(BOARD, "c1", ANA);
            assertEquals(Set.of("ana"), afterAdd.getVotes());

            RetroCard afterRemove = service.toggleVote(BOARD, "c1", ANA);
            assertTrue(afterRemove.getVotes().isEmpty());
            verify(webSocketHandler, times(2)).broadcastEvent(eq(BOARD), eq("CARD_SAVED"), any());
        }

        @Test
        @DisplayName("usa o lock pessimista do board")
        void locksBoardRow() {
            RetroCard c = card("c1", "good", "bia");
            when(cardRepository.findById("c1")).thenReturn(Optional.of(c));
            when(cardRepository.findByBoardId(BOARD)).thenReturn(List.of(c));

            service.toggleVote(BOARD, "c1", ANA);

            verify(boardRepository).findByIdForUpdate(BOARD);
            verify(boardRepository, never()).findById(BOARD);
        }

        @Test
        @DisplayName("limite é por painel (coluna): 3º voto na mesma coluna é 409, em outra coluna passa")
        void limitIsPerColumn() {
            RetroCard g1 = card("g1", "good", "bia", "ana");
            RetroCard g2 = card("g2", "good", "bia", "ana");
            RetroCard g3 = card("g3", "good", "bia");
            RetroCard b1 = card("b1", "bad", "bia");
            when(cardRepository.findById("g3")).thenReturn(Optional.of(g3));
            when(cardRepository.findById("b1")).thenReturn(Optional.of(b1));
            when(cardRepository.findByBoardId(BOARD)).thenReturn(List.of(g1, g2, g3, b1));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.toggleVote(BOARD, "g3", ANA));
            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
            assertEquals("Você já usou seus 2 votos neste painel.", ex.getReason());
            assertTrue(g3.getVotes().isEmpty());

            assertTrue(service.toggleVote(BOARD, "b1", ANA).getVotes().contains("ana"));
        }

        @Test
        @DisplayName("remover voto é sempre permitido mesmo no limite")
        void removingAtLimitAllowed() {
            RetroCard g1 = card("g1", "good", "bia", "ana");
            RetroCard g2 = card("g2", "good", "bia", "ana");
            when(cardRepository.findById("g1")).thenReturn(Optional.of(g1));

            assertFalse(service.toggleVote(BOARD, "g1", ANA).getVotes().contains("ana"));
        }

        @Test
        @DisplayName("maxVotesPerParticipant = 0 significa sem limite")
        void zeroMeansUnlimited() {
            board.setMaxVotesPerParticipant(0);
            RetroCard c = card("c9", "good", "bia");
            when(cardRepository.findById("c9")).thenReturn(Optional.of(c));

            assertTrue(service.toggleVote(BOARD, "c9", ANA).getVotes().contains("ana"));
        }

        @Test
        @DisplayName("votação fechada (disabled/finished) devolve 409 e não grava")
        void closedVotingRejected() {
            RetroCard c = card("c1", "good", "bia");
            when(cardRepository.findById("c1")).thenReturn(Optional.of(c));
            for (String status : List.of("disabled", "finished")) {
                board.setVotingStatus(status);
                assertStatus(HttpStatus.CONFLICT, () -> service.toggleVote(BOARD, "c1", ANA));
            }
            verify(cardRepository, never()).save(any());
        }

        @Test
        @DisplayName("card de coluna de ação não recebe voto (409)")
        void actionColumnRejected() {
            RetroCard c = card("a1", "actions", "bia");
            when(cardRepository.findById("a1")).thenReturn(Optional.of(c));

            assertStatus(HttpStatus.CONFLICT, () -> service.toggleVote(BOARD, "a1", ANA));
        }

        @Test
        @DisplayName("quem não é do board recebe 403; card de outro board é 404")
        void authorization() {
            assertStatus(HttpStatus.FORBIDDEN, () -> service.toggleVote(BOARD, "c1", INTRUSO));

            RetroCard other = card("c2", "good", "bia");
            other.setBoardId("outro");
            when(cardRepository.findById("c2")).thenReturn(Optional.of(other));
            assertStatus(HttpStatus.NOT_FOUND, () -> service.toggleVote(BOARD, "c2", ANA));
        }

        @Test
        @DisplayName("o voto é do JWT: o corpo não participa e o mesmo usuário nunca duplica")
        void voteIsAlwaysCallerAndNeverDuplicated() {
            RetroCard c = card("c1", "good", "bia");
            when(cardRepository.findById("c1")).thenReturn(Optional.of(c));
            when(cardRepository.findByBoardId(BOARD)).thenReturn(List.of(c));

            service.toggleVote(BOARD, "c1", ANA);
            c.getVotes().add("ana"); // tentativa de duplicar: Set não aceita
            assertEquals(1, c.getVotes().size());
        }

        @Test
        @DisplayName("concorrência simulada: lock do board serializa e nenhum voto se perde nem passa do limite")
        void concurrentVotesAreSerializedByBoardLock() throws Exception {
            board.setMaxVotesPerParticipant(1);
            RetroCard c = card("c1", "good", "bia");
            // o card é um objeto compartilhado; o lock do board (simulado por ReentrantLock) serializa o acesso,
            // como o SELECT ... FOR UPDATE faz no banco
            ReentrantLock rowLock = new ReentrantLock();
            when(boardRepository.findByIdForUpdate(BOARD)).thenAnswer(inv -> {
                rowLock.lock();
                return Optional.of(board);
            });
            when(cardRepository.findById("c1")).thenReturn(Optional.of(c));
            when(cardRepository.findByBoardId(BOARD)).thenAnswer(inv -> List.of(c));
            when(cardRepository.save(any(RetroCard.class))).thenAnswer(inv -> {
                rowLock.unlock(); // "commit": libera a linha do board
                return inv.getArgument(0);
            });

            List<RetroCaller> voters = List.of(ANA, BIA, CREATOR);
            ExecutorService pool = Executors.newFixedThreadPool(3);
            CountDownLatch start = new CountDownLatch(1);
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (RetroCaller v : voters) {
                futures.add(pool.submit(() -> {
                    start.await();
                    service.toggleVote(BOARD, "c1", v);
                    return null;
                }));
            }
            start.countDown();
            for (var f : futures) f.get(5, TimeUnit.SECONDS);
            pool.shutdownNow();

            assertEquals(Set.of("ana", "bia", "boss"), c.getVotes());
        }
    }

    @Nested
    @DisplayName("Reset de votos (POST /votes/reset)")
    class ResetTests {

        @Test
        @DisplayName("criador zera votos de todos os cards, desativa a votação e publica os eventos")
        void creatorResets() {
            RetroCard c1 = card("c1", "good", "bia", "ana", "bia");
            RetroCard c2 = card("c2", "bad", "bia");
            RetroCard c3 = card("c3", "bad", "bia", "boss");
            when(cardRepository.findByBoardId(BOARD)).thenReturn(List.of(c1, c2, c3));

            RetroBoard result = service.resetVotes(BOARD, CREATOR);

            assertEquals("disabled", result.getVotingStatus());
            assertTrue(c1.getVotes().isEmpty());
            assertTrue(c3.getVotes().isEmpty());
            verify(webSocketHandler, times(2)).broadcastEvent(eq(BOARD), eq("CARD_SAVED"), any());
            verify(webSocketHandler).broadcastEvent(eq(BOARD), eq("BOARD_UPDATED"), eq(board));
        }

        @Test
        @DisplayName("ADMIN pode; participante comum recebe 403 e nada é apagado")
        void permissions() {
            when(cardRepository.findByBoardId(BOARD)).thenReturn(List.of());
            assertDoesNotThrow(() -> service.resetVotes(BOARD, ADMIN));

            RetroCard c1 = card("c1", "good", "bia", "ana");
            assertStatus(HttpStatus.FORBIDDEN, () -> service.resetVotes(BOARD, ANA));
            assertEquals(Set.of("ana"), c1.getVotes());
        }
    }

    // ---------------------------------------------------------------- board

    @Nested
    @DisplayName("Board: criação, POST legado, PATCH parcial e transfer-control")
    class BoardTests {

        @Test
        @DisplayName("criar board: creatorId vem do chamador (corpo ignorado) e squad é validada")
        void createForcesCreator() {
            when(boardRepository.findById("novo")).thenReturn(Optional.empty());
            RetroBoard incoming = RetroBoard.builder().id("novo").title("Nova").creatorId("forjado").squadId("SQ1").build();
            when(squadAccessService.matchesSquad("SQ1", "ana", "MEMBER")).thenReturn(true);

            RetroService.Saved<RetroBoard> saved = service.saveOrUpdateBoard(incoming, ANA);

            assertTrue(saved.created());
            assertEquals("ana", saved.value().getCreatorId());
            verify(webSocketHandler).broadcastEvent(eq("novo"), eq("BOARD_UPDATED"), any());
        }

        @Test
        @DisplayName("criar board em squad alheia → 403")
        void createInOtherSquadForbidden() {
            when(boardRepository.findById("novo")).thenReturn(Optional.empty());
            RetroBoard incoming = RetroBoard.builder().id("novo").title("Nova").squadId("OUTRA").build();
            when(squadAccessService.matchesSquad("OUTRA", "ana", "MEMBER")).thenReturn(false);

            assertStatus(HttpStatus.FORBIDDEN, () -> service.saveOrUpdateBoard(incoming, ANA));
            verify(boardRepository, never()).save(any());
        }

        @Test
        @DisplayName("POST legado do criador aplica os campos mas não troca creatorId/squadId")
        void creatorFullUpdateKeepsIdentity() {
            board.setSquadId("SQ1");
            RetroBoard incoming = RetroBoard.builder().id(BOARD).title("Novo título").creatorId("ana")
                    .squadId("OUTRA").isCardsRevealed(false).votingStatus("finished").build();

            RetroService.Saved<RetroBoard> saved = service.saveOrUpdateBoard(incoming, CREATOR);

            assertFalse(saved.created());
            assertEquals("Novo título", board.getTitle());
            assertEquals("finished", board.getVotingStatus());
            assertEquals("boss", board.getCreatorId());
            assertEquals("SQ1", board.getSquadId());
        }

        @Test
        @DisplayName("POST legado de participante comum é ignorado (não sobrescreve o board)")
        void participantFullPostIgnored() {
            RetroBoard incoming = RetroBoard.builder().id(BOARD).title("Hackeado").creatorId("boss")
                    .votingStatus("disabled").isCardsRevealed(false).build();

            RetroService.Saved<RetroBoard> saved = service.saveOrUpdateBoard(incoming, ANA);

            assertEquals("Retrospectiva Sprint 45", saved.value().getTitle());
            assertEquals("active", board.getVotingStatus());
            verify(boardRepository, never()).save(any());
        }

        @Test
        @DisplayName("POST legado de participante com creatorId = ele mesmo é 'assumir controle'")
        void legacyClaimCreator() {
            RetroParticipant boss = RetroParticipant.builder().id("boss").boardId(BOARD).nickname("Boss").isCreator(true).build();
            RetroParticipant ana = RetroParticipant.builder().id("ana").boardId(BOARD).nickname("Ana").isCreator(false).build();
            when(participantRepository.findByBoardId(BOARD)).thenReturn(List.of(boss, ana));

            service.saveOrUpdateBoard(RetroBoard.builder().id(BOARD).title("x").creatorId("ana").build(), ANA);

            assertEquals("ana", board.getCreatorId());
            assertFalse(boss.getIsCreator());
            assertTrue(ana.getIsCreator());
        }

        @Test
        @DisplayName("outsider no POST legado → 403")
        void outsiderPostForbidden() {
            assertStatus(HttpStatus.FORBIDDEN,
                    () -> service.saveOrUpdateBoard(RetroBoard.builder().id(BOARD).title("x").build(), INTRUSO));
        }

        @Test
        @DisplayName("PATCH do criador altera só os campos enviados")
        void patchTouchesOnlyGivenFields() {
            board.setTitle("Original");
            board.setActiveColumnKey("good");

            RetroBoard result = service.patchBoard(BOARD,
                    json("{\"votingStatus\":\"finished\",\"maxVotesPerParticipant\":3,\"creatorId\":\"ana\",\"squadId\":\"X\","
                            + "\"timer\":{\"status\":\"running\",\"endTime\":1700000000000,\"initialDuration\":600}}"), CREATOR);

            assertEquals("finished", result.getVotingStatus());
            assertEquals(3, result.getMaxVotesPerParticipant());
            assertEquals("running", result.getTimerStatus());
            assertEquals("1700000000000", result.getTimerEndTime());
            assertEquals(600, result.getTimerInitialDuration());
            assertEquals("Original", result.getTitle());
            assertEquals("good", result.getActiveColumnKey());
            assertEquals("boss", result.getCreatorId());
            assertNull(result.getSquadId());
            assertTrue(result.getIsCardsRevealed());
            verify(webSocketHandler).broadcastEvent(eq(BOARD), eq("BOARD_UPDATED"), eq(board));
        }

        @Test
        @DisplayName("PATCH: timer.endTime null limpa o prazo; ADMIN também pode")
        void patchClearsEndTime() {
            board.setTimerEndTime("123");
            service.patchBoard(BOARD, json("{\"timer\":{\"status\":\"stopped\",\"endTime\":null}}"), ADMIN);
            assertNull(board.getTimerEndTime());
            assertEquals("stopped", board.getTimerStatus());
        }

        @Test
        @DisplayName("PATCH de participante comum em campo de controle → 403")
        void patchControlFieldByParticipantForbidden() {
            assertStatus(HttpStatus.FORBIDDEN,
                    () -> service.patchBoard(BOARD, json("{\"isCardsRevealed\":false}"), ANA));
            assertTrue(board.getIsCardsRevealed());
            verify(boardRepository, never()).save(any());
        }

        @Test
        @DisplayName("PATCH de participante só com participantIds/summary é aceito e não altera o board")
        void patchParticipantIdsByParticipantAllowed() {
            RetroBoard result = service.patchBoard(BOARD, json("{\"participantIds\":[\"ana\"],\"summary\":{}}"), ANA);
            assertEquals("active", result.getVotingStatus());
        }

        @Test
        @DisplayName("PATCH valida votingStatus e limite de votos (400) e exige membro (403)")
        void patchValidation() {
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.patchBoard(BOARD, json("{\"votingStatus\":\"bogus\"}"), CREATOR));
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.patchBoard(BOARD, json("{\"maxVotesPerParticipant\":9999}"), CREATOR));
            assertStatus(HttpStatus.FORBIDDEN, () -> service.patchBoard(BOARD, json("{\"title\":\"x\"}"), INTRUSO));
        }

        @Test
        @DisplayName("transfer-control: chamador vira criador e só ele fica com isCreator")
        void transferControlLeavesSingleFlag() {
            RetroParticipant boss = RetroParticipant.builder().id("boss").boardId(BOARD).nickname("Boss").isCreator(true).build();
            RetroParticipant ana = RetroParticipant.builder().id("ana").boardId(BOARD).nickname("Ana").isCreator(true).build();
            RetroParticipant bia = RetroParticipant.builder().id("bia").boardId(BOARD).nickname("Bia").isCreator(false).build();
            when(participantRepository.findByBoardId(BOARD)).thenReturn(List.of(boss, ana, bia));

            RetroBoard result = service.transferControl(BOARD, ANA);

            assertEquals("ana", result.getCreatorId());
            assertFalse(boss.getIsCreator());
            assertTrue(ana.getIsCreator());
            assertFalse(bia.getIsCreator());
            verify(boardRepository).findByIdForUpdate(BOARD);
        }

        @Test
        @DisplayName("transfer-control por quem não é do board → 403")
        void transferControlOutsiderForbidden() {
            assertStatus(HttpStatus.FORBIDDEN, () -> service.transferControl(BOARD, INTRUSO));
        }

        @Test
        @DisplayName("leitura do board: outsider de board com squad → 403; membro da squad lê")
        void readAccess() {
            board.setSquadId("SQ1");
            when(squadAccessService.matchesSquad("SQ1", "intruso", "MEMBER")).thenReturn(false);
            when(squadAccessService.matchesSquad("SQ1", "carla", "MEMBER")).thenReturn(true);

            assertStatus(HttpStatus.FORBIDDEN, () -> service.getBoard(BOARD, INTRUSO));
            assertTrue(service.getBoard(BOARD, new RetroCaller("carla", "MEMBER")).isPresent());
            assertTrue(service.getBoard(BOARD, ANA).isPresent());
            assertStatus(HttpStatus.FORBIDDEN, () -> service.getCards(BOARD, INTRUSO));
            assertStatus(HttpStatus.FORBIDDEN, () -> service.getParticipants(BOARD, INTRUSO));
        }

        @Test
        @DisplayName("apagar board remove cards, participantes e chat; só criador/ADMIN")
        void deleteBoardCascades() {
            assertStatus(HttpStatus.FORBIDDEN, () -> service.deleteBoard(BOARD, ANA));

            service.deleteBoard(BOARD, CREATOR);

            verify(cardRepository).deleteByBoardId(BOARD);
            verify(participantRepository).deleteByBoardId(BOARD);
            verify(chatMessageRepository).deleteByBoardId(BOARD);
            verify(boardRepository).delete(board);
        }
    }

    // ---------------------------------------------------------- participantes

    @Nested
    @DisplayName("Participantes")
    class ParticipantTests {

        @Test
        @DisplayName("id é sempre o do chamador e isCreator/dbId vêm do servidor")
        void forcesIdAndIgnoresIsCreator() {
            when(participantRepository.findById("retro-123_ana")).thenReturn(Optional.empty());
            RetroParticipant forged = RetroParticipant.builder().boardId(BOARD).id("boss").nickname("Ana")
                    .isCreator(true).role("DEV").build();

            RetroService.Saved<RetroParticipant> saved = service.addOrUpdateParticipant(forged, ANA);

            assertTrue(saved.created());
            assertEquals("ana", saved.value().getId());
            assertEquals("retro-123_ana", saved.value().getDbId());
            assertFalse(saved.value().getIsCreator());
            verify(webSocketHandler).broadcastEvent(eq(BOARD), eq("PARTICIPANT_JOINED"), any());
        }

        @Test
        @DisplayName("criador que entra recebe isCreator=true; atualização existente não muda isCreator")
        void creatorFlagFromBoardAndPreservedOnUpdate() {
            when(participantRepository.findById("retro-123_boss")).thenReturn(Optional.empty());
            RetroParticipant p = RetroParticipant.builder().boardId(BOARD).nickname("Boss").build();
            assertTrue(service.addOrUpdateParticipant(p, CREATOR).value().getIsCreator());

            RetroParticipant existing = RetroParticipant.builder().dbId("retro-123_ana").id("ana").boardId(BOARD)
                    .nickname("Ana").isCreator(false).build();
            when(participantRepository.findById("retro-123_ana")).thenReturn(Optional.of(existing));
            RetroParticipant tryEscalate = RetroParticipant.builder().boardId(BOARD).nickname("Ana2").isCreator(true).build();
            RetroService.Saved<RetroParticipant> saved = service.addOrUpdateParticipant(tryEscalate, ANA);

            assertFalse(saved.created());
            assertEquals("Ana2", saved.value().getNickname());
            assertFalse(saved.value().getIsCreator());
        }

        @Test
        @DisplayName("board inexistente → 404; outsider de squad → 403")
        void notFoundAndForbidden() {
            RetroParticipant p = RetroParticipant.builder().boardId("nada").nickname("X").build();
            when(boardRepository.findById("nada")).thenReturn(Optional.empty());
            assertStatus(HttpStatus.NOT_FOUND, () -> service.addOrUpdateParticipant(p, ANA));

            board.setSquadId("SQ1");
            when(squadAccessService.matchesSquad("SQ1", "intruso", "MEMBER")).thenReturn(false);
            RetroParticipant q = RetroParticipant.builder().boardId(BOARD).nickname("X").build();
            assertStatus(HttpStatus.FORBIDDEN, () -> service.addOrUpdateParticipant(q, INTRUSO));
        }

        @Test
        @DisplayName("remover: o próprio, o criador ou ADMIN; terceiro → 403")
        void removeRules() {
            service.removeParticipant(BOARD, "ana", ANA);
            service.removeParticipant(BOARD, "ana", CREATOR);
            service.removeParticipant(BOARD, "ana", ADMIN);
            verify(participantRepository, times(3)).deleteByBoardIdAndId(BOARD, "ana");
            verify(webSocketHandler, times(3)).broadcastEvent(eq(BOARD), eq("PARTICIPANT_LEFT"), any());

            assertStatus(HttpStatus.FORBIDDEN, () -> service.removeParticipant(BOARD, "ana", BIA));
        }
    }

    // ---------------------------------------------------------------- cards

    @Nested
    @DisplayName("Cartões")
    class CardTests {

        @Test
        @DisplayName("criar: authorId = chamador, votos vazios, id gerado, 201")
        void createForcesAuthorAndEmptyVotes() {
            RetroCard incoming = RetroCard.builder().boardId(BOARD).columnKey("good").content("Boa comunicação")
                    .authorId("forjado").votes(new LinkedHashSet<>(List.of("x", "y"))).build();

            RetroService.Saved<RetroCard> saved = service.saveOrUpdateCard(incoming, ANA);

            assertTrue(saved.created());
            assertEquals("ana", saved.value().getAuthorId());
            assertTrue(saved.value().getVotes().isEmpty());
            assertNotNull(saved.value().getId());
            verify(webSocketHandler).broadcastEvent(eq(BOARD), eq("CARD_SAVED"), eq(saved.value()));
        }

        @Test
        @DisplayName("atualizar: ignora votes e authorId do corpo e mantém os persistidos")
        void updateKeepsVotesAndAuthor() {
            RetroCard stored = card("c1", "good", "ana", "bia");
            when(cardRepository.findById("c1")).thenReturn(Optional.of(stored));
            RetroCard incoming = RetroCard.builder().id("c1").boardId(BOARD).columnKey("good").content("editado")
                    .authorId("bia").votes(new LinkedHashSet<>()).order(5L).build();

            RetroService.Saved<RetroCard> saved = service.saveOrUpdateCard(incoming, ANA);

            assertFalse(saved.created());
            assertEquals("editado", saved.value().getContent());
            assertEquals("ana", saved.value().getAuthorId());
            assertEquals(Set.of("bia"), saved.value().getVotes());
        }

        @Test
        @DisplayName("participante que não é autor só move/reage: conteúdo do corpo é descartado")
        void nonAuthorCannotEditContent() {
            RetroCard stored = card("c1", "good", "ana");
            when(cardRepository.findById("c1")).thenReturn(Optional.of(stored));
            RetroCard incoming = RetroCard.builder().id("c1").boardId(BOARD).columnKey("bad").content("sequestrado")
                    .order(99L).build();

            RetroCard saved = service.saveOrUpdateCard(incoming, BIA).value();

            assertEquals("texto c1", saved.getContent());
            assertEquals("bad", saved.getColumnKey());
            assertEquals(99L, saved.getOrder());
        }

        @Test
        @DisplayName("criador e ADMIN editam o conteúdo de qualquer card")
        void creatorEditsAnyCard() {
            RetroCard stored = card("c1", "good", "ana");
            when(cardRepository.findById("c1")).thenReturn(Optional.of(stored));
            RetroCard incoming = RetroCard.builder().id("c1").boardId(BOARD).columnKey("good").content("moderado").build();

            assertEquals("moderado", service.saveOrUpdateCard(incoming, CREATOR).value().getContent());
        }

        @Test
        @DisplayName("reações: cada um só altera as próprias")
        void reactionsOnlyOwn() throws Exception {
            RetroCard stored = card("c1", "good", "ana");
            stored.setReactions(json("{\"up\":[\"bia\"],\"love\":[\"ana\"]}"));
            when(cardRepository.findById("c1")).thenReturn(Optional.of(stored));
            RetroCard incoming = RetroCard.builder().id("c1").boardId(BOARD).columnKey("good").content("texto c1")
                    .reactions(json("{\"up\":[],\"wow\":[\"ana\",\"bia\"],\"hack\":[\"ana\"]}")).build();

            JsonNode r = service.saveOrUpdateCard(incoming, ANA).value().getReactions();

            assertEquals(List.of("bia"), toList(r.get("up")));
            assertEquals(List.of(), toList(r.get("love")));
            assertEquals(List.of("ana"), toList(r.get("wow")));
            assertNull(r.get("hack"));
        }

        private List<String> toList(JsonNode arr) {
            List<String> out = new ArrayList<>();
            arr.forEach(n -> out.add(n.asText()));
            return out;
        }

        @Test
        @DisplayName("rejeita 403 se o cartão existente pertence a outro quadro")
        void rejectsCardFromAnotherBoard() {
            RetroCard stored = RetroCard.builder().id("c1").boardId("outro-board").build();
            when(cardRepository.findById("c1")).thenReturn(Optional.of(stored));
            RetroCard attempt = RetroCard.builder().id("c1").boardId(BOARD).columnKey("good").content("x").build();

            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.saveOrUpdateCard(attempt, ANA));
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
            verify(cardRepository, never()).save(any());
        }

        @Test
        @DisplayName("escrita por quem não é do board → 403; board inexistente → 404")
        void authorizationAndNotFound() {
            RetroCard c = RetroCard.builder().boardId(BOARD).columnKey("good").content("x").build();
            assertStatus(HttpStatus.FORBIDDEN, () -> service.saveOrUpdateCard(c, INTRUSO));

            RetroCard n = RetroCard.builder().boardId("nada").columnKey("good").content("x").build();
            when(boardRepository.findById("nada")).thenReturn(Optional.empty());
            assertStatus(HttpStatus.NOT_FOUND, () -> service.saveOrUpdateCard(n, ANA));
        }

        @Test
        @DisplayName("validação: conteúdo vazio/gigante, coluna inexistente e columnKey longo → 400")
        void validation() {
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.saveOrUpdateCard(
                    RetroCard.builder().boardId(BOARD).columnKey("good").content("   ").build(), ANA));
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.saveOrUpdateCard(
                    RetroCard.builder().boardId(BOARD).columnKey("good").content("x".repeat(5001)).build(), ANA));
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.saveOrUpdateCard(
                    RetroCard.builder().boardId(BOARD).columnKey("inexistente").content("x").build(), ANA));
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.saveOrUpdateCard(
                    RetroCard.builder().boardId(BOARD).columnKey("k".repeat(51)).content("x").build(), ANA));
        }

        @Test
        @DisplayName("apagar: autor, criador ou ADMIN; outro participante → 403; inexistente → 404")
        void deleteRules() {
            RetroCard c = card("c1", "good", "ana");
            when(cardRepository.findById("c1")).thenReturn(Optional.of(c));

            assertStatus(HttpStatus.FORBIDDEN, () -> service.deleteCard(BOARD, "c1", BIA));
            service.deleteCard(BOARD, "c1", ANA);
            service.deleteCard(BOARD, "c1", CREATOR);
            verify(cardRepository, times(2)).deleteById("c1");
            verify(webSocketHandler, times(2)).broadcastEvent(eq(BOARD), eq("CARD_DELETED"), any());

            when(cardRepository.findById("zz")).thenReturn(Optional.empty());
            assertStatus(HttpStatus.NOT_FOUND, () -> service.deleteCard(BOARD, "zz", ANA));
        }

        @Test
        @DisplayName("apagar card de outro quadro → 403")
        void deleteFromDifferentBoard() {
            RetroCard c = card("c9", "good", "ana");
            c.setBoardId("outro-quadro");
            when(cardRepository.findById("c9")).thenReturn(Optional.of(c));

            assertStatus(HttpStatus.FORBIDDEN, () -> service.deleteCard(BOARD, "c9", ANA));
            verify(cardRepository, never()).deleteById(anyString());
        }

        @Test
        @DisplayName("apagar card pai solta os filhos (parentId null) e publica as alterações")
        void deleteParentOrphansChildren() {
            RetroCard parent = card("p", "good", "ana");
            RetroCard k1 = card("k1", "good", "bia");
            k1.setParentId("p");
            RetroCard k2 = card("k2", "good", "bia");
            k2.setParentId("p");
            when(cardRepository.findById("p")).thenReturn(Optional.of(parent));
            when(cardRepository.findByBoardIdAndParentId(BOARD, "p")).thenReturn(List.of(k1, k2));

            service.deleteCard(BOARD, "p", ANA);

            assertNull(k1.getParentId());
            assertNull(k2.getParentId());
            verify(webSocketHandler, times(2)).broadcastEvent(eq(BOARD), eq("CARD_SAVED"), any());
            verify(cardRepository).deleteById("p");
        }

        @Test
        @DisplayName("importar: novo card vira do chamador sem votos; id existente não é sobrescrito")
        void importDoesNotStealCards() {
            RetroCard fresh = RetroCard.builder().id("n1").columnKey("actions").content("Ação").authorId("forjado")
                    .votes(new LinkedHashSet<>(List.of("x"))).build();
            RetroCard stolen = RetroCard.builder().id("c1").columnKey("actions").content("roubo").build();
            RetroCard stored = card("c1", "good", "bia");
            when(cardRepository.findById("n1")).thenReturn(Optional.empty());
            when(cardRepository.findById("c1")).thenReturn(Optional.of(stored));

            List<RetroCard> result = service.importActions(BOARD, List.of(fresh, stolen), ANA);

            assertEquals(2, result.size());
            assertEquals("ana", fresh.getAuthorId());
            assertEquals(BOARD, fresh.getBoardId());
            assertTrue(fresh.getVotes().isEmpty());
            assertEquals("texto c1", stored.getContent());
            verify(cardRepository, times(1)).save(any(RetroCard.class));
            verify(webSocketHandler).broadcastEvent(eq(BOARD), eq("CARDS_IMPORTED"), eq(result));
        }

        @Test
        @DisplayName("importar: card de outro board com mesmo id → 403; outsider → 403")
        void importForbidden() {
            RetroCard other = card("c1", "good", "bia");
            other.setBoardId("outro");
            when(cardRepository.findById("c1")).thenReturn(Optional.of(other));
            RetroCard attempt = RetroCard.builder().id("c1").columnKey("actions").content("x").build();

            assertStatus(HttpStatus.FORBIDDEN, () -> service.importActions(BOARD, List.of(attempt), ANA));
            assertStatus(HttpStatus.FORBIDDEN, () -> service.importActions(BOARD, List.of(), INTRUSO));
        }

        @Test
        @DisplayName("listar cards devolve ordenado por order e depois id")
        void cardsAreOrdered() {
            RetroCard a = card("a", "good", "ana");
            a.setOrder(30L);
            RetroCard b = card("b", "good", "ana");
            b.setOrder(10L);
            RetroCard c = card("c", "good", "ana");
            c.setOrder(10L);
            when(cardRepository.findByBoardId(BOARD)).thenReturn(Arrays.asList(a, c, b));

            assertEquals(List.of("b", "c", "a"), service.getCards(BOARD).stream().map(RetroCard::getId).toList());
        }
    }

    // ---------------------------------------------------------------- merge

    @Nested
    @DisplayName("Fusão (POST /cards/{target}/merge/{source})")
    class MergeTests {

        @Test
        @DisplayName("junta histórico, une votos sem repetir, move filhos, apaga a origem e publica")
        void mergesAtomically() {
            RetroCard target = card("t", "good", "ana", "ana", "bia");
            target.getOriginalTexts().add("antigo");
            RetroCard source = card("s", "good", "bia", "bia", "boss");
            source.getOriginalTexts().add("da origem");
            RetroCard child = card("k", "good", "bia");
            child.setParentId("s");
            when(cardRepository.findById("t")).thenReturn(Optional.of(target));
            when(cardRepository.findById("s")).thenReturn(Optional.of(source));
            when(cardRepository.findByBoardIdAndParentId(BOARD, "s")).thenReturn(List.of(child));

            RetroCard merged = service.mergeCards(BOARD, "t", "s", ANA);

            assertEquals(List.of("antigo", "texto s", "da origem"), merged.getOriginalTexts());
            assertEquals(Set.of("ana", "bia", "boss"), merged.getVotes());
            assertEquals("t", child.getParentId());
            verify(cardRepository).deleteById("s");
            verify(webSocketHandler).broadcastEvent(eq(BOARD), eq("CARD_DELETED"), any());
            verify(webSocketHandler).broadcastEvent(eq(BOARD), eq("CARD_SAVED"), eq(merged));
        }

        @Test
        @DisplayName("fase anônima, painéis diferentes e coluna de ação → 409")
        void conflicts() {
            RetroCard t = card("t", "good", "ana");
            RetroCard s = card("s", "bad", "bia");
            when(cardRepository.findById("t")).thenReturn(Optional.of(t));
            when(cardRepository.findById("s")).thenReturn(Optional.of(s));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.mergeCards(BOARD, "t", "s", ANA));
            assertEquals("Só é possível fundir cards do mesmo painel.", ex.getReason());

            board.setIsCardsRevealed(false);
            ResponseStatusException anon = assertThrows(ResponseStatusException.class, () -> service.mergeCards(BOARD, "t", "s", ANA));
            assertEquals(HttpStatus.CONFLICT, anon.getStatusCode());
            assertEquals("Não é possível fundir cards durante a fase anônima.", anon.getReason());

            board.setIsCardsRevealed(true);
            t.setColumnKey("actions");
            s.setColumnKey("actions");
            assertStatus(HttpStatus.CONFLICT, () -> service.mergeCards(BOARD, "t", "s", ANA));
            verify(cardRepository, never()).deleteById(anyString());
        }

        @Test
        @DisplayName("outsider → 403; mesmo card → 400")
        void authorization() {
            assertStatus(HttpStatus.FORBIDDEN, () -> service.mergeCards(BOARD, "t", "s", INTRUSO));
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.mergeCards(BOARD, "t", "t", ANA));
        }
    }

    // ------------------------------------------------------- eventos / commit

    @Nested
    @DisplayName("Eventos WebSocket só depois do commit")
    class AfterCommitTests {

        @Test
        @DisplayName("com transação ativa, o broadcast só acontece em afterCommit (e não em rollback)")
        void publishesAfterCommit() {
            RetroCard c = card("c1", "good", "bia");
            when(cardRepository.findById("c1")).thenReturn(Optional.of(c));
            when(cardRepository.findByBoardId(BOARD)).thenReturn(List.of(c));

            TransactionSynchronizationManager.initSynchronization();
            service.toggleVote(BOARD, "c1", ANA);

            verify(webSocketHandler, never()).broadcastEvent(anyString(), anyString(), any());
            for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
                s.afterCommit();
            }
            verify(webSocketHandler).broadcastEvent(eq(BOARD), eq("CARD_SAVED"), any());
        }

        @Test
        @DisplayName("falha de validação não publica nada")
        void rollbackPublishesNothing() {
            TransactionSynchronizationManager.initSynchronization();
            assertThrows(ResponseStatusException.class, () -> service.toggleVote(BOARD, "c1", INTRUSO));
            assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
            verifyNoInteractions(webSocketHandler);
        }
    }

    @Test
    @DisplayName("listas por squad/sprint continuam delegando ao repositório (sprint filtra por acesso)")
    void listing() {
        RetroBoard open = RetroBoard.builder().id("a").creatorId("x").title("A").squadId("SQ1").build();
        RetroBoard hidden = RetroBoard.builder().id("b").creatorId("x").title("B").squadId("SQ2").build();
        when(boardRepository.findBySprintIdOrderByCreatedAtDesc("S1")).thenReturn(List.of(open, hidden));
        when(squadAccessService.matchesSquad("SQ1", "ana", "MEMBER")).thenReturn(true);
        when(squadAccessService.matchesSquad("SQ2", "ana", "MEMBER")).thenReturn(false);
        when(participantRepository.findByBoardIdAndId(anyString(), eq("ana"))).thenReturn(Optional.empty());

        assertEquals(List.of(open), service.listBoardsBySprintId("S1", ANA));
        when(boardRepository.findBySquadIdIgnoreCaseOrderByCreatedAtDesc("DDWMISSI")).thenReturn(Collections.singletonList(board));
        assertEquals(1, service.listBoardsBySquadId("DDWMISSI").size());
    }
}
