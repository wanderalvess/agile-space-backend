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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("HealthCheckService - voto anônimo, validação e encerramento no servidor")
class HealthCheckServiceTest {

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

    private static HealthCheckBoard board(String status, String scale) {
        return HealthCheckBoard.builder().id("hc1").creatorId("fac").status(status).scaleType(scale)
                .dimensions(json("[{\"key\":\"speed\",\"title\":\"Velocidade\",\"description\":\"\"},{\"key\":\"fun\",\"title\":\"Diversão\",\"description\":\"\"}]"))
                .build();
    }

    private static HealthCheckVote vote(String participant, String dimension, String value, String comment) {
        return HealthCheckVote.builder().id("hc1_" + participant + "_" + dimension).boardId("hc1").participantId(participant)
                .participantRole("DEV").dimensionKey(dimension).value(value).comment(comment).timestamp("t").build();
    }

    private static void assertStatus(HttpStatus expected, Runnable action) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(expected, ex.getStatusCode());
    }

    @Nested
    @DisplayName("Criação")
    class CreateTests {

        @Test
        @DisplayName("Criador vem do login, status é sempre 'collecting' e resumo do corpo é descartado")
        void createsWithServerControlledFields() {
            when(boardRepository.save(any(HealthCheckBoard.class))).thenAnswer(i -> i.getArgument(0));
            HealthCheckBoard body = HealthCheckBoard.builder().creatorId("impostor").status("finished")
                    .summary(json("{\"x\":1}")).scaleType("emojis").sprintName("Sprint 12")
                    .dimensions(json("[{\"key\":\"a\",\"title\":\"A\",\"description\":\"d\"}]")).build();

            HealthCheckService.Saved<HealthCheckBoard> saved = service.saveOrUpdateBoard(body, CREATOR);

            assertTrue(saved.created());
            assertEquals("fac", saved.value().getCreatorId());
            assertEquals("collecting", saved.value().getStatus());
            assertNull(saved.value().getSummary());
            assertEquals("emojis", saved.value().getScaleType());
            verify(webSocketHandler).broadcastEvent(eq(saved.value().getId()), eq("BOARD_UPDATED"), eq(saved.value()));
        }

        @Test
        @DisplayName("Escala inválida, dimensões vazias ou repetidas são recusadas")
        void validatesScaleAndDimensions() {
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.saveOrUpdateBoard(
                    HealthCheckBoard.builder().scaleType("x").dimensions(json("[{\"key\":\"a\",\"title\":\"A\"}]")).build(), CREATOR));
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.saveOrUpdateBoard(
                    HealthCheckBoard.builder().dimensions(json("[]")).build(), CREATOR));
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.saveOrUpdateBoard(
                    HealthCheckBoard.builder().dimensions(json("[{\"key\":\"a\",\"title\":\"A\"},{\"key\":\"a\",\"title\":\"B\"}]")).build(), CREATOR));
            verify(boardRepository, never()).save(any());
        }

        @Test
        @DisplayName("Radar existente: só o criador mexe; abas antigas que mandam 'finished' disparam o encerramento calculado no servidor")
        void legacyFinishWriteIsRecomputed() {
            HealthCheckBoard stored = board("collecting", "traffic_light");
            when(boardRepository.findById("hc1")).thenReturn(Optional.of(stored));
            HealthCheckBoard body = HealthCheckBoard.builder().id("hc1").status("finished").summary(json("{\"forjado\":true}")).build();

            assertStatus(HttpStatus.FORBIDDEN, () -> service.saveOrUpdateBoard(body, VOTER));

            when(boardRepository.findByIdForUpdate("hc1")).thenReturn(Optional.of(stored));
            when(voteRepository.findByBoardId("hc1")).thenReturn(List.of(vote("u2", "speed", "green", "")));
            when(boardRepository.save(any(HealthCheckBoard.class))).thenAnswer(i -> i.getArgument(0));

            HealthCheckService.Saved<HealthCheckBoard> saved = service.saveOrUpdateBoard(body, CREATOR);

            assertFalse(saved.created());
            assertEquals("finished", saved.value().getStatus());
            assertFalse(saved.value().getSummary().has("forjado"));
            assertEquals(2, saved.value().getSummary().path("results").size());
        }
    }

    @Nested
    @DisplayName("Voto")
    class VoteTests {

        private void stubVotingContext() {
            when(boardRepository.findByIdForUpdate("hc1")).thenReturn(Optional.of(board("collecting", "traffic_light")));
            when(participantRepository.findByBoardIdAndId("hc1", "u2"))
                    .thenReturn(Optional.of(HealthCheckParticipant.builder().id("u2").boardId("hc1").nickname("Ana").role("QA").build()));
        }

        @Test
        @DisplayName("O voto é de quem chama: participantId e papel do corpo são ignorados; só o dono recebe o evento")
        void voteBelongsToCaller() {
            stubVotingContext();
            when(voteRepository.findById("hc1_u2_speed")).thenReturn(Optional.empty());
            when(voteRepository.save(any(HealthCheckVote.class))).thenAnswer(i -> i.getArgument(0));
            HealthCheckVote body = HealthCheckVote.builder().participantId("vitima").participantRole("AM").dimensionKey("speed")
                    .value("red").comment("  atrasos  ").timestamp("2000").build();

            HealthCheckVote saved = service.saveVote("hc1", body, VOTER);

            assertEquals("u2", saved.getParticipantId());
            assertEquals("QA", saved.getParticipantRole());
            assertEquals("hc1_u2_speed", saved.getId());
            assertEquals("atrasos", saved.getComment());
            assertNotEquals("2000", saved.getTimestamp());
            verify(webSocketHandler).broadcastEventToUsers(eq("hc1"), eq("VOTE_SAVED"), eq(saved), eq(Set.of("u2")));
            verify(webSocketHandler, never()).broadcastEvent(anyString(), eq("VOTE_SAVED"), any());
        }

        @Test
        @DisplayName("Voto repetido na mesma dimensão atualiza o existente (um por pessoa)")
        void secondVoteReplacesFirst() {
            stubVotingContext();
            HealthCheckVote existing = vote("u2", "speed", "green", "ok");
            when(voteRepository.findById("hc1_u2_speed")).thenReturn(Optional.of(existing));
            when(voteRepository.save(any(HealthCheckVote.class))).thenAnswer(i -> i.getArgument(0));

            HealthCheckVote saved = service.saveVote("hc1", HealthCheckVote.builder().dimensionKey("speed").value("yellow").build(), VOTER);

            assertSame(existing, saved);
            assertEquals("yellow", saved.getValue());
        }

        @Test
        @DisplayName("Valor fora da escala, dimensão inexistente e comentário gigante são recusados")
        void validatesVote() {
            stubVotingContext();
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.saveVote("hc1", HealthCheckVote.builder().dimensionKey("speed").value("5").build(), VOTER));
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.saveVote("hc1", HealthCheckVote.builder().dimensionKey("nada").value("red").build(), VOTER));
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.saveVote("hc1",
                    HealthCheckVote.builder().dimensionKey("speed").value("red").comment("x".repeat(2001)).build(), VOTER));
            verify(voteRepository, never()).save(any());
        }

        @Test
        @DisplayName("Voto depois do encerramento dá 409 e quem não entrou na sala dá 403")
        void closedBoardAndNonParticipant() {
            when(boardRepository.findByIdForUpdate("hc1")).thenReturn(Optional.of(board("finished", "traffic_light")));
            assertStatus(HttpStatus.CONFLICT, () -> service.saveVote("hc1", HealthCheckVote.builder().dimensionKey("speed").value("red").build(), VOTER));

            when(boardRepository.findByIdForUpdate("hc1")).thenReturn(Optional.of(board("collecting", "traffic_light")));
            when(participantRepository.findByBoardIdAndId("hc1", "u2")).thenReturn(Optional.empty());
            assertStatus(HttpStatus.FORBIDDEN, () -> service.saveVote("hc1", HealthCheckVote.builder().dimensionKey("speed").value("red").build(), VOTER));
        }

        @Test
        @DisplayName("Escala 1–5 aceita só 1..5")
        void numericScale() {
            when(boardRepository.findByIdForUpdate("hc1")).thenReturn(Optional.of(board("collecting", "numbers_5")));
            when(participantRepository.findByBoardIdAndId("hc1", "u2"))
                    .thenReturn(Optional.of(HealthCheckParticipant.builder().id("u2").role("DEV").build()));
            when(voteRepository.findById(anyString())).thenReturn(Optional.empty());
            when(voteRepository.save(any(HealthCheckVote.class))).thenAnswer(i -> i.getArgument(0));

            assertStatus(HttpStatus.BAD_REQUEST, () -> service.saveVote("hc1", HealthCheckVote.builder().dimensionKey("speed").value("green").build(), VOTER));
            assertEquals("4", service.saveVote("hc1", HealthCheckVote.builder().dimensionKey("speed").value("4").build(), VOTER).getValue());
        }
    }

    @Nested
    @DisplayName("Anonimato na leitura")
    class AnonymityTests {

        @Test
        @DisplayName("Coleta aberta: cada pessoa só enxerga os próprios votos (nem o criador vê os dos outros)")
        void collectingReturnsOnlyOwnVotes() {
            when(boardRepository.findById("hc1")).thenReturn(Optional.of(board("collecting", "traffic_light")));
            List<HealthCheckVote> own = List.of(vote("fac", "speed", "green", "meu"));
            when(voteRepository.findByBoardIdAndParticipantId("hc1", "fac")).thenReturn(own);

            assertSame(own, service.getVotes("hc1", CREATOR));
            verify(voteRepository, never()).findByBoardId(anyString());
        }

        @Test
        @DisplayName("Encerrado: todos os votos, sem id nem papel do votante")
        void finishedReturnsAnonymizedVotes() {
            when(boardRepository.findById("hc1")).thenReturn(Optional.of(board("finished", "traffic_light")));
            when(voteRepository.findByBoardId("hc1")).thenReturn(List.of(
                    vote("u1", "speed", "red", "lento"), vote("u2", "speed", "green", "")));

            List<HealthCheckVote> votes = service.getVotes("hc1", VOTER);

            assertEquals(2, votes.size());
            for (HealthCheckVote v : votes) {
                assertNull(v.getParticipantId());
                assertNull(v.getParticipantRole());
                assertFalse(v.getId().contains("u1") || v.getId().contains("u2"));
            }
            assertEquals("lento", votes.get(0).getComment());
        }
    }

    @Nested
    @DisplayName("Encerramento")
    class FinishTests {

        @Test
        @DisplayName("Só o criador ou ADMIN encerra; precisa de ao menos um voto")
        void finishAuthorizationAndMinimumVotes() {
            when(boardRepository.findByIdForUpdate("hc1")).thenReturn(Optional.of(board("collecting", "traffic_light")));
            assertStatus(HttpStatus.FORBIDDEN, () -> service.finish("hc1", VOTER));

            when(voteRepository.findByBoardId("hc1")).thenReturn(List.of());
            assertStatus(HttpStatus.CONFLICT, () -> service.finish("hc1", CREATOR));
            verify(boardRepository, never()).save(any());
        }

        @Test
        @DisplayName("Calcula médias, contagem por valor e destaques no servidor; publica depois")
        void computesSummary() {
            HealthCheckBoard stored = board("collecting", "traffic_light");
            when(boardRepository.findByIdForUpdate("hc1")).thenReturn(Optional.of(stored));
            when(boardRepository.save(any(HealthCheckBoard.class))).thenAnswer(i -> i.getArgument(0));
            when(voteRepository.findByBoardId("hc1")).thenReturn(List.of(
                    vote("u1", "speed", "green", ""), vote("u2", "speed", "green", ""), vote("u3", "speed", "yellow", ""),
                    vote("u1", "fun", "red", ""), vote("u2", "fun", "red", "")));

            HealthCheckBoard finished = service.finish("hc1", ADMIN);

            assertEquals("finished", finished.getStatus());
            JsonNode summary = finished.getSummary();
            JsonNode speed = summary.path("results").get(0);
            assertEquals("speed", speed.path("dimensionKey").asText());
            assertEquals(2, speed.path("values").path("green").asInt());
            assertEquals(1, speed.path("values").path("yellow").asInt());
            assertEquals((3 + 3 + 2) / 3.0, speed.path("average").asDouble(), 1e-9);
            assertEquals("Velocidade", summary.path("topMetrics").get(0).asText());
            assertEquals("Diversão", summary.path("lowMetrics").get(0).asText());
            verify(webSocketHandler).broadcastEvent(eq("hc1"), eq("BOARD_UPDATED"), eq(finished));
        }

        @Test
        @DisplayName("Encerrar de novo é idempotente e não recalcula")
        void finishIsIdempotent() {
            HealthCheckBoard stored = board("finished", "traffic_light");
            when(boardRepository.findByIdForUpdate("hc1")).thenReturn(Optional.of(stored));

            assertSame(stored, service.finish("hc1", CREATOR));
            verify(boardRepository, never()).save(any());
        }

        @Test
        @DisplayName("Na escala 1–5 'forte' é média ≥ 4 e 'fraca' é < 2,5 (a regra única de 2,0 era errada para essa escala)")
        void numericScaleThresholds() {
            HealthCheckBoard stored = board("collecting", "numbers_5");
            when(boardRepository.findByIdForUpdate("hc1")).thenReturn(Optional.of(stored));
            when(boardRepository.save(any(HealthCheckBoard.class))).thenAnswer(i -> i.getArgument(0));
            when(voteRepository.findByBoardId("hc1")).thenReturn(List.of(
                    vote("u1", "speed", "3", ""), vote("u1", "fun", "2", "")));

            JsonNode summary = service.finish("hc1", CREATOR).getSummary();

            assertEquals(0, summary.path("topMetrics").size(), "média 3 não é destaque positivo na escala 1–5");
            assertEquals(1, summary.path("lowMetrics").size());
            assertEquals("Diversão", summary.path("lowMetrics").get(0).asText());
        }
    }

    @Nested
    @DisplayName("Participantes e exclusão")
    class ParticipantTests {

        @Test
        @DisplayName("Entrada usa o id do login, papel inválido vira OUTRO e quem já entrou é devolvido sem alteração")
        void joinRules() {
            when(boardRepository.findById("hc1")).thenReturn(Optional.of(board("collecting", "traffic_light")));
            when(participantRepository.findByBoardIdAndId("hc1", "u2")).thenReturn(Optional.empty());
            when(participantRepository.save(any(HealthCheckParticipant.class))).thenAnswer(i -> i.getArgument(0));

            HealthCheckParticipant joined = service.joinBoard("hc1",
                    HealthCheckParticipant.builder().id("vitima").nickname("Ana").role("CEO").build(), VOTER);
            assertEquals("u2", joined.getId());
            assertEquals("hc1_u2", joined.getDbId());
            assertEquals("OUTRO", joined.getRole());

            HealthCheckParticipant existing = HealthCheckParticipant.builder().id("u2").role("QA").build();
            when(participantRepository.findByBoardIdAndId("hc1", "u2")).thenReturn(Optional.of(existing));
            assertSame(existing, service.joinBoard("hc1", HealthCheckParticipant.builder().role("AM").build(), VOTER));
        }

        @Test
        @DisplayName("Sair e apagar o radar respeitam dono/criador/ADMIN")
        void leaveAndDeleteAuthorization() {
            when(boardRepository.findById("hc1")).thenReturn(Optional.of(board("collecting", "traffic_light")));
            assertStatus(HttpStatus.FORBIDDEN, () -> service.leaveBoard("hc1", "fac", VOTER));
            service.leaveBoard("hc1", "u2", VOTER);
            service.leaveBoard("hc1", "u2", CREATOR);
            verify(participantRepository, times(2)).deleteByBoardIdAndId("hc1", "u2");

            when(boardRepository.findByIdForUpdate("hc1")).thenReturn(Optional.of(board("collecting", "traffic_light")));
            assertStatus(HttpStatus.FORBIDDEN, () -> service.deleteBoard("hc1", VOTER));
            service.deleteBoard("hc1", CREATOR);
            verify(voteRepository).deleteByBoardId("hc1");
            verify(participantRepository).deleteByBoardId("hc1");
            verify(boardRepository).deleteById("hc1");
        }
    }
}
