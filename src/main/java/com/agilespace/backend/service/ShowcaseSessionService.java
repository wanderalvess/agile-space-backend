package com.agilespace.backend.service;

import com.agilespace.backend.domain.ShowcaseMember;
import com.agilespace.backend.domain.ShowcaseSession;
import com.agilespace.backend.domain.ShowcaseTask;
import com.agilespace.backend.repository.ShowcaseMemberRepository;
import com.agilespace.backend.repository.ShowcaseSessionRepository;
import com.agilespace.backend.repository.ShowcaseTaskRepository;
import com.agilespace.backend.websocket.ShowcaseWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class ShowcaseSessionService {

    // Mesma classe de proteção que SprintPlanningService aplica a tasks/members.
    private static final int MAX_TASKS = 2000;
    private static final int MAX_MEMBERS = 200;

    @Autowired
    private ShowcaseSessionRepository repository;

    @Autowired
    private ShowcaseTaskRepository taskRepository;

    @Autowired
    private ShowcaseMemberRepository memberRepository;

    @Autowired
    private ShowcaseWebSocketHandler webSocketHandler;

    @Transactional(readOnly = true)
    public ShowcaseSession getSession(String id) {
        return repository.findById(id).map(this::attachChildren).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<ShowcaseSession> getLatestSessions(int limit, String squadId) {
        int safeLimit = limit > 0 ? limit : 50;
        Pageable pageable = PageRequest.of(0, safeLimit);
        return repository.findLatestSessionsBySquad(squadId, pageable).stream().map(this::attachChildren).toList();
    }

    @Transactional
    public ShowcaseSession saveSession(ShowcaseSession session, String callerId) {
        ValidationSupport.requireNonBlank(session.getName(), "name");
        ValidationSupport.requireMaxSize(session.getTasks(), MAX_TASKS, "tasks");
        ValidationSupport.requireMaxSize(session.getMembers(), MAX_MEMBERS, "members");
        // squadName é texto livre no modal de criação — normaliza espaço sobrando pra não
        // quebrar o match de findLatestSessionsBySquad (comparação exata, case-insensitive).
        if (session.getSquadName() != null) {
            session.setSquadName(session.getSquadName().trim());
        }

        if (session.getId() != null && !session.getId().isEmpty()) {
            repository.findById(session.getId()).ifPresent(existing -> {
                session.setCreatedBy(existing.getCreatedBy() != null ? existing.getCreatedBy() : callerId);
                session.setCreatedAt(existing.getCreatedAt());
            });
        } else {
            session.setId(UUID.randomUUID().toString());
        }
        if (session.getCreatedBy() == null || session.getCreatedBy().isEmpty()) {
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
        replaceChildren(saved.getId(), tasks, members);
        ShowcaseSession result = attachChildren(saved);

        // Dispara evento em tempo real para os clientes conectados com o payload da sessão
        webSocketHandler.broadcastEvent(result.getId(), "SESSION_UPDATED", result);

        return result;
    }

    // --- Montagem/gravação das tabelas filhas (sem relação JPA, mesmo padrão do SprintPlanningService) ---

    private ShowcaseSession attachChildren(ShowcaseSession session) {
        session.setTasks(taskRepository.findBySessionIdOrderByOrderAsc(session.getId()));
        session.setMembers(memberRepository.findBySessionIdOrderByOrderAsc(session.getId()));
        return session;
    }

    private void replaceChildren(String sessionId, List<ShowcaseTask> tasks, List<ShowcaseMember> members) {
        taskRepository.deleteBySessionId(sessionId);
        memberRepository.deleteBySessionId(sessionId);

        List<ShowcaseTask> safeTasks = tasks != null ? tasks : Collections.emptyList();
        int taskOrder = 0;
        for (ShowcaseTask task : safeTasks) {
            if (task.getId() == null || task.getId().isBlank()) {
                task.setId(UUID.randomUUID().toString());
            }
            task.setSessionId(sessionId);
            task.setOrder(taskOrder++);
        }
        if (!safeTasks.isEmpty()) {
            taskRepository.saveAll(safeTasks);
        }

        List<ShowcaseMember> safeMembers = members != null ? members : Collections.emptyList();
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
