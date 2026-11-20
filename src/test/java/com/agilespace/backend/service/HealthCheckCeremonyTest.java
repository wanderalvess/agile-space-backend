package com.agilespace.backend.service;

import com.agilespace.backend.domain.HealthCheckBoard;
import com.agilespace.backend.domain.HealthCheckParticipant;
import com.agilespace.backend.domain.HealthCheckVote;
import com.agilespace.backend.repository.HealthCheckBoardRepository;
import com.agilespace.backend.repository.HealthCheckParticipantRepository;
import com.agilespace.backend.repository.HealthCheckVoteRepository;
import com.agilespace.backend.websocket.HealthCheckWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Radar: quem já respondeu (só contagens), encerramento real e apresentação sincronizada. */
@ExtendWith(MockitoExtension.class)
@DisplayName("HealthCheckService - progresso, finishedAt e apresentação")
class HealthCheckCeremonyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CeremonyCaller CREATOR = new CeremonyCaller("fac", "MEMBER");
    private static final CeremonyCaller VOTER = new CeremonyCaller("u2", "MEMBER");
    private static final CeremonyCaller ADMIN = new CeremonyCaller("adm", "ADMIN");

    @Mock private HealthCheckBoardRepository boardRepository;
    @Mock private HealthCheckParticipantRepository participantRepository;
    @Mock private HealthCheckVoteRepository voteRepository;
    @Mock private HealthCheckWebSocketHandler webSocketHandler;

    @InjectMocks
    private HealthCheckService service;

    private static JsonNode json(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static HealthCheckBoard board(String status) {
        return HealthCheckBoard.builder().id("hc1").creatorId("fac").status(status).scaleType("traffic_light")
                .dimensions(json("[{\"key\":\"speed\",\"title\":\"Velocidade\"},{\"key\":\"fun\",\"title\":\"Diversão\"}]"))
                .build();
    }

    private static HealthCheckParticipant person(String id) {
        return HealthCheckParticipant.builder().dbId("hc1_" + id).id(id).boardId("hc1").nickname("Nome " + id).role("DEV").build();
    }

    private static HealthCheckVote vote(String participant, String dimension, String value, String comment) {
        return HealthCheckVote.builder().id("hc1_" + participant + "_" + dimension).boardId("hc1").participantId(participant)
                .participantRole("DEV").dimensionKey(dimension).value(value).comment(comment).timestamp("t").build();
    }

    private static void assertStatus(HttpStatus expected, Runnable action) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(expected, ex.getStatusCode());
    }

    /** u1 completou as 2 dimensões, u2 votou em 1, u3 não votou; u4 votou mas já saiu da sala. */
    private void stubRoomWithVotes() {
        when(participantRepository.findByBoardIdIn(anyCollection()))
                .thenReturn(List.of(person("u1"), person("u2"), person("u3")));
        when(voteRepository.countDimensionsByParticipant(anyCollection())).thenReturn(List.of(
                new Object[]{"hc1", "u1", 2L}, new Object[]{"hc1", "u2", 1L}, new Object[]{"hc1", "u4", 2L}));
    }

    @Nested
    @DisplayName("Quem já respondeu")
    class ProgressTests {

        @Test
        @DisplayName("Conta respondeu / completou / na sala; quem saiu da sala não conta")
        void countsOnlyPeopleStillInTheRoom() {
            when(boardRepository.findById("hc1")).thenReturn(Optional.of(board("collecting")));
            stubRoomWithVotes();

            HealthCheckBoard view = service.getBoard("hc1").orElseThrow();

            assertEquals(3, view.getParticipantsCount());
            assertEquals(2, view.getRespondedCount());
            assertEquals(1, view.getCompletedCount());
        }

        @Test
        @DisplayName("Facilitador recebe a lista de pendentes (só ids); quem não é facilitador não recebe o campo")
        void pendingListIsFacilitatorOnly() throws Exception {
            when(boardRepository.findById("hc1")).thenReturn(Optional.of(board("collecting")));
            stubRoomWithVotes();

            HealthCheckService.HealthCheckProgress asFacilitator = service.getProgress("hc1", CREATOR);
            HealthCheckService.HealthCheckProgress asAdmin = service.getProgress("hc1", ADMIN);
            HealthCheckService.HealthCheckProgress asVoter = service.getProgress("hc1", VOTER);

            assertEquals(List.of("u3"), asFacilitator.pendingParticipantIds());
            assertEquals(List.of("u3"), asAdmin.pendingParticipantIds());
            assertNull(asVoter.pendingParticipantIds());
            assertEquals(3, asVoter.participantsCount());
            assertEquals(2, asVoter.respondedCount());
            assertEquals(1, asVoter.completedCount());
            assertEquals(2, asVoter.dimensionsCount());
            assertFalse(JSON.writeValueAsString(asVoter).contains("pendingParticipantIds"));
        }

        @Test
        @DisplayName("Nada que vaza voto: nem a resposta do progresso nem o quadro carregam valor, comentário ou quem votou o quê")
        void neverLeaksVotes() throws Exception {
            when(boardRepository.findById("hc1")).thenReturn(Optional.of(board("collecting")));
            stubRoomWithVotes();

            String progress = JSON.writeValueAsString(service.getProgress("hc1", CREATOR));
            String boardJson = JSON.writeValueAsString(service.getBoard("hc1").orElseThrow());

            for (String json : List.of(progress, boardJson)) {
                assertFalse(json.contains("green") || json.contains("red") || json.contains("comment"), json);
            }
            verify(voteRepository, never()).findByBoardId(anyString());
            verify(voteRepository, never()).findByBoardIdAndParticipantId(anyString(), anyString());
        }

        @Test
        @DisplayName("Radar inexistente = 404; sem login = 403")
        void notFoundAndAnonymous() {
            when(boardRepository.findById("nope")).thenReturn(Optional.empty());
            assertStatus(HttpStatus.NOT_FOUND, () -> service.getProgress("nope", CREATOR));
            assertStatus(HttpStatus.FORBIDDEN, () -> service.getProgress("hc1", new CeremonyCaller(null, null)));
        }

        @Test
        @DisplayName("A lista de radares traz as contagens calculadas em lote (uma consulta de participantes e uma de votos)")
        void listIsBatched() {
            HealthCheckBoard a = board("collecting");
            HealthCheckBoard b = HealthCheckBoard.builder().id("hc2").creatorId("fac").status("finished")
                    .dimensions(json("[{\"key\":\"x\",\"title\":\"X\"}]")).build();
            when(boardRepository.findByTeamIgnoreCaseOrderByCreatedAtDesc("SQ1")).thenReturn(List.of(a, b));
            when(participantRepository.findByBoardIdIn(anyCollection())).thenReturn(List.of(
                    person("u1"), person("u2"), HealthCheckParticipant.builder().id("u1").boardId("hc2").nickname("Ana").build()));
            when(voteRepository.countDimensionsByParticipant(anyCollection())).thenReturn(List.<Object[]>of(
                    new Object[]{"hc1", "u1", 2L}, new Object[]{"hc2", "u1", 1L}));

            List<HealthCheckBoard> list = service.listBoards("SQ1");

            assertEquals(2, a.getParticipantsCount());
            assertEquals(1, a.getRespondedCount());
            assertEquals(1, a.getCompletedCount());
            assertEquals(1, b.getParticipantsCount());
            assertEquals(1, b.getRespondedCount());
            assertEquals(1, b.getCompletedCount());
            assertEquals(2, list.size());
            verify(participantRepository, times(1)).findByBoardIdIn(anyCollection());
            verify(voteRepository, times(1)).countDimensionsByParticipant(anyCollection());
        }
    }

    @Nested
    @DisplayName("Tempo real da contagem (reaproveita o BOARD_UPDATED)")
    class ProgressEventTests {

        private void stubVoting(HealthCheckBoard board, List<HealthCheckVote> ownBefore) {
            when(boardRepository.findByIdForUpdate("hc1")).thenReturn(Optional.of(board));
            when(participantRepository.findByBoardIdAndId("hc1", "u2")).thenReturn(Optional.of(person("u2")));
            when(voteRepository.findById(anyString())).thenReturn(Optional.empty());
            when(voteRepository.findByBoardIdAndParticipantId("hc1", "u2")).thenReturn(ownBefore);
            when(voteRepository.save(any(HealthCheckVote.class))).thenAnswer(i -> i.getArgument(0));
        }

        @Test
        @DisplayName("Primeira resposta da pessoa gera BOARD_UPDATED para a sala inteira, com as contagens e sem lista de pendentes")
        void firstAnswerBroadcastsBoardUpdated() {
            HealthCheckBoard board = board("collecting");
            stubVoting(board, List.of());
            stubRoomWithVotes();

            service.saveVote("hc1", HealthCheckVote.builder().dimensionKey("speed").value("red").build(), VOTER);

            verify(webSocketHandler).broadcastEvent(eq("hc1"), eq("BOARD_UPDATED"), eq(board));
            assertEquals(2, board.getRespondedCount());
        }

        @Test
        @DisplayName("Completar a última dimensão gera BOARD_UPDATED; uma dimensão do meio não gera")
        void completionBroadcastsButMiddleVoteDoesNot() {
            HealthCheckBoard threeDims = HealthCheckBoard.builder().id("hc1").creatorId("fac").status("collecting").scaleType("traffic_light")
                    .dimensions(json("[{\"key\":\"speed\",\"title\":\"A\"},{\"key\":\"fun\",\"title\":\"B\"},{\"key\":\"x\",\"title\":\"C\"}]"))
                    .build();
            stubVoting(threeDims, List.of(vote("u2", "speed", "red", "")));
            stubRoomWithVotes();

            service.saveVote("hc1", HealthCheckVote.builder().dimensionKey("fun").value("red").build(), VOTER);
            verify(webSocketHandler, never()).broadcastEvent(anyString(), eq("BOARD_UPDATED"), any());

            when(voteRepository.findByBoardIdAndParticipantId("hc1", "u2"))
                    .thenReturn(List.of(vote("u2", "speed", "red", ""), vote("u2", "fun", "red", "")));
            service.saveVote("hc1", HealthCheckVote.builder().dimensionKey("x").value("green").build(), VOTER);
            verify(webSocketHandler).broadcastEvent(eq("hc1"), eq("BOARD_UPDATED"), eq(threeDims));
        }

        @Test
        @DisplayName("Trocar um voto já dado não muda a contagem e não gera evento; o VOTE_SAVED segue só para o dono")
        void changingAnExistingVoteIsSilent() {
            HealthCheckBoard board = board("collecting");
            when(boardRepository.findByIdForUpdate("hc1")).thenReturn(Optional.of(board));
            when(participantRepository.findByBoardIdAndId("hc1", "u2")).thenReturn(Optional.of(person("u2")));
            when(voteRepository.findById("hc1_u2_speed")).thenReturn(Optional.of(vote("u2", "speed", "green", "")));
            when(voteRepository.save(any(HealthCheckVote.class))).thenAnswer(i -> i.getArgument(0));

            service.saveVote("hc1", HealthCheckVote.builder().dimensionKey("speed").value("red").build(), VOTER);

            verify(webSocketHandler, never()).broadcastEvent(anyString(), eq("BOARD_UPDATED"), any());
            verify(webSocketHandler).broadcastEventToUsers(eq("hc1"), eq("VOTE_SAVED"), any(), eq(Set.of("u2")));
        }
    }

    @Nested
    @DisplayName("Encerramento real")
    class FinishTests {

        @Test
        @DisplayName("Encerrar grava finishedAt e o lifecycleStatus passa a FINISHED; encerrar de novo não muda a data")
        void finishStampsFinishedAtOnce() {
            HealthCheckBoard stored = board("collecting");
            assertEquals("OPEN", stored.getLifecycleStatus());
            assertNull(stored.getFinishedAt());
            when(boardRepository.findByIdForUpdate("hc1")).thenReturn(Optional.of(stored));
            when(boardRepository.save(any(HealthCheckBoard.class))).thenAnswer(i -> i.getArgument(0));
            when(voteRepository.findByBoardId("hc1")).thenReturn(List.of(vote("u1", "speed", "green", "")));

            HealthCheckBoard finished = service.finish("hc1", CREATOR);

            assertEquals("finished", finished.getStatus());
            assertEquals("FINISHED", finished.getLifecycleStatus());
            assertNotNull(finished.getFinishedAt());
            assertDoesNotThrow(() -> java.time.Instant.parse(finished.getFinishedAt()));
            String stamp = finished.getFinishedAt();

            assertSame(finished, service.finish("hc1", ADMIN));
            assertEquals(stamp, finished.getFinishedAt());
        }

        @Test
        @DisplayName("Radar encerrado antes da V79: finishedAt nulo e lifecycleStatus FINISHED derivado do status (nada inventado)")
        void legacyFinishedBoardKeepsNullDate() {
            HealthCheckBoard legacy = board("finished");
            assertNull(legacy.getFinishedAt());
            assertEquals("FINISHED", legacy.getLifecycleStatus());
        }

        @Test
        @DisplayName("Quem não é criador nem ADMIN continua levando 403 ao encerrar")
        void onlyFacilitatorFinishes() {
            when(boardRepository.findByIdForUpdate("hc1")).thenReturn(Optional.of(board("collecting")));
            assertStatus(HttpStatus.FORBIDDEN, () -> service.finish("hc1", VOTER));
        }

        @Test
        @DisplayName("Serialização: lifecycleStatus e finishedAt saem no JSON; o corpo antigo (sem eles) e o corpo com eles são aceitos e ignorados")
        void jsonIsBackwardCompatible() throws Exception {
            HealthCheckBoard b = board("finished");
            b.setFinishedAt("2026-10-17T12:00:00Z");
            JsonNode out = JSON.valueToTree(b);
            assertEquals("FINISHED", out.get("lifecycleStatus").asText());
            assertEquals("2026-10-17T12:00:00Z", out.get("finishedAt").asText());
            assertTrue(out.get("presentationStage").isNull());
            assertFalse(out.has("respondedCount"), "sem contagem calculada o campo não aparece");

            HealthCheckBoard old = JSON.readValue("{\"id\":\"x\",\"status\":\"finished\",\"scaleType\":\"emojis\"}", HealthCheckBoard.class);
            assertEquals("finished", old.getStatus());
            HealthCheckBoard echoed = JSON.readValue(
                    "{\"id\":\"x\",\"status\":\"collecting\",\"lifecycleStatus\":\"FINISHED\",\"finishedAt\":\"2020-01-01T00:00:00Z\","
                            + "\"presentationStage\":\"radar\",\"respondedCount\":9}", HealthCheckBoard.class);
            assertNull(echoed.getFinishedAt());
            assertNull(echoed.getPresentationStage());
            assertNull(echoed.getRespondedCount());
            assertEquals("OPEN", echoed.getLifecycleStatus());
        }
    }

    @Nested
    @DisplayName("Apresentação sincronizada")
    class PresentationTests {

        @Test
        @DisplayName("Criador e ADMIN publicam a etapa; o evento opt-in é emitido com o estado completo")
        void facilitatorPublishesStage() {
            HealthCheckBoard stored = board("finished");
            when(boardRepository.findByIdForUpdate("hc1")).thenReturn(Optional.of(stored));
            when(boardRepository.save(any(HealthCheckBoard.class))).thenAnswer(i -> i.getArgument(0));

            HealthCheckBoard saved = service.setPresentationStage("hc1", " radar ", CREATOR);
            assertEquals("radar", saved.getPresentationStage());
            verify(webSocketHandler).broadcastPresentationStage(eq("hc1"), eq(java.util.Map.of("stage", "radar")));

            service.setPresentationStage("hc1", "comments", ADMIN);
            assertEquals("comments", stored.getPresentationStage());
            verify(webSocketHandler).broadcastPresentationStage(eq("hc1"), eq(java.util.Map.of("stage", "comments")));
        }

        @Test
        @DisplayName("Participante comum não muda a etapa (403) nem encerra a apresentação (403)")
        void participantCannotConduct() {
            when(boardRepository.findByIdForUpdate("hc1")).thenReturn(Optional.of(board("finished")));
            assertStatus(HttpStatus.FORBIDDEN, () -> service.setPresentationStage("hc1", "radar", VOTER));
            assertStatus(HttpStatus.FORBIDDEN, () -> service.clearPresentationStage("hc1", VOTER));
            verify(webSocketHandler, never()).broadcastPresentationStage(anyString(), any());
            verify(boardRepository, never()).save(any());
        }

        @Test
        @DisplayName("Antes de encerrar não há apresentação (409); etapa vazia ou fora do formato é 400")
        void validatesStateAndFormat() {
            when(boardRepository.findByIdForUpdate("hc1")).thenReturn(Optional.of(board("collecting")));
            assertStatus(HttpStatus.CONFLICT, () -> service.setPresentationStage("hc1", "radar", CREATOR));
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.setPresentationStage("hc1", null, CREATOR));
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.setPresentationStage("hc1", "  ", CREATOR));
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.setPresentationStage("hc1", "com espaço", CREATOR));
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.setPresentationStage("hc1", "x".repeat(41), CREATOR));
        }

        @Test
        @DisplayName("Encerrar a apresentação limpa a etapa e avisa com stage nulo; radar inexistente é 404")
        void clearStage() {
            HealthCheckBoard stored = board("finished");
            stored.setPresentationStage("plan");
            when(boardRepository.findByIdForUpdate("hc1")).thenReturn(Optional.of(stored));
            when(boardRepository.save(any(HealthCheckBoard.class))).thenAnswer(i -> i.getArgument(0));

            HealthCheckBoard saved = service.clearPresentationStage("hc1", CREATOR);

            assertNull(saved.getPresentationStage());
            java.util.Map<String, Object> cleared = new java.util.LinkedHashMap<>();
            cleared.put("stage", null);
            verify(webSocketHandler).broadcastPresentationStage("hc1", cleared);
            when(boardRepository.findByIdForUpdate("nope")).thenReturn(Optional.empty());
            assertStatus(HttpStatus.NOT_FOUND, () -> service.clearPresentationStage("nope", CREATOR));
        }

        @Test
        @DisplayName("Encerrar o radar com etapa residual limpa a etapa e avisa")
        void finishClearsLeftoverStage() {
            HealthCheckBoard stored = board("collecting");
            stored.setPresentationStage("radar");
            when(boardRepository.findByIdForUpdate("hc1")).thenReturn(Optional.of(stored));
            when(boardRepository.save(any(HealthCheckBoard.class))).thenAnswer(i -> i.getArgument(0));
            when(voteRepository.findByBoardId("hc1")).thenReturn(List.of(vote("u1", "speed", "green", "")));

            HealthCheckBoard finished = service.finish("hc1", CREATOR);

            assertNull(finished.getPresentationStage());
            verify(webSocketHandler).broadcastPresentationStage(eq("hc1"), any());
        }

        @Test
        @DisplayName("POST legado num radar existente continua ignorando o corpo: não dá para publicar etapa por ele")
        void legacyPostCannotSetStage() {
            HealthCheckBoard stored = board("collecting");
            when(boardRepository.findById("hc1")).thenReturn(Optional.of(stored));
            HealthCheckBoard body = HealthCheckBoard.builder().id("hc1").status("collecting").build();

            HealthCheckBoard result = service.saveOrUpdateBoard(body, CREATOR).value();

            assertNull(result.getPresentationStage());
        }
    }
}
