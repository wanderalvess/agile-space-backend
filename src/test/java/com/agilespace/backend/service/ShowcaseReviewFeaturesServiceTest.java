package com.agilespace.backend.service;

import com.agilespace.backend.domain.ShowcaseEvidence;
import com.agilespace.backend.domain.ShowcaseImpactMetric;
import com.agilespace.backend.domain.ShowcaseParticipant;
import com.agilespace.backend.domain.ShowcaseSession;
import com.agilespace.backend.domain.ShowcaseTask;
import com.agilespace.backend.domain.ShowcaseTaskFile;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.ShowcasePresenterDto;
import com.agilespace.backend.repository.ShowcaseMemberRepository;
import com.agilespace.backend.repository.ShowcaseParticipantRepository;
import com.agilespace.backend.repository.ShowcaseSessionRepository;
import com.agilespace.backend.repository.ShowcaseTaskRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.websocket.PresenceModule;
import com.agilespace.backend.websocket.PresenceRegistry;
import com.agilespace.backend.websocket.ShowcaseWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Trava PO/SME, versão por card (mescla do POST antigo e PATCH), participantes do resumo e apresentador, no nível do serviço
 * (docs/ceremony-review-api.md). A parte com Postgres real está em ReviewCeremonyIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
class ShowcaseReviewFeaturesServiceTest {

    private static final String SESSION = "session-456";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private ShowcaseSessionRepository repository;
    @Mock private ShowcaseTaskRepository taskRepository;
    @Mock private ShowcaseMemberRepository memberRepository;
    @Mock private ShowcaseWebSocketHandler webSocketHandler;
    @Mock private ShowcaseTaskFileService fileService;
    @Mock private UserRepository userRepository;
    @Mock private ShowcaseAccessPolicy accessPolicy;
    @Mock private ShowcaseParticipantRepository participantRepository;
    @Spy private ShowcasePresenterRegistry presenterRegistry = new ShowcasePresenterRegistry(240);
    @Spy private PresenceRegistry presenceRegistry = new PresenceRegistry();

    @InjectMocks private ShowcaseSessionService service;

    private ShowcaseSession stored;

    @BeforeEach
    void setUp() {
        stored = ShowcaseSession.builder().id(SESSION).name("Review").status("active").squadName("SQ1")
                .createdBy("criador").createdAt(LocalDateTime.of(2026, 10, 1, 10, 0)).build();
    }

    // ---------- helpers ----------

    private static ShowcaseTask card(String id, String title, String decision, String feedback, Long version) {
        return ShowcaseTask.builder().id(id).sessionId(SESSION).key("PTV-" + id).title(title).decision(decision)
                .feedback(feedback).version(version).evidence(ShowcaseEvidence.builder().problem("P").solution("S").build())
                .metrics(new ArrayList<>()).build();
    }

    private void existingSession() {
        when(repository.findByIdForUpdate(SESSION)).thenReturn(Optional.of(stored));
        when(repository.save(any(ShowcaseSession.class))).thenAnswer(i -> i.getArgument(0));
    }

    private void storedCards(ShowcaseTask... cards) {
        when(taskRepository.findBySessionIdOrderByOrderAsc(SESSION)).thenReturn(new ArrayList<>(List.of(cards)));
    }

    private ShowcaseSession incoming(String status, ShowcaseTask... cards) {
        return ShowcaseSession.builder().id(SESSION).name("Review").status(status).squadName("SQ1")
                .tasks(new ArrayList<>(List.of(cards))).members(new ArrayList<>()).build();
    }

    @SuppressWarnings("unchecked")
    private List<ShowcaseTask> savedCards() {
        ArgumentCaptor<List<ShowcaseTask>> cap = ArgumentCaptor.forClass(List.class);
        verify(taskRepository).saveAll(cap.capture());
        return cap.getValue();
    }

    private void lockOn(boolean canDecide) {
        when(accessPolicy.decisionLockEnforced()).thenReturn(true);
        when(accessPolicy.canDecide(any(), any(), anyString())).thenReturn(canDecide);
    }

    // ---------- 1. trava PO/SME ----------

    @Nested
    @DisplayName("Trava PO/SME no POST da sessão inteira")
    class DecisionLockOnPost {

        @Test
        @DisplayName("quem não pode decidir e manda a sessão inteira SEM mudar decisão/feedback: aceito")
        void nonDeciderWithoutDecisionChangeIsAccepted() {
            existingSession();
            ShowcaseTask before = card("t1", "Antigo", "approved", "ok", 1L);
            storedCards(before);
            ShowcaseTask edited = card("t1", "Novo título", "approved", "ok", 1L);
            when(accessPolicy.decisionLockEnforced()).thenReturn(true);

            ShowcaseSession saved = service.saveSession(incoming("active", edited), "dev-1");

            assertNotNull(saved);
            verify(accessPolicy, never()).canDecide(any(), any(), any());
            assertEquals(2L, savedCards().get(0).getVersion(), "conteúdo mudou: a versão sobe");
        }

        @Test
        @DisplayName("null, vazio e 'open' são a mesma coisa: sem decisão não conta como mudança")
        void blankAndOpenAreTheSameAsNoDecision() {
            existingSession();
            storedCards(card("t1", "T", null, null, 1L));
            when(accessPolicy.decisionLockEnforced()).thenReturn(true);

            service.saveSession(incoming("active", card("t1", "T", "open", "", 1L)), "dev-1");

            verify(accessPolicy, never()).canDecide(any(), any(), any());
        }

        @Test
        @DisplayName("quem não pode decidir e muda o veredito: 403 DECISION_RESTRICTED, nada é gravado")
        void nonDeciderChangingDecisionIsRejected() {
            existingSession();
            storedCards(card("t1", "T", "open", "", 1L), card("t2", "T2", "open", "", 1L));
            lockOn(false);

            ShowcaseSessionService.DecisionRestrictedException ex = assertThrows(ShowcaseSessionService.DecisionRestrictedException.class,
                    () -> service.saveSession(incoming("active", card("t1", "T", "approved", "", 1L), card("t2", "T2", "open", "", 1L)), "dev-1"));

            assertEquals(403, ex.getStatusCode().value());
            assertEquals(List.of("t1"), ex.getCardIds());
            verify(taskRepository, never()).saveAll(any());
            verify(webSocketHandler, never()).broadcastEvent(any(), any(), any());
        }

        @Test
        @DisplayName("quem não pode decidir e muda só o feedback: 403")
        void nonDeciderChangingFeedbackIsRejected() {
            existingSession();
            storedCards(card("t1", "T", "approved", "ok", 1L));
            lockOn(false);

            assertThrows(ShowcaseSessionService.DecisionRestrictedException.class,
                    () -> service.saveSession(incoming("active", card("t1", "T", "approved", "reescrevi", 1L)), "dev-1"));
        }

        @Test
        @DisplayName("quem pode decidir muda o veredito e o autor é o do token")
        void deciderChangesDecision() {
            existingSession();
            storedCards(card("t1", "T", "open", "", 1L));
            lockOn(true);
            when(userRepository.findById("po-1")).thenReturn(Optional.of(User.builder().id("po-1").name("Paula PO").build()));

            service.saveSession(incoming("active", card("t1", "T", "approved", "", 1L)), "po-1");

            ShowcaseTask out = savedCards().get(0);
            assertEquals("approved", out.getDecision());
            assertEquals("po-1", out.getDecidedBy());
            assertEquals("Paula PO", out.getDecidedByName());
            assertEquals(2L, out.getVersion());
            verify(accessPolicy).canDecide("SQ1", "criador", "po-1");
        }

        @Test
        @DisplayName("card NOVO já nascendo com decisão ou feedback por quem não pode: 403; sem decisão: aceito")
        void newCardWithDecisionByNonDecider() {
            existingSession();
            storedCards();
            lockOn(false);

            assertThrows(ShowcaseSessionService.DecisionRestrictedException.class,
                    () -> service.saveSession(incoming("active", card("novo", "N", "rejected", "", null)), "dev-1"));
            assertThrows(ShowcaseSessionService.DecisionRestrictedException.class,
                    () -> service.saveSession(incoming("active", card("novo", "N", "open", "comentário", null)), "dev-1"));
        }

        @Test
        @DisplayName("card novo sem decisão por quem não pode decidir: aceito, versão 1")
        void newCardWithoutDecisionAccepted() {
            existingSession();
            storedCards();
            when(accessPolicy.decisionLockEnforced()).thenReturn(true);

            service.saveSession(incoming("active", card("novo", "N", "open", "", null)), "dev-1");

            assertEquals(1L, savedCards().get(0).getVersion());
            verify(accessPolicy, never()).canDecide(any(), any(), any());
        }

        @Test
        @DisplayName("a squad da trava é a JÁ gravada: trocar squadName no mesmo envio não escolhe a regra")
        void lockUsesStoredSquad() {
            existingSession();
            storedCards(card("t1", "T", "open", "", 1L));
            lockOn(false);
            ShowcaseSession payload = incoming("active", card("t1", "T", "approved", "", 1L));
            payload.setSquadName("MINHA-SQUAD");

            assertThrows(ShowcaseSessionService.DecisionRestrictedException.class, () -> service.saveSession(payload, "dev-1"));
            verify(accessPolicy).canDecide(eq("SQ1"), eq("criador"), eq("dev-1"));
        }

        @Test
        @DisplayName("com a trava desligada (app.showcase.decision-lock=off) volta ao comportamento anterior")
        void lockOffAllowsEverything() {
            existingSession();
            storedCards(card("t1", "T", "open", "", 1L));
            when(accessPolicy.decisionLockEnforced()).thenReturn(false);
            when(userRepository.findById("dev-1")).thenReturn(Optional.empty());

            service.saveSession(incoming("active", card("t1", "T", "approved", "", 1L)), "dev-1");

            assertEquals("approved", savedCards().get(0).getDecision());
            verify(accessPolicy, never()).canDecide(any(), any(), any());
        }
    }

    // ---------- 2. versão por card / mescla ----------

    @Nested
    @DisplayName("Mescla por versão de card no POST antigo")
    class MergeOnPost {

        @Test
        @DisplayName("conteúdo igual ao gravado: a versão não sobe")
        void unchangedContentKeepsVersion() {
            existingSession();
            ShowcaseTask s = card("t1", "T", "open", "", 4L);
            s.setUpdatedAt(LocalDateTime.of(2026, 10, 2, 9, 0));
            storedCards(s);

            service.saveSession(incoming("active", card("t1", "T", "open", null, 4L)), "dev-1");

            ShowcaseTask out = savedCards().get(0);
            assertEquals(4L, out.getVersion());
            assertEquals(LocalDateTime.of(2026, 10, 2, 9, 0), out.getUpdatedAt());
        }

        @Test
        @DisplayName("cliente sem version (cliente que não conhece o campo): a última gravação vence, como antes")
        void clientWithoutVersionOverwrites() {
            existingSession();
            storedCards(card("t1", "Do servidor", "open", "", 7L));

            ShowcaseSession result = service.saveSession(incoming("active", card("t1", "Do cliente", "open", "", null)), "dev-1");

            ShowcaseTask out = savedCards().get(0);
            assertEquals("Do cliente", out.getTitle());
            assertEquals(8L, out.getVersion());
            assertNull(result.getStaleCardIds());
        }

        @Test
        @DisplayName("versão do cliente MENOR e conteúdo diferente: fica o card do servidor e o id vai em staleCardIds")
        void staleCardKeepsServerVersion() {
            existingSession();
            storedCards(card("t1", "Do servidor", "open", "", 5L), card("t2", "Outro", "open", "", 1L));

            ShowcaseSession result = service.saveSession(incoming("active",
                    card("t1", "Cópia velha do cliente", "open", "", 3L),
                    card("t2", "Outro editado", "open", "", 1L)), "dev-1");

            List<ShowcaseTask> out = savedCards();
            assertEquals("Do servidor", out.get(0).getTitle(), "a mudança do outro não é sobrescrita");
            assertEquals(5L, out.get(0).getVersion());
            assertEquals("Outro editado", out.get(1).getTitle());
            assertEquals(2L, out.get(1).getVersion());
            assertEquals(List.of("t1"), result.getStaleCardIds());
        }

        @Test
        @DisplayName("versão velha mas conteúdo igual ao do servidor: não é conflito")
        void staleVersionButSameContentIsNotAConflict() {
            existingSession();
            storedCards(card("t1", "Mesmo", "open", "", 5L));

            ShowcaseSession result = service.saveSession(incoming("active", card("t1", "Mesmo", "open", "", 3L)), "dev-1");

            assertEquals(5L, savedCards().get(0).getVersion());
            assertNull(result.getStaleCardIds());
        }

        @Test
        @DisplayName("card mantido do servidor não passa pela trava de decisão (a mudança nem será gravada)")
        void staleCardSkipsDecisionLock() {
            existingSession();
            storedCards(card("t1", "T", "open", "", 5L));
            when(accessPolicy.decisionLockEnforced()).thenReturn(true);

            ShowcaseSession result = service.saveSession(incoming("active", card("t1", "T", "approved", "", 2L)), "dev-1");

            assertEquals("open", savedCards().get(0).getDecision());
            assertEquals(List.of("t1"), result.getStaleCardIds());
            verify(accessPolicy, never()).canDecide(any(), any(), any());
        }

        @Test
        @DisplayName("o card mantido do servidor é regravado como cópia (com evidência e métricas), na ordem do corpo")
        void keptServerCardIsACompleteCopy() {
            existingSession();
            ShowcaseTask server = card("t1", "Servidor", "open", "", 5L);
            server.getMetrics().add(ShowcaseImpactMetric.builder().field("Economia").value(10.0).build());
            server.getEvidence().setTimeSpent(3600.0);
            storedCards(card("t0", "Zero", "open", "", 1L), server);

            service.saveSession(incoming("active", card("t1", "Velho", "open", "", 1L), card("t0", "Zero", "open", "", 1L)), "dev-1");

            List<ShowcaseTask> out = savedCards();
            assertEquals("t1", out.get(0).getId());
            assertEquals(0, out.get(0).getOrder());
            assertEquals(1, out.get(0).getMetrics().size());
            assertEquals(3600.0, out.get(0).getEvidence().getTimeSpent());
            assertNotSame(server, out.get(0), "regrava uma cópia: a entidade original foi removida pelo delete");
        }
    }

    // ---------- 3. participantes ----------

    @Nested
    @DisplayName("Participantes do resumo")
    class Participants {

        private void online(String... userIds) {
            int i = 0;
            for (String u : userIds) {
                presenceRegistry.join(PresenceModule.SHOWCASE, SESSION, u, "ws-" + i++, null);
            }
        }

        @SuppressWarnings("unchecked")
        private List<ShowcaseParticipant> savedParticipants() {
            ArgumentCaptor<List<ShowcaseParticipant>> cap = ArgumentCaptor.forClass(List.class);
            verify(participantRepository).saveAll(cap.capture());
            return cap.getValue();
        }

        @Test
        @DisplayName("ao finalizar grava os online na ordem de entrada, mais o apresentador e quem finalizou, com o nome")
        void finishingStoresOnlinePresenterAndCaller() {
            existingSession();
            storedCards();
            stored.setStatus("active");
            online("ana", "bia");
            presenterRegistry.publish(SESSION, "caio", "Caio", null, null);
            when(userRepository.findAllById(any())).thenReturn(List.of(
                    User.builder().id("ana").name("Ana Souza").build(),
                    User.builder().id("bia").name("Bia Lima").build(),
                    User.builder().id("caio").name("Caio Dias").build(),
                    User.builder().id("po-1").name("Paula PO").build()));
            when(participantRepository.saveAll(any())).thenAnswer(i -> {
                savedRows = new ArrayList<>(i.<List<ShowcaseParticipant>>getArgument(0));
                return savedRows;
            });
            when(participantRepository.findBySessionIdOrderBySortOrderAsc(SESSION)).thenAnswer(i -> savedRows);

            ShowcaseSession result = service.saveSession(incoming("finished"), "po-1");

            List<ShowcaseParticipant> rows = savedParticipants();
            assertEquals(List.of("ana", "bia", "caio", "po-1"), rows.stream().map(ShowcaseParticipant::getUserId).toList());
            assertEquals(List.of(0, 1, 2, 3), rows.stream().map(ShowcaseParticipant::getSortOrder).toList());
            assertEquals("Ana Souza", rows.get(0).getDisplayName());
            verify(participantRepository).deleteBySessionId(SESSION);
            assertEquals(4, result.getParticipants().size());
            assertEquals("Ana Souza", result.getParticipants().get(0).displayName());
            assertNull(result.getPresenter(), "finalizar limpa o apresentador");
            verify(webSocketHandler).broadcastToOptIn(eq(SESSION), eq("PRESENTER_UPDATED"), any());
        }

        private List<ShowcaseParticipant> savedRows = new ArrayList<>();

        @Test
        @DisplayName("sem presença no registro a lista fica vazia (não inventa nem o 'quem finalizou')")
        void emptyPresenceMeansEmptyList() {
            existingSession();
            storedCards();

            ShowcaseSession result = service.saveSession(incoming("finished"), "po-1");

            verify(participantRepository, never()).saveAll(any());
            assertTrue(result.getParticipants().isEmpty());
        }

        @Test
        @DisplayName("salvar de novo uma Review que já estava finalizada não recalcula a lista")
        void savingAnAlreadyFinishedReviewDoesNotRecompute() {
            stored.setStatus("finished");
            existingSession();
            storedCards();
            online("ana");

            service.saveSession(incoming("finished"), "po-1");

            verify(participantRepository, never()).deleteBySessionId(any());
            verify(participantRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("reabrir (finished -> outro status) zera a lista")
        void reopeningClearsTheList() {
            stored.setStatus("finished");
            existingSession();
            storedCards();

            ShowcaseSession result = service.saveSession(incoming("planning"), "po-1");

            verify(participantRepository).deleteBySessionId(SESSION);
            assertTrue(result.getParticipants().isEmpty());
        }

        @Test
        @DisplayName("Review que não está finalizada não consulta nem devolve participantes")
        void unfinishedReviewHasNoParticipants() {
            when(repository.findById(SESSION)).thenReturn(Optional.of(stored));
            storedCards();

            ShowcaseSession got = service.getSession(SESSION);

            assertTrue(got.getParticipants().isEmpty());
            verify(participantRepository, never()).findBySessionIdOrderBySortOrderAsc(any());
        }

        @Test
        @DisplayName("GET de Review finalizada devolve os participantes gravados, em ordem")
        void finishedReviewReturnsStoredParticipants() {
            stored.setStatus("finished");
            when(repository.findById(SESSION)).thenReturn(Optional.of(stored));
            storedCards();
            when(participantRepository.findBySessionIdOrderBySortOrderAsc(SESSION)).thenReturn(List.of(
                    ShowcaseParticipant.builder().userId("ana").displayName("Ana Souza").sortOrder(0).build(),
                    ShowcaseParticipant.builder().userId("bia").displayName("Bia Lima").sortOrder(1).build()));

            ShowcaseSession got = service.getSession(SESSION);

            assertEquals("ana", got.getParticipants().get(0).userId());
            assertEquals("Bia Lima", got.getParticipants().get(1).displayName());
        }
    }

    // ---------- 4. apresentador ----------

    @Nested
    @DisplayName("Apresentador")
    class Presenter {

        @BeforeEach
        void session() {
            lenient().when(repository.findById(SESSION)).thenReturn(Optional.of(stored));
        }

        private void cardExists() {
            when(taskRepository.findById("c1")).thenReturn(Optional.of(card("c1", "T", "open", "", 1L)));
        }

        @Test
        @DisplayName("o criador publica; o evento vai SÓ a quem tem opt-in e a sessão passa a trazer o presenter")
        void creatorPublishesAndOnlyOptInGetsTheEvent() {
            cardExists();
            when(userRepository.findById("criador")).thenReturn(Optional.of(User.builder().id("criador").name("Carla Criadora").build()));

            ShowcasePresenterDto p = service.publishPresenter(SESSION, "criador", "c1", "card");

            assertEquals("criador", p.userId());
            assertEquals("Carla Criadora", p.displayName());
            assertEquals("c1", p.cardId());
            assertEquals("card", p.step());
            assertNotNull(p.presentingAt());
            ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
            verify(webSocketHandler).broadcastToOptIn(eq(SESSION), eq("PRESENTER_UPDATED"), payload.capture());
            assertEquals(p, ((Map<?, ?>) payload.getValue()).get("presenter"));
            verify(webSocketHandler, never()).broadcastEvent(any(), any(), any());
            verify(webSocketHandler, never()).broadcastToLegacy(any(), any(), any());
            assertEquals(p, service.currentPresenter(SESSION).orElseThrow());
        }

        @Test
        @DisplayName("liderança da squad/ADMIN (pela política) publica")
        void leaderPublishes() {
            when(accessPolicy.isAdminOrSquadLeader("SQ1", "lider")).thenReturn(true);

            ShowcasePresenterDto p = service.publishPresenter(SESSION, "lider", null, null);

            assertEquals("lider", p.userId());
            assertNull(p.cardId());
            assertEquals("Participante", p.displayName(), "sem nome cadastrado não vaza id");
        }

        @Test
        @DisplayName("quem não é criador, apresentador atual, liderança nem ADMIN recebe 403 e nada é publicado")
        void strangerIsForbidden() {
            when(accessPolicy.isAdminOrSquadLeader("SQ1", "dev-1")).thenReturn(false);

            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.publishPresenter(SESSION, "dev-1", null, "card"));

            assertEquals(403, ex.getStatusCode().value());
            assertTrue(presenterRegistry.current(SESSION).isEmpty());
            verify(webSocketHandler, never()).broadcastToOptIn(any(), any(), any());
        }

        @Test
        @DisplayName("o apresentador atual segue controlando mesmo sem ser liderança")
        void currentPresenterKeepsControl() {
            presenterRegistry.publish(SESSION, "dev-1", "Dev", "c1", "card");
            cardExists();

            ShowcasePresenterDto p = service.publishPresenter(SESSION, "dev-1", "c1", "summary");

            assertEquals("summary", p.step());
        }

        @Test
        @DisplayName("Review finalizada: 409; card de outra Review: 404; step inválido: 400")
        void validations() {
            ResponseStatusException badStep = assertThrows(ResponseStatusException.class, () -> service.publishPresenter(SESSION, "criador", null, "tem espaço"));
            assertEquals(400, badStep.getStatusCode().value());
            ResponseStatusException longStep = assertThrows(ResponseStatusException.class, () -> service.publishPresenter(SESSION, "criador", null, "a".repeat(41)));
            assertEquals(400, longStep.getStatusCode().value());

            ShowcaseTask other = card("x9", "T", "open", "", 1L);
            other.setSessionId("outra");
            when(taskRepository.findById("x9")).thenReturn(Optional.of(other));
            assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.publishPresenter(SESSION, "criador", "x9", null)).getStatusCode().value());
            when(taskRepository.findById("nao-existe")).thenReturn(Optional.empty());
            assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.publishPresenter(SESSION, "criador", "nao-existe", null)).getStatusCode().value());

            stored.setStatus("finished");
            assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.publishPresenter(SESSION, "criador", null, null)).getStatusCode().value());
        }

        @Test
        @DisplayName("encerrar limpa e avisa só o opt-in; sem apresentador ativo é idempotente e não avisa")
        void clearIsIdempotent() {
            presenterRegistry.publish(SESSION, "criador", "Carla", null, null);

            service.clearPresenter(SESSION, "criador");
            service.clearPresenter(SESSION, "criador");

            assertTrue(service.currentPresenter(SESSION).isEmpty());
            ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
            verify(webSocketHandler, times(1)).broadcastToOptIn(eq(SESSION), eq("PRESENTER_UPDATED"), payload.capture());
            assertNull(((Map<?, ?>) payload.getValue()).get("presenter"));
        }

        @Test
        @DisplayName("quem não controla a apresentação não consegue encerrá-la")
        void strangerCannotClear() {
            presenterRegistry.publish(SESSION, "criador", "Carla", null, null);

            assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.clearPresenter(SESSION, "dev-1")).getStatusCode().value());
            assertTrue(service.currentPresenter(SESSION).isPresent());
        }

        @Test
        @DisplayName("Review inexistente: 404")
        void unknownSession() {
            when(repository.findById("nada")).thenReturn(Optional.empty());

            assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.currentPresenter("nada")).getStatusCode().value());
        }
    }

    // ---------- 5. PATCH por card ----------

    @Nested
    @DisplayName("PATCH por card")
    class PatchCard {

        private ShowcaseTask current;

        @BeforeEach
        void session() {
            current = card("c1", "Título", "open", "", 3L);
            current.getEvidence().setDev("Dev A");
            current.getMetrics().add(ShowcaseImpactMetric.builder().field("Economia").value(1.0).build());
            lenient().when(repository.findByIdForUpdate(SESSION)).thenReturn(Optional.of(stored));
            lenient().when(taskRepository.findById("c1")).thenReturn(Optional.of(current));
        }

        private com.fasterxml.jackson.databind.JsonNode body(String json) throws Exception {
            return JSON.readTree(json);
        }

        @Test
        @DisplayName("grava só os campos enviados, sobe a versão e avisa: opt-in recebe CARD_UPDATED; antigos, a sessão inteira")
        void patchesOnlySentFieldsAndNotifies() throws Exception {
            when(webSocketHandler.hasLegacyConnections(SESSION)).thenReturn(true);
            storedCards(current);

            ShowcaseTask out = service.patchCard(SESSION, "c1", body("{\"version\":3,\"title\":\"Novo\"}"), "dev-1");

            assertEquals("Novo", out.getTitle());
            assertEquals(4L, out.getVersion());
            assertNotNull(out.getUpdatedAt());
            assertEquals("S", out.getEvidence().getSolution(), "o que não foi enviado não muda");
            assertEquals("Dev A", out.getEvidence().getDev());
            assertEquals(1, out.getMetrics().size());
            verify(taskRepository).save(current);
            assertNotNull(stored.getUpdatedAt(), "editar um card renova a atividade da sessão");

            ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
            verify(webSocketHandler).broadcastToOptIn(eq(SESSION), eq("CARD_UPDATED"), payload.capture());
            Map<?, ?> p = (Map<?, ?>) payload.getValue();
            assertEquals("c1", p.get("cardId"));
            assertEquals(4L, p.get("version"));
            verify(webSocketHandler).broadcastToLegacy(eq(SESSION), eq("SESSION_UPDATED"), any(ShowcaseSession.class));
            verify(webSocketHandler, never()).broadcastEvent(any(), any(), any());
        }

        @Test
        @DisplayName("sem conexões antigas na sala não monta a sessão inteira à toa")
        void noLegacyConnectionsNoSessionPayload() throws Exception {
            when(webSocketHandler.hasLegacyConnections(SESSION)).thenReturn(false);

            service.patchCard(SESSION, "c1", body("{\"version\":3,\"title\":\"Novo\"}"), "dev-1");

            verify(webSocketHandler, never()).broadcastToLegacy(any(), any(), any());
            verify(taskRepository, never()).findBySessionIdOrderByOrderAsc(any());
        }

        @Test
        @DisplayName("evidence mescla campo a campo; metrics substitui por inteiro")
        void evidenceMergesAndMetricsReplace() throws Exception {
            ShowcaseTask out = service.patchCard(SESSION, "c1", body(
                    "{\"version\":3,\"evidence\":{\"solution\":\"Nova solução\"},\"metrics\":[{\"field\":\"Horas\",\"value\":5}]}"), "dev-1");

            assertEquals("Nova solução", out.getEvidence().getSolution());
            assertEquals("P", out.getEvidence().getProblem());
            assertEquals("Dev A", out.getEvidence().getDev());
            assertEquals(1, out.getMetrics().size());
            assertEquals("Horas", out.getMetrics().get(0).getField());
            assertEquals(5.0, out.getMetrics().get(0).getValue());
        }

        @Test
        @DisplayName("version velha: 409 com o card atual e nada é gravado")
        void staleVersionConflicts() throws Exception {
            ShowcaseSessionService.CardVersionConflictException ex = assertThrows(ShowcaseSessionService.CardVersionConflictException.class,
                    () -> service.patchCard(SESSION, "c1", body("{\"version\":2,\"title\":\"Tarde demais\"}"), "dev-1"));

            assertEquals(409, ex.getStatusCode().value());
            assertEquals(3L, ex.getCurrentCard().getVersion());
            assertEquals("Título", ex.getCurrentCard().getTitle());
            verify(taskRepository, never()).save(any());
            verify(webSocketHandler, never()).broadcastToOptIn(any(), any(), any());
        }

        @Test
        @DisplayName("sem version ou version não inteira: 400")
        void versionIsRequired() {
            for (String json : new String[]{"{\"title\":\"x\"}", "{\"version\":\"3\",\"title\":\"x\"}", "{\"version\":null}", "{\"version\":3.5}"}) {
                ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.patchCard(SESSION, "c1", body(json), "dev-1"), json);
                assertEquals(400, ex.getStatusCode().value(), json);
            }
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.patchCard(SESSION, "c1", body("[1]"), "dev-1")).getStatusCode().value());
        }

        @Test
        @DisplayName("card de outra Review ou inexistente: 404; Review inexistente: 404")
        void notFound() throws Exception {
            ShowcaseTask foreign = card("c9", "T", "open", "", 1L);
            foreign.setSessionId("outra");
            when(taskRepository.findById("c9")).thenReturn(Optional.of(foreign));
            when(taskRepository.findById("c0")).thenReturn(Optional.empty());
            when(repository.findByIdForUpdate("sem-sessao")).thenReturn(Optional.empty());

            assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.patchCard(SESSION, "c9", body("{\"version\":1}"), "dev-1")).getStatusCode().value());
            assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.patchCard(SESSION, "c0", body("{\"version\":1}"), "dev-1")).getStatusCode().value());
            assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.patchCard("sem-sessao", "c1", body("{\"version\":1}"), "dev-1")).getStatusCode().value());
        }

        @Test
        @DisplayName("PATCH que não muda nada não sobe a versão nem avisa")
        void noopPatch() throws Exception {
            ShowcaseTask out = service.patchCard(SESSION, "c1", body("{\"version\":3,\"title\":\"Título\",\"feedback\":\"\"}"), "dev-1");

            assertEquals(3L, out.getVersion());
            verify(taskRepository, never()).save(any());
            verify(webSocketHandler, never()).broadcastToOptIn(any(), any(), any());
        }

        @Test
        @DisplayName("campos do servidor e desconhecidos são ignorados (autoria da decisão, id, sessão, anexos)")
        void serverOwnedFieldsAreIgnored() throws Exception {
            ShowcaseTask out = service.patchCard(SESSION, "c1", body(
                    "{\"version\":3,\"title\":\"Novo\",\"id\":\"sequestro\",\"sessionId\":\"outra\",\"decidedBy\":\"forjado\","
                            + "\"approvedAt\":\"2020\",\"order\":99,\"attachments\":[],\"inventado\":1}"), "dev-1");

            assertEquals("c1", out.getId());
            assertEquals(SESSION, out.getSessionId());
            assertNull(out.getDecidedBy());
            assertNull(out.getApprovedAt());
            assertEquals("Novo", out.getTitle());
        }

        @Test
        @DisplayName("valor de tipo errado: 400, nada é gravado")
        void invalidValueIs400() throws Exception {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.patchCard(SESSION, "c1", body("{\"version\":3,\"points\":\"muitos\"}"), "dev-1"));

            assertEquals(400, ex.getStatusCode().value());
            verify(taskRepository, never()).save(any());
            assertEquals(3L, current.getVersion());
        }

        @Test
        @DisplayName("decision/feedback por quem não pode: 403 e o card gerenciado não é alterado")
        void decisionRestricted() throws Exception {
            lockOn(false);

            ShowcaseSessionService.DecisionRestrictedException ex = assertThrows(ShowcaseSessionService.DecisionRestrictedException.class,
                    () -> service.patchCard(SESSION, "c1", body("{\"version\":3,\"title\":\"Novo\",\"decision\":\"approved\"}"), "dev-1"));

            assertEquals(List.of("c1"), ex.getCardIds());
            assertEquals("Título", current.getTitle(), "o título também não foi aplicado (rejeita tudo)");
            assertEquals("open", current.getDecision());
            verify(taskRepository, never()).save(any());
        }

        @Test
        @DisplayName("quem pode decidir grava o veredito, com a autoria do token")
        void deciderPatchesDecision() throws Exception {
            lockOn(true);
            when(userRepository.findById("po-1")).thenReturn(Optional.of(User.builder().id("po-1").name("Paula PO").build()));

            ShowcaseTask out = service.patchCard(SESSION, "c1", body("{\"version\":3,\"decision\":\"approved\",\"feedback\":\"Aprovado\"}"), "po-1");

            assertEquals("approved", out.getDecision());
            assertEquals("po-1", out.getDecidedBy());
            assertEquals("Paula PO", out.getDecidedByName());
            assertNotNull(out.getApprovedAt());
            assertEquals(4L, out.getVersion());
        }

        @Test
        @DisplayName("não-PO mexendo só em conteúdo de preparação não consulta a política")
        void nonDeciderEditingContentIsFine() throws Exception {
            service.patchCard(SESSION, "c1", body("{\"version\":3,\"preparationStatus\":\"done\",\"evidence\":{\"video\":\"https://v.example/x\"}}"), "dev-1");

            verify(accessPolicy, never()).canDecide(any(), any(), any());
        }

        @Test
        @DisplayName("links com esquema executável são descartados como no POST")
        void linksAreSanitized() throws Exception {
            ShowcaseTask out = service.patchCard(SESSION, "c1", body("{\"version\":3,\"url\":\"javascript:alert(1)\",\"evidence\":{\"video\":\"https://ok.example/v\"}}"), "dev-1");

            assertNull(out.getUrl());
            assertEquals("https://ok.example/v", out.getEvidence().getVideo());
        }

        @Test
        @DisplayName("a resposta traz os anexos do card")
        void responseCarriesAttachments() throws Exception {
            ShowcaseTaskFile file = new ShowcaseTaskFile();
            file.setTaskId("c1");
            ShowcaseTaskFile other = new ShowcaseTaskFile();
            other.setTaskId("c2");
            when(fileService.listBySession(SESSION)).thenReturn(List.of(file, other));

            ShowcaseTask out = service.patchCard(SESSION, "c1", body("{\"version\":3,\"title\":\"Novo\"}"), "dev-1");

            assertEquals(1, out.getAttachments().size());
        }
    }
}
