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
import com.agilespace.backend.websocket.PresenceModule;
import com.agilespace.backend.websocket.PresenceRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Rodada de cerimônia do Brainstorming (V80): encerrar/reabrir, facilitação, limite de votos, autoria e marca "editada".
 * Contrato em docs/ceremony-brainstorming-api.md. A parte contra Postgres (migração, concorrência) está em
 * BrainstormingCeremonyIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BrainstormingService - encerrar, facilitação, limite de votos, autoria e editada")
class BrainstormingCeremonyServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private BrainstormingBoardRepository boardRepository;
    @Mock private BrainstormingIdeaRepository ideaRepository;
    @Mock private BrainstormingGroupRepository groupRepository;
    @Mock private BrainstormingParticipantRepository participantRepository;
    @Mock private BrainstormingWebSocketHandler webSocketHandler;
    @Spy private PresenceRegistry presence = new PresenceRegistry();

    @InjectMocks
    private BrainstormingService service;

    private static final CeremonyCaller CREATOR = new CeremonyCaller("fac", "MEMBER");
    private static final CeremonyCaller BIA = new CeremonyCaller("bia", "MEMBER");
    private static final CeremonyCaller CAIO = new CeremonyCaller("caio", "MEMBER");
    private static final CeremonyCaller ADMIN = new CeremonyCaller("adm", "ADMIN");

    private final AtomicLong clock = new AtomicLong(1_000_000L);

    @BeforeEach
    void fixClock() {
        presence.useClock(clock::get);
    }

    private static JsonNode json(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** Mural aberto, não anônimo, ideias reveladas, criado e facilitado por "fac". */
    private static BrainstormingBoard board() {
        return BrainstormingBoard.builder().id("b1").creatorId("fac").title("Sessão").phase("ideation")
                .settings(json("{\"isAnonymous\":false}"))
                .timer(json("{\"status\":\"stopped\",\"endTime\":null,\"initialDuration\":600,\"remainingOnPause\":600}"))
                .build();
    }

    private static BrainstormingBoard board(String settingsJson) {
        BrainstormingBoard b = board();
        b.setSettings(json(settingsJson));
        return b;
    }

    private static BrainstormingIdea idea(String id, String content, String author, String votesJson) {
        return BrainstormingIdea.builder().id(id).boardId("b1").content(content).authorId(author)
                .votes(json(votesJson)).position(json("{\"x\":1,\"y\":2}")).build();
    }

    private static BrainstormingParticipant person(String id, String nickname) {
        return BrainstormingParticipant.builder().dbId("b1_" + id).id(id).boardId("b1").nickname(nickname).build();
    }

    private static void assertStatus(HttpStatus expected, Runnable action) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(expected, ex.getStatusCode(), ex.getReason());
    }

    private BrainstormingBoard givenBoard(BrainstormingBoard board) {
        lenient().when(boardRepository.findById("b1")).thenReturn(Optional.of(board));
        lenient().when(boardRepository.findByIdForUpdate("b1")).thenReturn(Optional.of(board));
        lenient().when(boardRepository.save(any(BrainstormingBoard.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(ideaRepository.save(any(BrainstormingIdea.class))).thenAnswer(i -> i.getArgument(0));
        return board;
    }

    private void givenIdeas(BrainstormingIdea... ideas) {
        for (BrainstormingIdea i : ideas) {
            lenient().when(ideaRepository.findByIdForUpdate(i.getId())).thenReturn(Optional.of(i));
            lenient().when(ideaRepository.findById(i.getId())).thenReturn(Optional.of(i));
        }
        lenient().when(ideaRepository.findByBoardId("b1")).thenReturn(List.of(ideas));
    }

    private void givenParticipants(BrainstormingParticipant... people) {
        lenient().when(participantRepository.findByBoardIdOrderByNicknameAsc("b1")).thenReturn(List.of(people));
        for (BrainstormingParticipant p : people) {
            lenient().when(participantRepository.findByBoardIdAndId("b1", p.getId())).thenReturn(Optional.of(p));
        }
    }

    // ------------------------------------------------------------------------------------------------ encerrar

    @Nested
    @DisplayName("Encerrar e reabrir")
    class FinishTests {

        @Test
        @DisplayName("O facilitador encerra: status FINISHED, finishedAt e BOARD_UPDATED com o mural")
        void facilitatorFinishes() {
            BrainstormingBoard b = givenBoard(board());

            BrainstormingBoard out = service.finishBoard("b1", CREATOR);

            assertEquals("FINISHED", out.getStatus());
            assertNotNull(out.getFinishedAt());
            verify(webSocketHandler).broadcastEvent(eq("b1"), eq("BOARD_UPDATED"), eq(b));
        }

        @Test
        @DisplayName("Mural antigo (status nulo) é OPEN, sem finishedAt, e o facilitador dele é o criador")
        void legacyBoardDefaults() {
            BrainstormingBoard b = board();
            assertEquals("OPEN", b.getStatus());
            assertFalse(b.isFinished());
            assertNull(b.getFinishedAt());
            assertEquals("fac", b.getFacilitatorId());
            assertNull(b.getVotesPerParticipant());
        }

        @Test
        @DisplayName("Quem não é facilitador, criador nem ADMIN recebe 403 (encerrar e reabrir)")
        void strangerCannotFinishOrReopen() {
            givenBoard(board());
            assertStatus(HttpStatus.FORBIDDEN, () -> service.finishBoard("b1", BIA));
            assertStatus(HttpStatus.FORBIDDEN, () -> service.reopenBoard("b1", BIA));
            verify(boardRepository, never()).save(any());
            verify(webSocketHandler, never()).broadcastEvent(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("ADMIN encerra e reabre")
        void adminFinishesAndReopens() {
            givenBoard(board());
            assertEquals("FINISHED", service.finishBoard("b1", ADMIN).getStatus());
            assertEquals("OPEN", service.reopenBoard("b1", ADMIN).getStatus());
        }

        @Test
        @DisplayName("O criador que passou a facilitação ainda encerra e reabre")
        void creatorKeepsFinishRightsAfterTransfer() {
            BrainstormingBoard b = givenBoard(board());
            b.setFacilitatorId("bia");

            assertEquals("FINISHED", service.finishBoard("b1", CREATOR).getStatus());
            assertEquals("OPEN", service.reopenBoard("b1", CREATOR).getStatus());
        }

        @Test
        @DisplayName("Encerrar é idempotente: segunda vez não regrava, não muda finishedAt e não gera evento")
        void finishIsIdempotent() {
            givenBoard(board());
            String first = service.finishBoard("b1", CREATOR).getFinishedAt();
            clock.addAndGet(5000);

            BrainstormingBoard again = service.finishBoard("b1", CREATOR);

            assertEquals(first, again.getFinishedAt());
            verify(boardRepository, times(1)).save(any());
            verify(webSocketHandler, times(1)).broadcastEvent(eq("b1"), eq("BOARD_UPDATED"), any());
        }

        @Test
        @DisplayName("Reabrir limpa finishedAt; reabrir um mural aberto é idempotente")
        void reopenClearsAndIsIdempotent() {
            givenBoard(board());
            assertSame(service.reopenBoard("b1", CREATOR), service.reopenBoard("b1", CREATOR));
            verify(boardRepository, never()).save(any());

            service.finishBoard("b1", CREATOR);
            BrainstormingBoard reopened = service.reopenBoard("b1", CREATOR);

            assertEquals("OPEN", reopened.getStatus());
            assertNull(reopened.getFinishedAt());
        }

        @Test
        @DisplayName("Mural inexistente: 404")
        void unknownBoard() {
            when(boardRepository.findByIdForUpdate("nada")).thenReturn(Optional.empty());
            assertStatus(HttpStatus.NOT_FOUND, () -> service.finishBoard("nada", CREATOR));
        }

        @Test
        @DisplayName("Encerrado: toda escrita (ideia, edição, voto, fusão, apagar, grupos) responde 409 e nada é gravado")
        void writesAreConflictAfterFinish() {
            BrainstormingBoard b = givenBoard(board());
            b.setStatus("FINISHED");
            BrainstormingIdea i1 = idea("i1", "a", "bia", "[]");
            BrainstormingIdea i2 = idea("i2", "b", "bia", "[]");
            givenIdeas(i1, i2);

            assertStatus(HttpStatus.CONFLICT, () -> service.saveOrUpdateIdea("b1", idea("novo", "x", null, "[]"), BIA));
            assertStatus(HttpStatus.CONFLICT, () -> service.patchIdea("b1", "i1", json("{\"position\":{\"x\":5,\"y\":5}}"), BIA));
            assertStatus(HttpStatus.CONFLICT, () -> service.patchIdea("b1", "i1", json("{\"groupId\":null}"), BIA));
            assertStatus(HttpStatus.CONFLICT, () -> service.toggleVote("b1", "i1", BIA));
            assertStatus(HttpStatus.CONFLICT, () -> service.mergeIdeas("b1", "i1", "i2", BIA));
            assertStatus(HttpStatus.CONFLICT, () -> service.deleteIdea("b1", "i1", BIA));
            assertStatus(HttpStatus.CONFLICT, () -> service.deleteIdea("b1", "i1", CREATOR));
            assertStatus(HttpStatus.CONFLICT, () -> service.saveOrUpdateGroup("b1", BrainstormingGroup.builder().title("G").build(), BIA));
            assertStatus(HttpStatus.CONFLICT, () -> service.deleteGroup("b1", "g1", BIA));

            verify(ideaRepository, never()).save(any());
            verify(ideaRepository, never()).deleteById(anyString());
            verify(groupRepository, never()).save(any());
            verify(webSocketHandler, never()).broadcastEvent(anyString(), anyString(), any());
            verify(webSocketHandler, never()).broadcastEventPerUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("Encerrado: a leitura segue livre e entrar na sala continua permitido")
        void readsAndJoinStillWork() {
            BrainstormingBoard b = givenBoard(board());
            b.setStatus("FINISHED");
            givenIdeas(idea("i1", "a", "bia", "[]"));
            givenParticipants(person("bia", "Bia"));
            when(participantRepository.save(any(BrainstormingParticipant.class))).thenAnswer(i -> i.getArgument(0));

            assertEquals(1, service.getIdeas("b1", CAIO).size());
            assertEquals(1, service.getParticipants("b1").size());
            assertEquals("FINISHED", service.getBoard("b1").orElseThrow().getStatus());
            assertEquals("Caio", service.joinBoard("b1", BrainstormingParticipant.builder().nickname("Caio").build(), CAIO).getNickname());
        }

        @Test
        @DisplayName("Encerrado: PATCH só aceita título; fase, timer, configurações e limite de votos dão 409; reabrir libera")
        void patchOnFinishedBoard() {
            BrainstormingBoard b = givenBoard(board());
            b.setStatus("FINISHED");

            assertStatus(HttpStatus.CONFLICT, () -> service.patchBoard("b1", json("{\"phase\":\"grouping\"}"), CREATOR));
            assertStatus(HttpStatus.CONFLICT, () -> service.patchBoard("b1", json("{\"timer\":{\"status\":\"stopped\",\"initialDuration\":60}}"), CREATOR));
            assertStatus(HttpStatus.CONFLICT, () -> service.patchBoard("b1", json("{\"settings\":{\"isAnonymous\":true}}"), CREATOR));
            assertStatus(HttpStatus.CONFLICT, () -> service.patchBoard("b1", json("{\"votesPerParticipant\":3}"), CREATOR));
            assertEquals("Novo título", service.patchBoard("b1", json("{\"title\":\"Novo título\"}"), CREATOR).getTitle());

            service.reopenBoard("b1", CREATOR);
            assertEquals("grouping", service.patchBoard("b1", json("{\"phase\":\"grouping\"}"), CREATOR).getPhase());
        }

        @Test
        @DisplayName("Reaberto, as escritas voltam")
        void writesWorkAfterReopen() {
            BrainstormingBoard b = givenBoard(board());
            b.setStatus("FINISHED");
            givenIdeas(idea("i1", "a", "bia", "[]"));
            service.reopenBoard("b1", CREATOR);

            assertNotNull(service.toggleVote("b1", "i1", BIA));
        }

        @Test
        @DisplayName("Mural apagado por quem encerrou continua permitido (apagar não é escrita de conteúdo)")
        void deleteBoardStillAllowedWhenFinished() {
            BrainstormingBoard b = givenBoard(board());
            b.setStatus("FINISHED");
            service.deleteBoard("b1", CREATOR);
            verify(boardRepository).deleteById("b1");
        }
    }

    // ------------------------------------------------------------------------------------------------ facilitação

    @Nested
    @DisplayName("Facilitação: assumir e passar")
    class FacilitationTests {

        @Test
        @DisplayName("Facilitador online: 409 'está online'")
        void onlineFacilitatorBlocksClaim() {
            givenBoard(board());
            givenParticipants(person("bia", "Bia"));
            presence.join(PresenceModule.BRAINSTORMING, "b1", "fac", "s1", null);

            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.claimFacilitation("b1", BIA));

            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
            assertEquals("O facilitador está online.", ex.getReason());
            verify(boardRepository, never()).save(any());
        }

        @Test
        @DisplayName("Facilitador offline há menos que o limite: 409; passado o limite: assume, nome e evento")
        void offlineFacilitatorMustBeGoneLongEnough() {
            BrainstormingBoard b = givenBoard(board());
            givenParticipants(person("bia", "Bia"));
            presence.join(PresenceModule.BRAINSTORMING, "b1", "fac", "s1", null);
            presence.leave(PresenceModule.BRAINSTORMING, "b1", "fac", "s1");

            clock.addAndGet(30_000);
            assertStatus(HttpStatus.CONFLICT, () -> service.claimFacilitation("b1", BIA));
            clock.addAndGet(30_000); // exatamente 60 s: ainda não é "mais de" 60
            assertStatus(HttpStatus.CONFLICT, () -> service.claimFacilitation("b1", BIA));
            clock.addAndGet(1_000);

            BrainstormingBoard out = service.claimFacilitation("b1", BIA);

            assertEquals("bia", out.getFacilitatorId());
            assertEquals("Bia", out.getFacilitatorName());
            assertEquals("fac", out.getCreatorId(), "o criador original não muda");
            verify(webSocketHandler).broadcastEvent(eq("b1"), eq("BOARD_UPDATED"), eq(b));
        }

        @Test
        @DisplayName("Facilitador que reconecta zera a espera")
        void reconnectResetsWait() {
            givenBoard(board());
            givenParticipants(person("bia", "Bia"));
            presence.join(PresenceModule.BRAINSTORMING, "b1", "fac", "s1", null);
            presence.leave(PresenceModule.BRAINSTORMING, "b1", "fac", "s1");
            clock.addAndGet(120_000);
            presence.join(PresenceModule.BRAINSTORMING, "b1", "fac", "s2", null);

            assertStatus(HttpStatus.CONFLICT, () -> service.claimFacilitation("b1", BIA));
        }

        @Test
        @DisplayName("Quem nunca esteve online neste processo conta como offline desde o início dele (nada de assumir logo após reiniciar)")
        void neverSeenFacilitatorIsOfflineSinceProcessStart() {
            givenBoard(board());
            givenParticipants(person("bia", "Bia"));
            // relógio real de pé: o processo acabou de subir (startedAt ~ agora) -> menos de 60 s
            presence.useClock(System::currentTimeMillis);

            assertStatus(HttpStatus.CONFLICT, () -> service.claimFacilitation("b1", BIA));
        }

        @Test
        @DisplayName("Quem não está na lista de participantes não assume: 403")
        void nonParticipantCannotClaim() {
            givenBoard(board());
            when(participantRepository.findByBoardIdAndId("b1", "caio")).thenReturn(Optional.empty());
            clock.addAndGet(600_000);

            assertStatus(HttpStatus.FORBIDDEN, () -> service.claimFacilitation("b1", CAIO));
        }

        @Test
        @DisplayName("Criador e ADMIN assumem mesmo com o facilitador online")
        void creatorAndAdminClaimAlways() {
            BrainstormingBoard b = givenBoard(board());
            b.setFacilitatorId("bia");
            presence.join(PresenceModule.BRAINSTORMING, "b1", "bia", "s1", null);

            assertEquals("fac", service.claimFacilitation("b1", CREATOR).getFacilitatorId());
            assertEquals("adm", service.claimFacilitation("b1", ADMIN).getFacilitatorId());
        }

        @Test
        @DisplayName("Quem já é o facilitador: idempotente, sem evento")
        void claimByCurrentFacilitatorIsNoop() {
            givenBoard(board());
            assertEquals("fac", service.claimFacilitation("b1", CREATOR).getFacilitatorId());
            verify(boardRepository, never()).save(any());
            verify(webSocketHandler, never()).broadcastEvent(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("Passar a facilitação: facilitador atual para um participante; evento e nome")
        void transferByFacilitator() {
            BrainstormingBoard b = givenBoard(board());
            givenParticipants(person("bia", "Bia"));

            BrainstormingBoard out = service.transferFacilitation("b1", " bia ", CREATOR);

            assertEquals("bia", out.getFacilitatorId());
            assertEquals("Bia", out.getFacilitatorName());
            verify(webSocketHandler).broadcastEvent(eq("b1"), eq("BOARD_UPDATED"), eq(b));
        }

        @Test
        @DisplayName("Passar: só o facilitador atual ou ADMIN (o criador que já passou não passa de novo)")
        void transferPermissions() {
            BrainstormingBoard b = givenBoard(board());
            givenParticipants(person("bia", "Bia"), person("caio", "Caio"));

            assertStatus(HttpStatus.FORBIDDEN, () -> service.transferFacilitation("b1", "bia", CAIO));
            assertEquals("bia", service.transferFacilitation("b1", "bia", ADMIN).getFacilitatorId());
            assertStatus(HttpStatus.FORBIDDEN, () -> service.transferFacilitation("b1", "caio", CREATOR));
            assertEquals("caio", service.transferFacilitation("b1", "caio", BIA).getFacilitatorId());
            assertEquals("fac", b.getCreatorId());
        }

        @Test
        @DisplayName("Passar: destino fora da sala 404, userId vazio 400, para si mesmo é idempotente")
        void transferValidation() {
            givenBoard(board());
            when(participantRepository.findByBoardIdAndId("b1", "fantasma")).thenReturn(Optional.empty());

            assertStatus(HttpStatus.NOT_FOUND, () -> service.transferFacilitation("b1", "fantasma", CREATOR));
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.transferFacilitation("b1", "  ", CREATOR));
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.transferFacilitation("b1", null, CREATOR));
            assertEquals("fac", service.transferFacilitation("b1", "fac", CREATOR).getFacilitatorId());
            verify(boardRepository, never()).save(any());
        }

        @Test
        @DisplayName("Depois de passar: o novo facilitador muda a sessão; o anterior perde esse poder")
        void powersFollowTheFacilitator() {
            givenBoard(board());
            givenParticipants(person("bia", "Bia"));
            service.transferFacilitation("b1", "bia", CREATOR);

            assertEquals("grouping", service.patchBoard("b1", json("{\"phase\":\"grouping\"}"), BIA).getPhase());
            assertStatus(HttpStatus.FORBIDDEN, () -> service.patchBoard("b1", json("{\"phase\":\"actions\"}"), CREATOR));
        }

        @Test
        @DisplayName("Quem recebeu a facilitação vê as ideias ocultas; o criador anterior deixa de ver")
        void hiddenIdeasFollowTheFacilitator() {
            BrainstormingBoard b = board("{\"isRevealed\":false}");
            b.setFacilitatorId("bia");
            BrainstormingIdea secret = idea("i1", "segredo", "caio", "[\"x\"]");

            assertEquals("segredo", BrainstormingRedaction.redact(secret, b, BIA).getContent());
            assertEquals("", BrainstormingRedaction.redact(secret, b, CREATOR).getContent());
            assertEquals("segredo", BrainstormingRedaction.redact(secret, b, ADMIN).getContent());
        }

        @Test
        @DisplayName("Sem PresenceRegistry (não deveria ocorrer) o servidor não deixa assumir: trata como online")
        void withoutPresenceRegistryItIsConservative() {
            BrainstormingService noPresence = new BrainstormingService(boardRepository, ideaRepository, groupRepository,
                    participantRepository, webSocketHandler, null);
            givenBoard(board());
            givenParticipants(person("bia", "Bia"));

            assertStatus(HttpStatus.CONFLICT, () -> noPresence.claimFacilitation("b1", BIA));
        }
    }

    // ------------------------------------------------------------------------------------------------ limite de votos

    @Nested
    @DisplayName("Limite de votos por pessoa")
    class VoteLimitTests {

        private BrainstormingBoard limited(Integer limit) {
            BrainstormingBoard b = givenBoard(board());
            b.setVotesPerParticipant(limit);
            return b;
        }

        @Test
        @DisplayName("Sem limite (nulo) o voto é o de sempre e o mural não é travado")
        void noLimitMeansNoLock() {
            limited(null);
            BrainstormingIdea i1 = idea("i1", "a", "caio", "[\"bia\"]");
            BrainstormingIdea i2 = idea("i2", "b", "caio", "[\"bia\"]");
            BrainstormingIdea i3 = idea("i3", "c", "caio", "[]");
            givenIdeas(i1, i2, i3);

            assertTrue(service.toggleVote("b1", "i3", BIA).getVotes().toString().contains("bia"));
            verify(boardRepository, never()).findByIdForUpdate(anyString());
        }

        @Test
        @DisplayName("Com limite 2: o terceiro voto da pessoa é 409; o de outra pessoa passa")
        void thirdVoteIsRefused() {
            limited(2);
            BrainstormingIdea i1 = idea("i1", "a", "caio", "[\"bia\"]");
            BrainstormingIdea i2 = idea("i2", "b", "caio", "[\"bia\"]");
            BrainstormingIdea i3 = idea("i3", "c", "caio", "[]");
            givenIdeas(i1, i2, i3);

            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.toggleVote("b1", "i3", BIA));

            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
            assertEquals("Você já usou seus 2 votos. Remova um voto para votar em outra ideia.", ex.getReason());
            assertEquals(0, i3.getVotes().size(), "a ideia não pode ter sido alterada");
            assertTrue(service.toggleVote("b1", "i3", CAIO).getVotes().toString().contains("caio"));
        }

        @Test
        @DisplayName("Limite 1 tem mensagem no singular")
        void singularMessage() {
            limited(1);
            givenIdeas(idea("i1", "a", "caio", "[\"bia\"]"), idea("i2", "b", "caio", "[]"));
            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.toggleVote("b1", "i2", BIA));
            assertEquals("Você já usou seu voto. Remova um voto para votar em outra ideia.", ex.getReason());
        }

        @Test
        @DisplayName("Remover um voto devolve a cota e funciona mesmo no limite")
        void removingGivesTheQuotaBack() {
            limited(1);
            BrainstormingIdea i1 = idea("i1", "a", "caio", "[\"bia\"]");
            BrainstormingIdea i2 = idea("i2", "b", "caio", "[]");
            givenIdeas(i1, i2);

            assertEquals(0, service.toggleVote("b1", "i1", BIA).getVotes().size(), "remover no limite sempre funciona");
            assertTrue(service.toggleVote("b1", "i2", BIA).getVotes().toString().contains("bia"));
        }

        @Test
        @DisplayName("Votos em ideias ocultas contam (o servidor vê os votos reais)")
        void hiddenIdeaVotesCount() {
            limited(1);
            givenIdeas(idea("i1", "a", "caio", "[\"bia\"]"), idea("i2", "b", "caio", "[]"));
            assertStatus(HttpStatus.CONFLICT, () -> service.toggleVote("b1", "i2", BIA));
        }

        @Test
        @DisplayName("Com limite, o mural é travado (lock) antes da ideia para serializar votos da mesma pessoa")
        void boardLockedBeforeIdea() {
            limited(3);
            givenIdeas(idea("i1", "a", "caio", "[]"));

            service.toggleVote("b1", "i1", BIA);

            var order = inOrder(boardRepository, ideaRepository);
            order.verify(boardRepository).findByIdForUpdate("b1");
            order.verify(ideaRepository).findByIdForUpdate("i1");
        }

        @Test
        @DisplayName("Reduzir o limite abaixo do que alguém já usou é permitido: ninguém perde voto, só não vota mais")
        void reducingBelowUsageKeepsVotes() {
            BrainstormingBoard b = limited(5);
            BrainstormingIdea i1 = idea("i1", "a", "caio", "[\"bia\"]");
            BrainstormingIdea i2 = idea("i2", "b", "caio", "[\"bia\"]");
            BrainstormingIdea i3 = idea("i3", "c", "caio", "[]");
            givenIdeas(i1, i2, i3);

            assertEquals(1, service.patchBoard("b1", json("{\"votesPerParticipant\":1}"), CREATOR).getVotesPerParticipant());

            assertEquals(1, i1.getVotes().size());
            assertEquals(1, i2.getVotes().size());
            assertStatus(HttpStatus.CONFLICT, () -> service.toggleVote("b1", "i3", BIA));
            service.toggleVote("b1", "i1", BIA); // remove
            assertStatus(HttpStatus.CONFLICT, () -> service.toggleVote("b1", "i3", BIA)); // ainda tem 1 (i2) e o limite é 1
            assertEquals(1, b.getVotesPerParticipant());
        }

        @Test
        @DisplayName("PATCH do limite: 1..100 ou null (remove); 0, 101, texto e decimal são 400; ausente não mexe")
        void patchValidation() {
            BrainstormingBoard b = limited(3);

            assertEquals(7, service.patchBoard("b1", json("{\"votesPerParticipant\":7}"), CREATOR).getVotesPerParticipant());
            assertEquals(7, service.patchBoard("b1", json("{\"title\":\"X\"}"), CREATOR).getVotesPerParticipant());
            assertEquals(100, service.patchBoard("b1", json("{\"votesPerParticipant\":100}"), CREATOR).getVotesPerParticipant());
            assertNull(service.patchBoard("b1", json("{\"votesPerParticipant\":null}"), CREATOR).getVotesPerParticipant());
            b.setVotesPerParticipant(4);
            for (String bad : List.of("0", "-1", "101", "\"3\"", "2.5", "true")) {
                assertStatus(HttpStatus.BAD_REQUEST, () -> service.patchBoard("b1", json("{\"votesPerParticipant\":" + bad + "}"), CREATOR));
            }
            assertEquals(4, b.getVotesPerParticipant());
            assertStatus(HttpStatus.FORBIDDEN, () -> service.patchBoard("b1", json("{\"votesPerParticipant\":2}"), BIA));
        }

        @Test
        @DisplayName("Criação com limite e restrictEdits; limite inválido na criação é 400")
        void createWithLimit() {
            when(boardRepository.save(any(BrainstormingBoard.class))).thenAnswer(i -> i.getArgument(0));
            BrainstormingBoard body = BrainstormingBoard.builder().title("T")
                    .votesPerParticipant(3).settings(json("{\"restrictEdits\":true,\"isAnonymous\":true}"))
                    .status("FINISHED").finishedAt("x").facilitatorId("impostor").build();

            BrainstormingBoard created = service.saveOrUpdateBoard(body, BIA).value();

            assertEquals(3, created.getVotesPerParticipant());
            assertTrue(created.getSettings().path("restrictEdits").asBoolean());
            assertEquals("OPEN", created.getStatus(), "status do corpo é ignorado");
            assertNull(created.getFinishedAt());
            assertEquals("bia", created.getFacilitatorId(), "facilitador do corpo é ignorado: é o criador");

            assertStatus(HttpStatus.BAD_REQUEST, () -> service.saveOrUpdateBoard(BrainstormingBoard.builder().title("T").votesPerParticipant(0).build(), BIA));
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.saveOrUpdateBoard(BrainstormingBoard.builder().title("T").votesPerParticipant(500).build(), BIA));
        }

        @Test
        @DisplayName("Fusão une votos sem repetir: quem votou nas duas ideias recupera um voto")
        void mergeReturnsQuota() {
            limited(2);
            BrainstormingIdea i1 = idea("i1", "a", "caio", "[\"bia\"]");
            BrainstormingIdea i2 = idea("i2", "b", "caio", "[\"bia\"]");
            BrainstormingIdea i3 = idea("i3", "c", "caio", "[]");
            givenIdeas(i1, i2, i3);

            service.mergeIdeas("b1", "i1", "i2", BIA);
            lenient().when(ideaRepository.findByBoardId("b1")).thenReturn(List.of(i1, i3));

            assertTrue(service.toggleVote("b1", "i3", BIA).getVotes().toString().contains("bia"));
        }
    }

    // ------------------------------------------------------------------------------------------------ autoria e editada

    @Nested
    @DisplayName("Autor, facilitador na lista e marca 'editada'")
    class AuthorshipTests {

        @Test
        @DisplayName("Mural não anônimo: a ideia sai com authorName e editedByName vindos da lista de participantes")
        void namesAppearOnOpenBoards() {
            givenBoard(board());
            BrainstormingIdea i = idea("i1", "texto", "bia", "[]");
            i.setEditedAt("2026-10-17T10:00:00Z");
            i.setEditedBy("caio");
            givenIdeas(i);
            givenParticipants(person("bia", "Bia"), person("caio", "Caio"));

            BrainstormingIdea out = service.getIdeas("b1", CREATOR).get(0);

            assertEquals("Bia", out.getAuthorName());
            assertEquals("Caio", out.getEditedByName());
            assertEquals("caio", out.getEditedBy());
            assertEquals("2026-10-17T10:00:00Z", out.getEditedAt());
        }

        @Test
        @DisplayName("Autor ausente da lista: sem nome (não quebra)")
        void unknownAuthorHasNoName() {
            givenBoard(board());
            givenIdeas(idea("i1", "texto", "saiu", "[]"));
            givenParticipants(person("bia", "Bia"));

            assertNull(service.getIdeas("b1", CREATOR).get(0).getAuthorName());
        }

        @Test
        @DisplayName("Mural anônimo: nunca authorName/editedBy/editedByName, para ninguém (facilitador e ADMIN incluídos); editedAt sai")
        void anonymousBoardNeverLeaksIdentity() throws Exception {
            givenBoard(board("{\"isAnonymous\":true}"));
            BrainstormingIdea i = idea("i1", "texto", "bia", "[]");
            i.setEditedAt("2026-10-17T10:00:00Z");
            i.setEditedBy("caio");
            givenIdeas(i);
            givenParticipants(person("bia", "Bia"), person("caio", "Caio"));

            for (CeremonyCaller reader : List.of(CREATOR, ADMIN, CAIO)) {
                BrainstormingIdea out = service.getIdeas("b1", reader).get(0);
                assertNull(out.getAuthorId(), reader.id());
                assertNull(out.getAuthorName(), reader.id());
                assertNull(out.getEditedBy(), reader.id());
                assertNull(out.getEditedByName(), reader.id());
                assertEquals("2026-10-17T10:00:00Z", out.getEditedAt(), reader.id());
                String serialized = JSON.writeValueAsString(out);
                String lower = serialized.toLowerCase();
                assertFalse(lower.contains("bia") || lower.contains("caio"), serialized);
            }
            verify(participantRepository, never()).findByBoardIdOrderByNicknameAsc(anyString());
        }

        @Test
        @DisplayName("Mural anônimo: o autor vê o próprio id, mas não quem mais editou a ideia dele; vê se foi ele mesmo")
        void anonymousAuthorSeesOnlySelfEdits() {
            givenBoard(board("{\"isAnonymous\":true}"));
            BrainstormingIdea editedByOther = idea("i1", "texto", "bia", "[]");
            editedByOther.setEditedAt("t");
            editedByOther.setEditedBy("caio");
            BrainstormingIdea editedBySelf = idea("i2", "texto", "bia", "[]");
            editedBySelf.setEditedAt("t");
            editedBySelf.setEditedBy("bia");
            givenIdeas(editedByOther, editedBySelf);

            List<BrainstormingIdea> seen = service.getIdeas("b1", BIA);

            assertEquals("bia", seen.get(0).getAuthorId());
            assertNull(seen.get(0).getEditedBy());
            assertEquals("t", seen.get(0).getEditedAt());
            assertEquals("bia", seen.get(1).getEditedBy());
            assertEquals("caio", editedByOther.getEditedBy(), "a entidade não pode ser alterada");
        }

        @Test
        @DisplayName("Ideias ocultas: quem não é autor/facilitador não recebe nome, quem editou nem editedAt; o autor e o facilitador recebem")
        void hiddenIdeasLeakNothing() {
            givenBoard(board("{\"isRevealed\":false}"));
            BrainstormingIdea i = idea("i1", "segredo", "bia", "[\"x\"]");
            i.setEditedAt("t");
            i.setEditedBy("bia");
            givenIdeas(i);
            givenParticipants(person("bia", "Bia"));

            BrainstormingIdea other = service.getIdeas("b1", CAIO).get(0);
            assertEquals("", other.getContent());
            assertNull(other.getAuthorName());
            assertNull(other.getEditedBy());
            assertNull(other.getEditedByName());
            assertNull(other.getEditedAt());
            assertTrue(other.getHidden());

            BrainstormingIdea facilitator = service.getIdeas("b1", CREATOR).get(0);
            assertEquals("Bia", facilitator.getAuthorName());
            assertEquals("t", facilitator.getEditedAt());
            assertEquals("Bia", service.getIdeas("b1", BIA).get(0).getAuthorName());
        }

        @Test
        @DisplayName("Resposta REST (viewIdea) e evento por destinatário aplicam os mesmos nomes e a mesma redação")
        void viewAndPublishAreConsistent() {
            givenBoard(board("{\"isRevealed\":false}"));
            BrainstormingIdea i = idea("i1", "segredo", "bia", "[]");
            givenParticipants(person("bia", "Bia"));

            assertEquals("Bia", service.viewIdea("b1", i, CREATOR).getAuthorName());
            assertNull(service.viewIdea("b1", i, CAIO).getAuthorName());
        }

        @Test
        @DisplayName("Participantes saem com displayName e a flag facilitator (só o facilitador atual)")
        void participantsDescribeFacilitator() {
            BrainstormingBoard b = givenBoard(board());
            b.setFacilitatorId("bia");
            when(participantRepository.findByBoardIdOrderByNicknameAsc("b1"))
                    .thenReturn(List.of(person("bia", "Bia"), person("fac", "Fac")));

            List<BrainstormingParticipant> out = service.getParticipants("b1");

            assertEquals("Bia", out.get(0).getDisplayName());
            assertTrue(out.get(0).getFacilitator());
            assertFalse(out.get(1).getFacilitator(), "o criador que passou a facilitação não é mais marcado");
        }

        @Test
        @DisplayName("Entrar na sala devolve o participante já com displayName e facilitator")
        void joinReturnsDescribedParticipant() {
            givenBoard(board());
            when(participantRepository.findByBoardIdAndId("b1", "fac")).thenReturn(Optional.empty());
            when(participantRepository.save(any(BrainstormingParticipant.class))).thenAnswer(i -> i.getArgument(0));

            BrainstormingParticipant joined = service.joinBoard("b1", BrainstormingParticipant.builder().nickname("Fac").build(), CREATOR);

            assertEquals("Fac", joined.getDisplayName());
            assertTrue(joined.getFacilitator());
            assertTrue(joined.getIsCreator());
        }

        @Test
        @DisplayName("GET do mural traz facilitatorId e facilitatorName; a lista de murais também")
        void boardNamesFacilitator() {
            BrainstormingBoard b = givenBoard(board());
            b.setFacilitatorId("bia");
            givenParticipants(person("bia", "Bia"));
            when(participantRepository.findAllById(any())).thenReturn(List.of(person("bia", "Bia")));
            when(boardRepository.findByTeamIgnoreCaseOrderByCreatedAtDesc("SQ")).thenReturn(List.of(b));

            BrainstormingBoard one = service.getBoard("b1").orElseThrow();
            assertEquals("bia", one.getFacilitatorId());
            assertEquals("Bia", one.getFacilitatorName());
            assertEquals("Bia", service.listBoards("SQ").get(0).getFacilitatorName());
        }

        @Test
        @DisplayName("Editar o texto grava editedAt e editedBy com o userId do TOKEN (o corpo é ignorado)")
        void editRecordsTokenIdentity() {
            givenBoard(board());
            BrainstormingIdea i = idea("i1", "antes", "bia", "[]");
            givenIdeas(i);

            BrainstormingIdea saved = service.patchIdea("b1", "i1", json("{\"content\":\"depois\",\"editedBy\":\"impostor\",\"editedAt\":\"1999\"}"), CAIO);

            assertEquals("depois", saved.getContent());
            assertEquals("caio", saved.getEditedBy());
            assertNotNull(saved.getEditedAt());
            assertNotEquals("1999", saved.getEditedAt());
            assertEquals("bia", saved.getAuthorId(), "o autor não muda");
        }

        @Test
        @DisplayName("POST de ideia existente (abas antigas) com o mesmo texto não marca como editada; com texto novo marca; com editedBy forjado ignora")
        void legacyPostEditMarking() {
            givenBoard(board());
            BrainstormingIdea i = idea("i1", "igual", "bia", "[]");
            givenIdeas(i);

            service.saveOrUpdateIdea("b1", idea("i1", "igual", "bia", "[]"), CAIO);
            assertNull(i.getEditedAt());
            assertNull(i.getEditedBy());

            BrainstormingIdea forged = idea("i1", "novo texto", "bia", "[]");
            forged.setEditedBy("impostor");
            forged.setEditedAt("1999");
            service.saveOrUpdateIdea("b1", forged, CAIO);
            assertEquals("novo texto", i.getContent());
            assertEquals("caio", i.getEditedBy());
            assertNotEquals("1999", i.getEditedAt());
        }

        @Test
        @DisplayName("Mover, agrupar, ligar e priorizar não contam como edição")
        void nonTextChangesAreNotEdits() {
            givenBoard(board());
            BrainstormingIdea i = idea("i1", "texto", "bia", "[]");
            givenIdeas(i);
            lenient().when(groupRepository.findById("g1")).thenReturn(Optional.of(BrainstormingGroup.builder().id("g1").boardId("b1").build()));

            service.patchIdea("b1", "i1", json("{\"position\":{\"x\":9,\"y\":9},\"qualifiers\":{\"roi\":80},\"groupId\":\"g1\",\"color\":\"red\"}"), CAIO);
            service.patchIdea("b1", "i1", json("{\"content\":\"texto\"}"), CAIO); // mesmo texto

            assertNull(i.getEditedAt());
            assertNull(i.getEditedBy());
        }

        @Test
        @DisplayName("Fusão registra quem juntou na ideia de destino")
        void mergeMarksTarget() {
            givenBoard(board());
            BrainstormingIdea target = idea("i1", "a", "bia", "[]");
            BrainstormingIdea source = idea("i2", "b", "caio", "[]");
            givenIdeas(target, source);

            service.mergeIdeas("b1", "i1", "i2", CAIO);

            assertEquals("caio", target.getEditedBy());
            assertNotNull(target.getEditedAt());
        }
    }

    // ------------------------------------------------------------------------------------------------ quem edita / apaga

    @Nested
    @DisplayName("Quem edita e quem apaga ideia alheia")
    class EditPolicyTests {

        @Test
        @DisplayName("Padrão (sem restrictEdits): qualquer participante edita o texto de qualquer ideia, como hoje")
        void defaultAnyoneEdits() {
            givenBoard(board());
            BrainstormingIdea i = idea("i1", "antes", "bia", "[]");
            givenIdeas(i);

            assertEquals("depois", service.patchIdea("b1", "i1", json("{\"content\":\"depois\"}"), CAIO).getContent());
        }

        @Test
        @DisplayName("Com restrictEdits: só autor, facilitador e ADMIN mudam o texto; os demais recebem 403 e nada muda")
        void restrictedEditsOnlyAuthorOrFacilitator() {
            givenBoard(board("{\"restrictEdits\":true}"));
            BrainstormingIdea i = idea("i1", "antes", "bia", "[]");
            givenIdeas(i);

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.patchIdea("b1", "i1", json("{\"content\":\"invasão\"}"), CAIO));
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
            assertEquals("Só o autor da ideia ou o facilitador podem editar o texto.", ex.getReason());
            assertStatus(HttpStatus.FORBIDDEN, () -> service.saveOrUpdateIdea("b1", idea("i1", "invasão 2", "bia", "[]"), CAIO));
            assertEquals("antes", i.getContent());
            assertNull(i.getEditedBy());

            assertEquals("do autor", service.patchIdea("b1", "i1", json("{\"content\":\"do autor\"}"), BIA).getContent());
            assertEquals("do facilitador", service.patchIdea("b1", "i1", json("{\"content\":\"do facilitador\"}"), CREATOR).getContent());
            assertEquals("do admin", service.patchIdea("b1", "i1", json("{\"content\":\"do admin\"}"), ADMIN).getContent());
        }

        @Test
        @DisplayName("Com restrictEdits, mover, agrupar, ligar, priorizar, votar e fundir seguem livres para todos")
        void restrictedEditsKeepsOtherFlowsOpen() {
            givenBoard(board("{\"restrictEdits\":true}"));
            BrainstormingIdea i1 = idea("i1", "a", "bia", "[]");
            BrainstormingIdea i2 = idea("i2", "b", "bia", "[]");
            givenIdeas(i1, i2);
            lenient().when(groupRepository.findById("g1")).thenReturn(Optional.of(BrainstormingGroup.builder().id("g1").boardId("b1").build()));

            service.patchIdea("b1", "i1", json("{\"position\":{\"x\":3,\"y\":4}}"), CAIO);
            service.patchIdea("b1", "i1", json("{\"groupId\":\"g1\"}"), CAIO);
            service.patchIdea("b1", "i1", json("{\"qualifiers\":{\"roi\":70,\"effort\":10}}"), CAIO);
            service.patchIdea("b1", "i1", json("{\"parentId\":\"i2\"}"), CAIO);
            service.patchIdea("b1", "i1", json("{\"content\":\"a\"}"), CAIO); // abas antigas: mesmo texto
            service.toggleVote("b1", "i1", CAIO);
            BrainstormingIdea merged = service.mergeIdeas("b1", "i1", "i2", CAIO);

            assertTrue(merged.getContent().contains("b"));
        }

        @Test
        @DisplayName("Apagar ideia: autor, facilitador atual ou ADMIN; outra pessoa 403 (já era assim; o servidor impõe)")
        void deleteRules() {
            BrainstormingBoard b = givenBoard(board());
            givenIdeas(idea("i1", "a", "bia", "[]"));

            assertStatus(HttpStatus.FORBIDDEN, () -> service.deleteIdea("b1", "i1", CAIO));
            service.deleteIdea("b1", "i1", BIA);
            service.deleteIdea("b1", "i1", CREATOR);
            service.deleteIdea("b1", "i1", ADMIN);
            verify(ideaRepository, times(3)).deleteById("i1");

            b.setFacilitatorId("caio");
            assertStatus(HttpStatus.FORBIDDEN, () -> service.deleteIdea("b1", "i1", CREATOR));
            service.deleteIdea("b1", "i1", CAIO);
            verify(ideaRepository, times(4)).deleteById("i1");
        }
    }
}
