package com.agilespace.backend.service;

import com.agilespace.backend.domain.ShowcaseMember;
import com.agilespace.backend.domain.ShowcaseSession;
import com.agilespace.backend.domain.ShowcaseTask;
import com.agilespace.backend.domain.ShowcaseTaskFile;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.ShowcaseMemberRepository;
import com.agilespace.backend.repository.ShowcaseSessionRepository;
import com.agilespace.backend.repository.ShowcaseTaskRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.websocket.ShowcaseWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ShowcaseSessionService {

    // Mesma classe de proteção que SprintPlanningService aplica a tasks/members.
    private static final int MAX_TASKS = 2000;
    private static final int MAX_MEMBERS = 200;
    private static final int MAX_LIST_LIMIT = 100;
    private static final int MAX_CHECKLIST_ITEMS = 20;

    @Autowired
    private ShowcaseSessionRepository repository;

    @Autowired
    private ShowcaseTaskRepository taskRepository;

    @Autowired
    private ShowcaseMemberRepository memberRepository;

    @Autowired
    private ShowcaseWebSocketHandler webSocketHandler;

    @Autowired
    private ShowcaseTaskFileService fileService;

    @Autowired(required = false)
    private UserRepository userRepository;

    @Transactional(readOnly = true)
    public ShowcaseSession getSession(String id) {
        return repository.findById(id).map(this::attachChildren).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<ShowcaseSession> getLatestSessions(int limit, String squadId) {
        int safeLimit = limit > 0 ? Math.min(limit, MAX_LIST_LIMIT) : 50;
        Pageable pageable = PageRequest.of(0, safeLimit);
        return repository.findLatestSessionsBySquad(squadId, pageable).stream().map(this::attachChildren).toList();
    }

    @Transactional
    public ShowcaseSession saveSession(ShowcaseSession session, String callerId) {
        ValidationSupport.requireNonBlank(session.getName(), "name");
        ValidationSupport.requireMaxSize(session.getTasks(), MAX_TASKS, "tasks");
        ValidationSupport.requireMaxSize(session.getMembers(), MAX_MEMBERS, "members");
        ValidationSupport.requireMaxSize(session.getReadinessChecklist(), MAX_CHECKLIST_ITEMS, "readinessChecklist");
        // squadName é texto livre no modal de criação — normaliza espaço sobrando pra não
        // quebrar o match de findLatestSessionsBySquad (comparação exata, case-insensitive).
        if (session.getSquadName() != null) {
            session.setSquadName(session.getSquadName().trim());
        }
        session.setCoverImage(UrlSafety.sanitize(session.getCoverImage()));

        boolean existed = false;
        if (session.getId() != null && !session.getId().isEmpty()) {
            // FOR UPDATE: serializa saves simultâneos da mesma Review (apagar e regravar as filhas em
            // paralelo estourava chave duplicada e um dos dois saves se perdia).
            var existing = repository.findByIdForUpdate(session.getId());
            if (existing.isPresent()) {
                existed = true;
                session.setCreatedBy(existing.get().getCreatedBy() != null ? existing.get().getCreatedBy() : callerId);
                session.setCreatedAt(existing.get().getCreatedAt());
            }
        } else {
            session.setId(UUID.randomUUID().toString());
        }
        // Quem cria é quem está logado: o criador vindo do corpo podia ser de outra pessoa.
        if (!existed && callerId != null && !callerId.isBlank()) {
            session.setCreatedBy(callerId);
        } else if (session.getCreatedBy() == null || session.getCreatedBy().isEmpty()) {
            session.setCreatedBy(callerId);
        }
        if (session.getCreatedAt() == null) {
            session.setCreatedAt(LocalDateTime.now());
        }

        List<ShowcaseTask> tasks = session.getTasks();
        List<ShowcaseMember> members = session.getMembers();
        session.setTasks(null); // campos @Transient, não fazem parte do INSERT/UPDATE da própria sessão
        session.setMembers(null);

        ShowcaseSession saved = repository.save(session);
        replaceChildren(saved.getId(), tasks, members, callerId);
        ShowcaseSession result = attachChildren(saved);

        // Só depois do commit: se o save falhar, ninguém vê na sala um estado que não foi gravado.
        runAfterCommit(() -> webSocketHandler.broadcastEvent(result.getId(), "SESSION_UPDATED", result));

        return result;
    }

    private static void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    // --- Montagem/gravação das tabelas filhas (sem relação JPA, mesmo padrão do SprintPlanningService) ---

    private ShowcaseSession attachChildren(ShowcaseSession session) {
        List<ShowcaseTask> tasks = taskRepository.findBySessionIdOrderByOrderAsc(session.getId());
        Map<String, List<ShowcaseTaskFile>> filesByTask = fileService.listBySession(session.getId()).stream()
                .collect(Collectors.groupingBy(ShowcaseTaskFile::getTaskId));
        tasks.forEach(t -> t.setAttachments(filesByTask.getOrDefault(t.getId(), List.of())));
        session.setTasks(tasks);
        session.setMembers(memberRepository.findBySessionIdOrderByOrderAsc(session.getId()));
        return session;
    }

    /** Decisão já gravada de um card: serve para não aceitar do cliente quem decidiu nem quando. */
    private record StoredDecision(String decision, String decidedBy, String decidedByName, String decidedAt, String approvedAt) {
    }

    /** Os ids de card/integrante vêm do cliente e são chave primária global: um id de outra Review seria sequestrado pelo merge. */
    private void rejectForeignIds(String sessionId, List<ShowcaseTask> tasks, List<ShowcaseMember> members) {
        List<String> taskIds = tasks.stream().map(ShowcaseTask::getId).filter(i -> i != null && !i.isBlank()).toList();
        if (!taskIds.isEmpty()) {
            for (ShowcaseTask other : taskRepository.findAllById(taskIds)) {
                if (!sessionId.equals(other.getSessionId())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Card pertence a outra Review.");
                }
            }
        }
        List<String> memberIds = members.stream().map(ShowcaseMember::getId).filter(i -> i != null && !i.isBlank()).toList();
        if (!memberIds.isEmpty()) {
            for (ShowcaseMember other : memberRepository.findAllById(memberIds)) {
                if (!sessionId.equals(other.getSessionId())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Integrante pertence a outra Review.");
                }
            }
        }
    }

    private String callerName(String callerId) {
        if (userRepository == null || callerId == null) {
            return null;
        }
        return userRepository.findById(callerId).map(User::getName).orElse(null);
    }

    private void applyDecisionAuthorship(ShowcaseTask task, StoredDecision previous, String callerId) {
        String decision = task.getDecision();
        boolean decided = decision != null && !decision.isBlank() && !"open".equals(decision);
        if (!decided) {
            task.setDecidedBy(null);
            task.setDecidedByName(null);
            task.setDecidedAt(null);
            task.setApprovedAt(null);
            return;
        }
        if (previous != null && decision.equals(previous.decision())) {
            task.setDecidedBy(previous.decidedBy());
            task.setDecidedByName(previous.decidedByName());
            task.setDecidedAt(previous.decidedAt());
            task.setApprovedAt(previous.approvedAt());
            return;
        }
        // Decisão nova ou trocada: quem decidiu é quem está autenticado, não o que o cliente escreveu.
        String now = LocalDateTime.now().toString();
        task.setDecidedBy(callerId);
        String name = callerName(callerId);
        if (name != null) {
            task.setDecidedByName(name);
        }
        task.setDecidedAt(now);
        task.setApprovedAt("approved".equals(decision) ? now : null);
    }

    private void sanitizeLinks(ShowcaseTask task) {
        task.setUrl(UrlSafety.sanitize(task.getUrl()));
        if (task.getEvidence() != null) {
            task.getEvidence().setScreenshot(UrlSafety.sanitize(task.getEvidence().getScreenshot()));
            task.getEvidence().setVideo(UrlSafety.sanitize(task.getEvidence().getVideo()));
            task.getEvidence().setTechDocUrl(UrlSafety.sanitize(task.getEvidence().getTechDocUrl()));
            task.getEvidence().setTdnUrl(UrlSafety.sanitize(task.getEvidence().getTdnUrl()));
        }
    }

    private void replaceChildren(String sessionId, List<ShowcaseTask> tasks, List<ShowcaseMember> members, String callerId) {
        List<ShowcaseTask> safeTasks = tasks != null ? tasks : Collections.emptyList();
        List<ShowcaseMember> safeMembers = members != null ? members : Collections.emptyList();
        rejectForeignIds(sessionId, safeTasks, safeMembers);

        Map<String, StoredDecision> previousDecisions = new HashMap<>();
        for (ShowcaseTask existing : taskRepository.findBySessionIdOrderByOrderAsc(sessionId)) {
            previousDecisions.put(existing.getId(), new StoredDecision(existing.getDecision(), existing.getDecidedBy(),
                    existing.getDecidedByName(), existing.getDecidedAt(), existing.getApprovedAt()));
        }

        taskRepository.deleteBySessionId(sessionId);
        memberRepository.deleteBySessionId(sessionId);

        int taskOrder = 0;
        for (ShowcaseTask task : safeTasks) {
            if (task.getId() == null || task.getId().isBlank()) {
                task.setId(UUID.randomUUID().toString());
            }
            task.setSessionId(sessionId);
            task.setOrder(taskOrder++);
            sanitizeLinks(task);
            applyDecisionAuthorship(task, previousDecisions.get(task.getId()), callerId);
        }
        if (!safeTasks.isEmpty()) {
            taskRepository.saveAll(safeTasks);
        }
        // Card que saiu da Review leva os anexos junto.
        fileService.removeOrphans(sessionId, safeTasks.stream().map(ShowcaseTask::getId).collect(Collectors.toSet()));

        int memberOrder = 0;
        for (ShowcaseMember member : safeMembers) {
            if (member.getId() == null || member.getId().isBlank()) {
                member.setId(UUID.randomUUID().toString());
            }
            member.setSessionId(sessionId);
            member.setOrder(memberOrder++);
        }
        if (!safeMembers.isEmpty()) {
            memberRepository.saveAll(safeMembers);
        }
    }
}
