package com.agilespace.backend.service;

import com.agilespace.backend.domain.ActionPlan;
import com.agilespace.backend.domain.ActionPlanTask;
import com.agilespace.backend.repository.ActionPlanRepository;
import com.agilespace.backend.repository.ActionPlanTaskRepository;
import com.agilespace.backend.repository.ActionPlanTaskRepository.BoardTaskCount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActionPlanService - origem do plano, trava de duplicidade, contagens e prazo (V78)")
class ActionPlanOriginServiceTest {

    @Mock private ActionPlanRepository boardRepository;
    @Mock private ActionPlanTaskRepository taskRepository;
    @Mock private SquadAudienceService audienceService;
    private ActionPlanService service;

    @BeforeEach
    void setup() {
        service = new ActionPlanService(boardRepository, taskRepository);
        service.setAudienceService(audienceService);
    }

    private ActionPlan plan(String sourceType, String sourceId) {
        return ActionPlan.builder().title("Plano").team("SQA").squadId("SQA").creatorId("u1")
                .sourceType(sourceType).sourceId(sourceId).sourceTitle("Radar — Sprint 42").build();
    }

    private void squadSqaExists() {
        when(audienceService.squadNames(List.of("SQA"))).thenReturn(Map.of("SQA", "Squad QA"));
    }

    private static ResponseStatusException status(Runnable action) {
        return assertThrows(ResponseStatusException.class, action::run);
    }

    private static BoardTaskCount count(UUID boardId, long total, long done) {
        return new BoardTaskCount() {
            @Override public UUID getBoardId() { return boardId; }
            @Override public Long getTotal() { return total; }
            @Override public Long getDone() { return done; }
        };
    }

    // ---------- criação do plano ----------

    @Test
    @DisplayName("Criar com origem: grava tipo em maiúsculas, id e título, e confere duplicidade na squad")
    void createWithSource() {
        squadSqaExists();
        when(boardRepository.findBySourceInScope("SQA", "HEALTH_CHECK", "hc-1")).thenReturn(List.of());
        when(boardRepository.save(any(ActionPlan.class))).thenAnswer(i -> i.getArgument(0));

        ActionPlan saved = service.createBoard(plan("health_check", "  hc-1  "));

        assertEquals("HEALTH_CHECK", saved.getSourceType());
        assertEquals("hc-1", saved.getSourceId());
        assertEquals("Radar — Sprint 42", saved.getSourceTitle());
        verify(boardRepository).flush(); // o INSERT sai já, para o índice único barrar a corrida dentro do try do controller
    }

    @Test
    @DisplayName("Mesma origem na mesma squad: ActionPlanSourceConflictException (409) com o plano existente, sem gravar")
    void duplicateSourceConflicts() {
        squadSqaExists();
        ActionPlan existing = ActionPlan.builder().id(UUID.randomUUID()).title("Antigo").squadId("SQA").build();
        when(boardRepository.findBySourceInScope("SQA", "RETRO", "retro-9")).thenReturn(List.of(existing));

        ActionPlanSourceConflictException ex = assertThrows(ActionPlanSourceConflictException.class,
                () -> service.createBoard(plan("RETRO", "retro-9")));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertSame(existing, ex.getExisting());
        verify(boardRepository, never()).save(any());
    }

    @Test
    @DisplayName("Plano sem squad (team em texto livre) também tem trava, no escopo vazio")
    void duplicateSourceWithoutSquadUsesEmptyScope() {
        when(audienceService.squadNames(List.of("Squad Geral"))).thenReturn(Map.of());
        when(boardRepository.findBySourceInScope("", "HEALTH_CHECK", "hc-1")).thenReturn(List.of());
        when(boardRepository.save(any(ActionPlan.class))).thenAnswer(i -> i.getArgument(0));

        ActionPlan saved = service.createBoard(ActionPlan.builder().title("Plano").team("Squad Geral").creatorId("u1")
                .sourceType("HEALTH_CHECK").sourceId("hc-1").build());

        assertNull(saved.getSquadId());
        verify(boardRepository).findBySourceInScope("", "HEALTH_CHECK", "hc-1");
    }

    @Test
    @DisplayName("Sem origem (corpo antigo) vira MANUAL, não consulta duplicidade nem faz flush")
    void legacyBodyIsManual() {
        when(boardRepository.save(any(ActionPlan.class))).thenAnswer(i -> i.getArgument(0));

        ActionPlan saved = service.createBoard(ActionPlan.builder().title("Plano").team("Squad Geral").creatorId("u1").build());

        assertEquals("MANUAL", saved.getSourceType());
        assertNull(saved.getSourceId());
        assertNull(saved.getSourceTitle());
        verify(boardRepository, never()).findBySourceInScope(any(), any(), any());
        verify(boardRepository, never()).flush();
    }

    @Test
    @DisplayName("MANUAL explícito sem id funciona; título da origem é descartado")
    void explicitManual() {
        when(boardRepository.save(any(ActionPlan.class))).thenAnswer(i -> i.getArgument(0));

        ActionPlan saved = service.createBoard(ActionPlan.builder().title("Plano").creatorId("u1")
                .sourceType("manual").sourceTitle("ignorado").build());

        assertEquals("MANUAL", saved.getSourceType());
        assertNull(saved.getSourceTitle());
    }

    @Test
    @DisplayName("MANUAL com sourceId: 400")
    void manualWithSourceIdRejected() {
        ResponseStatusException ex = status(() -> service.createBoard(plan("MANUAL", "x")));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        status(() -> service.createBoard(plan(null, "x")));
        verifyNoInteractions(boardRepository);
    }

    @Test
    @DisplayName("Tipo de origem inválido: 400")
    void invalidSourceType() {
        ResponseStatusException ex = status(() -> service.createBoard(plan("JIRA", "x")));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verifyNoInteractions(boardRepository);
    }

    @Test
    @DisplayName("Origem diferente de MANUAL sem sourceId (ausente ou em branco): 400")
    void sourceIdRequired() {
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> service.createBoard(plan("RETRO", null))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> service.createBoard(plan("RETRO", "   "))).getStatusCode());
        verifyNoInteractions(boardRepository);
    }

    @Test
    @DisplayName("sourceId acima de 80 e sourceTitle acima de 255: 400")
    void sourceLimits() {
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> service.createBoard(plan("RETRO", "x".repeat(81)))).getStatusCode());
        ActionPlan longTitle = plan("RETRO", "ok");
        longTitle.setSourceTitle("t".repeat(256));
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> service.createBoard(longTitle)).getStatusCode());
    }

    @Test
    @DisplayName("tasksTotal/tasksDone enviados no corpo são descartados")
    void countsFromBodyDiscarded() {
        when(boardRepository.save(any(ActionPlan.class))).thenAnswer(i -> i.getArgument(0));
        ActionPlan body = ActionPlan.builder().title("Plano").creatorId("u1").tasksTotal(99).tasksDone(98).build();

        ActionPlan saved = service.createBoard(body);

        assertNull(saved.getTasksTotal());
        assertNull(saved.getTasksDone());
    }

    @Test
    @DisplayName("Plano antigo (source_type nulo no banco) é lido como MANUAL")
    void legacyRowReadsAsManual() {
        assertEquals("MANUAL", new ActionPlan().getSourceType());
    }

    @Test
    @DisplayName("findExistingBySource: sem tipo ou sem id nem consulta o banco")
    void findExistingNeedsSource() {
        assertTrue(service.findExistingBySource("SQA", null, "x").isEmpty());
        assertTrue(service.findExistingBySource("SQA", "RETRO", null).isEmpty());
        verifyNoInteractions(boardRepository);
    }

    @Test
    @DisplayName("resolveSquadId usa squadId ou team só se for squad cadastrada")
    void resolveSquad() {
        squadSqaExists();
        assertEquals("SQA", service.resolveSquadId(ActionPlan.builder().team("SQA").build()));
    }

    // ---------- listagens: contagens ----------

    @Test
    @DisplayName("Lista por squad traz tasksTotal/tasksDone numa consulta agregada só; plano sem ação = 0/0")
    void listCounts() {
        ActionPlan a = ActionPlan.builder().id(UUID.randomUUID()).title("A").build();
        ActionPlan b = ActionPlan.builder().id(UUID.randomUUID()).title("B").build();
        when(boardRepository.findBySquad(eq("SQA"), any(Pageable.class))).thenReturn(List.of(a, b));
        when(taskRepository.countByBoards(anyCollection())).thenReturn(List.of(count(a.getId(), 5, 2)));

        List<ActionPlan> out = service.listBoardsBySquad("SQA");

        assertEquals(5, out.get(0).getTasksTotal());
        assertEquals(2, out.get(0).getTasksDone());
        assertEquals(0, out.get(1).getTasksTotal());
        assertEquals(0, out.get(1).getTasksDone());
        verify(taskRepository, times(1)).countByBoards(anyCollection());
        verify(taskRepository, never()).findByBoardIdOrderByOrderAsc(any());
    }

    @Test
    @DisplayName("Lista vazia não consulta contagens")
    void emptyListSkipsCounts() {
        when(boardRepository.findBySquad(eq("SQA"), any(Pageable.class))).thenReturn(List.of());
        assertTrue(service.listBoardsBySquad("SQA").isEmpty());
        verifyNoInteractions(taskRepository);
    }

    @Test
    @DisplayName("Lista por sprint também traz as contagens")
    void sprintListCounts() {
        ActionPlan a = ActionPlan.builder().id(UUID.randomUUID()).title("A").build();
        when(boardRepository.findBySprintIdOrderByCreatedAtDesc("s1")).thenReturn(List.of(a));
        when(taskRepository.countByBoards(anyCollection())).thenReturn(List.of(count(a.getId(), 3, 3)));

        assertEquals(3, service.listBoardsBySprintId("s1").get(0).getTasksDone());
    }

    // ---------- filtro por origem ----------

    @Test
    @DisplayName("Filtro tipo+id na squad usa a consulta de origem; só tipo usa a de tipo; sem filtro, a normal")
    void sourceFilterRouting() {
        when(boardRepository.findBySquadAndSource(eq("SQA"), eq("HEALTH_CHECK"), eq("hc-1"), any(Pageable.class))).thenReturn(List.of());
        when(boardRepository.findBySquadAndSourceType(eq("SQA"), eq("RETRO"), any(Pageable.class))).thenReturn(List.of());
        when(boardRepository.findBySquad(eq("SQA"), any(Pageable.class))).thenReturn(List.of());

        service.listBoardsBySquad("SQA", "health_check", "hc-1");
        service.listBoardsBySquad("SQA", "RETRO", null);
        service.listBoardsBySquad("SQA", null, null);

        verify(boardRepository).findBySquadAndSource(eq("SQA"), eq("HEALTH_CHECK"), eq("hc-1"), any(Pageable.class));
        verify(boardRepository).findBySquadAndSourceType(eq("SQA"), eq("RETRO"), any(Pageable.class));
        verify(boardRepository).findBySquad(eq("SQA"), any(Pageable.class));
    }

    @Test
    @DisplayName("Filtro: sourceId sem tipo e tipo inválido são 400")
    void sourceFilterValidation() {
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> service.listBoardsBySquad("SQA", null, "x")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> service.listBoardsBySquad("SQA", "JIRA", null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> service.listBoardsBySource("RETRO", null)).getStatusCode());
    }

    @Test
    @DisplayName("Busca por origem sem squad devolve os planos da origem com contagens")
    void listBySource() {
        ActionPlan a = ActionPlan.builder().id(UUID.randomUUID()).title("A").sourceType("RETRO").sourceId("r1").build();
        when(boardRepository.findBySource(eq("RETRO"), eq("r1"), any(Pageable.class))).thenReturn(List.of(a));
        when(taskRepository.countByBoards(anyCollection())).thenReturn(List.of(count(a.getId(), 1, 0)));

        List<ActionPlan> out = service.listBoardsBySource("RETRO", "r1");

        assertEquals(1, out.size());
        assertEquals(1, out.get(0).getTasksTotal());
    }

    // ---------- ações: origem e prazo ----------

    @Test
    @DisplayName("Criar ação com sourceItemId, sourceLabel e dueDate")
    void createTaskWithOrigin() {
        UUID boardId = UUID.randomUUID();
        when(taskRepository.save(any(ActionPlanTask.class))).thenAnswer(i -> i.getArgument(0));
        when(taskRepository.findByBoardIdOrderByOrderAsc(boardId)).thenReturn(List.of());

        ActionPlanTask saved = service.createTask(boardId, ActionPlanTask.builder().what("Combinar canal")
                .sourceItemId(" comunicacao ").sourceLabel("Comunicação").dueDate(LocalDate.of(2026, 11, 5)).build(), "u1");

        assertEquals("comunicacao", saved.getSourceItemId());
        assertEquals("Comunicação", saved.getSourceLabel());
        assertEquals(LocalDate.of(2026, 11, 5), saved.getDueDate());
    }

    @Test
    @DisplayName("Criar ação no formato antigo: origem e prazo nulos, 'when' continua texto livre")
    void createTaskLegacyBody() {
        UUID boardId = UUID.randomUUID();
        when(taskRepository.save(any(ActionPlanTask.class))).thenAnswer(i -> i.getArgument(0));
        when(taskRepository.findByBoardIdOrderByOrderAsc(boardId)).thenReturn(List.of());

        ActionPlanTask saved = service.createTask(boardId, ActionPlanTask.builder().what("Algo").when("semana que vem").build(), "u1");

        assertNull(saved.getSourceItemId());
        assertNull(saved.getSourceLabel());
        assertNull(saved.getDueDate());
        assertEquals("semana que vem", saved.getWhen());
    }

    @Test
    @DisplayName("Limites: sourceItemId acima de 80 e sourceLabel acima de 255 são 400")
    void taskSourceLimits() {
        UUID boardId = UUID.randomUUID();
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> service.createTask(boardId,
                ActionPlanTask.builder().what("x").sourceItemId("i".repeat(81)).build(), "u1")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> service.createTask(boardId,
                ActionPlanTask.builder().what("x").sourceLabel("l".repeat(256)).build(), "u1")).getStatusCode());
    }

    @Test
    @DisplayName("Editar: dueDate muda o prazo, clearDueDate limpa, ausente não mexe, e a origem da ação é imutável")
    void updateTaskDueDate() {
        UUID taskId = UUID.randomUUID();
        ActionPlanTask existing = ActionPlanTask.builder().id(taskId).boardId(UUID.randomUUID()).what("A")
                .sourceItemId("i1").sourceLabel("L1").dueDate(LocalDate.of(2026, 10, 30)).build();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(existing));
        when(taskRepository.save(any(ActionPlanTask.class))).thenAnswer(i -> i.getArgument(0));

        service.updateTask(taskId, ActionPlanTask.builder().what("A").build(), "u1");
        assertEquals(LocalDate.of(2026, 10, 30), existing.getDueDate(), "campo ausente não muda o prazo");

        service.updateTask(taskId, ActionPlanTask.builder().dueDate(LocalDate.of(2026, 12, 1))
                .sourceItemId("outro").sourceLabel("Outro").build(), "u1");
        assertEquals(LocalDate.of(2026, 12, 1), existing.getDueDate());
        assertEquals("i1", existing.getSourceItemId(), "a origem da ação não muda na edição");
        assertEquals("L1", existing.getSourceLabel());

        service.updateTask(taskId, ActionPlanTask.builder().clearDueDate(true).build(), "u1");
        assertNull(existing.getDueDate());

        ArgumentCaptor<ActionPlanTask> saved = ArgumentCaptor.forClass(ActionPlanTask.class);
        verify(taskRepository, times(3)).save(saved.capture());
    }
}
