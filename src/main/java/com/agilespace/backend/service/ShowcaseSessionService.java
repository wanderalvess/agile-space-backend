package com.agilespace.backend.service;

import com.agilespace.backend.domain.ShowcaseImpactMetric;
import com.agilespace.backend.domain.ShowcaseMember;
import com.agilespace.backend.domain.ShowcaseParticipant;
import com.agilespace.backend.domain.ShowcaseSession;
import com.agilespace.backend.domain.ShowcaseTask;
import com.agilespace.backend.domain.ShowcaseTaskFile;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.ShowcaseParticipantDto;
import com.agilespace.backend.dto.ShowcasePresenterDto;
import com.agilespace.backend.repository.ShowcaseMemberRepository;
import com.agilespace.backend.repository.ShowcaseParticipantRepository;
import com.agilespace.backend.repository.ShowcaseSessionRepository;
import com.agilespace.backend.repository.ShowcaseTaskRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.websocket.PresenceModule;
import com.agilespace.backend.websocket.PresenceRegistry;
import com.agilespace.backend.websocket.ShowcaseWebSocketHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
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

    // Os quatro abaixo são opcionais para os testes que montam o serviço à mão: ausentes, a trava, os participantes e o
    // apresentador simplesmente não agem (no app estão sempre presentes).
    @Autowired(required = false)
    private ShowcaseAccessPolicy accessPolicy;

    @Autowired(required = false)
    private ShowcasePresenterRegistry presenterRegistry;

    @Autowired(required = false)
    private PresenceRegistry presenceRegistry;

    @Autowired(required = false)
    private ShowcaseParticipantRepository participantRepository;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    private volatile ObjectMapper lenientMapper;

    /** A pessoa tentou mudar decisão/feedback sem poder (403, code DECISION_RESTRICTED). */
    public static class DecisionRestrictedException extends ResponseStatusException {
        private final List<String> cardIds;

        public DecisionRestrictedException(List<String> cardIds) {
            super(HttpStatus.FORBIDDEN, "Só o PO, o SME, a liderança da squad ou um administrador pode registrar a decisão ou o feedback de um card.");
            this.cardIds = List.copyOf(cardIds);
        }

        public List<String> getCardIds() {
            return cardIds;
        }
    }

    /** O card mudou desde o que o cliente leu (409, code CARD_VERSION_CONFLICT); carrega o card atual. */
    public static class CardVersionConflictException extends ResponseStatusException {
        private final transient ShowcaseTask currentCard;

        public CardVersionConflictException(ShowcaseTask currentCard) {
            super(HttpStatus.CONFLICT, "O card foi alterado por outra pessoa. Reaplique a sua mudança sobre a versão atual.");
            this.currentCard = currentCard;
        }

        public ShowcaseTask getCurrentCard() {
            return currentCard;
        }
    }

    /** Campos do card que o PATCH aceita (conteúdo). O resto é ignorado em silêncio, como no POST. */
    private static final Set<String> PATCHABLE_FIELDS = Set.of("key", "title", "description", "acceptanceCriteria", "type", "status",
            "priority", "points", "cardKind", "chartType", "chartTitle", "chartDisplay", "assignee", "url", "decision",
            "preparationStatus", "feedback", "project", "versionSuporte", "versionMaster", "versionRelease", "versionDevelop");

    private static final Pattern STEP_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,40}$");

    @Transactional(readOnly = true)
    public boolean sessionExists(String id) {
        return id != null && repository.existsById(id);
    }

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
        String previousStatus = null;
        // A trava de decisão olha a squad e o criador JÁ gravados: quem troca a squad da Review no mesmo envio não escolhe a regra.
        String lockSquad = session.getSquadName();
        String lockCreator = callerId;
        if (session.getId() != null && !session.getId().isEmpty()) {
            // FOR UPDATE: serializa saves simultâneos da mesma Review (apagar e regravar as filhas em
            // paralelo estourava chave duplicada e um dos dois saves se perdia).
            var existing = repository.findByIdForUpdate(session.getId());
            if (existing.isPresent()) {
                existed = true;
                previousStatus = existing.get().getStatus();
                lockSquad = existing.get().getSquadName();
                lockCreator = existing.get().getCreatedBy();
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
        // Se a trava de decisão rejeitar aqui dentro, a exceção desfaz a transação inteira (nada é gravado, nem a sessão).
        List<String> staleCardIds = replaceChildren(saved.getId(), tasks, members, callerId, lockSquad, lockCreator);
        boolean nowFinished = isFinished(saved.getStatus());
        boolean wasFinished = isFinished(previousStatus);
        boolean presenterCleared = false;
        if (nowFinished && !wasFinished) {
            storeParticipants(saved.getId(), callerId);
            presenterCleared = presenterRegistry != null && presenterRegistry.clear(saved.getId());
        } else if (!nowFinished && wasFinished && participantRepository != null) {
            participantRepository.deleteBySessionId(saved.getId());
        }
        ShowcaseSession result = attachChildren(saved);
        result.setStaleCardIds(staleCardIds.isEmpty() ? null : staleCardIds);

        boolean presenterCleared0 = presenterCleared;
        // Só depois do commit: se o save falhar, ninguém vê na sala um estado que não foi gravado.
        runAfterCommit(() -> {
            webSocketHandler.broadcastEvent(result.getId(), "SESSION_UPDATED", result);
            if (presenterCleared0) {
                webSocketHandler.broadcastToOptIn(result.getId(), "PRESENTER_UPDATED", presenterPayload(null));
            }
        });

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
        session.setParticipants(participantsOf(session));
        session.setPresenter(presenterRegistry == null ? null : presenterRegistry.current(session.getId()).orElse(null));
        return session;
    }

    private List<ShowcaseParticipantDto> participantsOf(ShowcaseSession session) {
        if (participantRepository == null || !isFinished(session.getStatus())) {
            return new ArrayList<>();
        }
        return participantRepository.findBySessionIdOrderBySortOrderAsc(session.getId()).stream()
                .map(p -> new ShowcaseParticipantDto(p.getUserId(), p.getDisplayName()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static boolean isFinished(String status) {
        return status != null && "finished".equalsIgnoreCase(status.trim());
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

    private void applyDecisionAuthorship(ShowcaseTask task, ShowcaseTask previous, String callerId) {
        String decision = task.getDecision();
        boolean decided = decision != null && !decision.isBlank() && !"open".equals(decision);
        if (!decided) {
            task.setDecidedBy(null);
            task.setDecidedByName(null);
            task.setDecidedAt(null);
            task.setApprovedAt(null);
            return;
        }
        if (previous != null && decision.equals(previous.getDecision())) {
            task.setDecidedBy(previous.getDecidedBy());
            task.setDecidedByName(previous.getDecidedByName());
            task.setDecidedAt(previous.getDecidedAt());
            task.setApprovedAt(previous.getApprovedAt());
            return;
        }
        // Decisão nova ou trocada: quem decidiu é quem está autenticado, não o que o cliente escreveu.
        String now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString();
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

    private List<String> replaceChildren(String sessionId, List<ShowcaseTask> tasks, List<ShowcaseMember> members,
                                         String callerId, String lockSquad, String lockCreator) {
        List<ShowcaseTask> safeTasks = tasks != null ? tasks : Collections.emptyList();
        List<ShowcaseMember> safeMembers = members != null ? members : Collections.emptyList();
        rejectForeignIds(sessionId, safeTasks, safeMembers);

        // Cópias do que está gravado: o delete abaixo remove as entidades gerenciadas, e o card mantido do servidor (versão
        // velha do cliente) precisa ser regravado a partir de uma cópia.
        Map<String, ShowcaseTask> stored = new HashMap<>();
        for (ShowcaseTask existing : taskRepository.findBySessionIdOrderByOrderAsc(sessionId)) {
            stored.put(existing.getId(), existing.copy());
        }

        for (ShowcaseTask task : safeTasks) {
            sanitizeLinks(task);
        }
        Set<ShowcaseTask> stale = staleCards(safeTasks, stored);
        enforceDecisionLock(safeTasks, stored, stale, callerId, lockSquad, lockCreator);

        taskRepository.deleteBySessionId(sessionId);
        memberRepository.deleteBySessionId(sessionId);

        LocalDateTime now = LocalDateTime.now();
        List<String> staleIds = new ArrayList<>();
        List<ShowcaseTask> toSave = new ArrayList<>(safeTasks.size());
        int taskOrder = 0;
        for (ShowcaseTask task : safeTasks) {
            if (task.getId() == null || task.getId().isBlank()) {
                task.setId(UUID.randomUUID().toString());
            }
            ShowcaseTask previous = stored.get(task.getId());
            ShowcaseTask finalTask = task;
            if (stale.contains(task) && previous != null) {
                // O cliente leu uma versão mais velha e outra pessoa mudou o card desde então: fica o do servidor.
                finalTask = previous;
                staleIds.add(previous.getId());
            } else {
                applyDecisionAuthorship(task, previous, callerId);
                if (previous == null) {
                    task.setVersion(1L);
                    task.setUpdatedAt(now);
                } else if (task.sameContentAs(previous)) {
                    task.setVersion(previous.versionOrDefault());
                    task.setUpdatedAt(previous.getUpdatedAt());
                } else {
                    task.setVersion(previous.versionOrDefault() + 1);
                    task.setUpdatedAt(now);
                }
            }
            finalTask.setSessionId(sessionId);
            finalTask.setOrder(taskOrder++);
            toSave.add(finalTask);
        }
        if (!toSave.isEmpty()) {
            taskRepository.saveAll(toSave);
        }
        // Card que saiu da Review leva os anexos junto.
        fileService.removeOrphans(sessionId, toSave.stream().map(ShowcaseTask::getId).collect(Collectors.toSet()));

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
        return staleIds;
    }

    /** Cards em que o cliente mandou uma versão MENOR que a gravada e o conteúdo difere do gravado (por identidade do objeto). */
    private static Set<ShowcaseTask> staleCards(List<ShowcaseTask> incoming, Map<String, ShowcaseTask> stored) {
        Set<ShowcaseTask> stale = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (ShowcaseTask task : incoming) {
            ShowcaseTask previous = task.getId() == null ? null : stored.get(task.getId());
            if (previous != null && task.getVersion() != null && task.getVersion() < previous.versionOrDefault()
                    && !task.sameContentAs(previous)) {
                stale.add(task);
            }
        }
        return stale;
    }

    /** 403 se alguém sem permissão muda decisão/feedback de um card (o card mantido do servidor não conta: não será gravado). */
    private void enforceDecisionLock(List<ShowcaseTask> incoming, Map<String, ShowcaseTask> stored, Set<ShowcaseTask> stale,
                                     String callerId, String lockSquad, String lockCreator) {
        if (accessPolicy == null || !accessPolicy.decisionLockEnforced()) {
            return;
        }
        List<String> touched = new ArrayList<>();
        for (ShowcaseTask task : incoming) {
            if (stale.contains(task)) {
                continue;
            }
            ShowcaseTask previous = task.getId() == null ? null : stored.get(task.getId());
            boolean touchesDecision = previous == null
                    ? task.normalizedDecision() != null || (task.getFeedback() != null && !task.getFeedback().isBlank())
                    : !task.sameDecisionFieldsAs(previous);
            if (touchesDecision) {
                touched.add(task.getId() != null ? task.getId() : String.valueOf(task.getKey()));
            }
        }
        if (!touched.isEmpty() && !accessPolicy.canDecide(lockSquad, lockCreator, callerId)) {
            throw new DecisionRestrictedException(touched);
        }
    }

    // --- Edição parcial por card ---

    /**
     * Atualiza SÓ os campos enviados de um card, com controle otimista por card ({@code version} obrigatória no corpo). 409 se o
     * card mudou desde o que o cliente leu (a exceção leva o card atual); 403 se mexe em decisão/feedback sem poder.
     */
    @Transactional
    public ShowcaseTask patchCard(String sessionId, String cardId, JsonNode body, String callerId) {
        if (body == null || !body.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Envie um objeto JSON com os campos do card e a version.");
        }
        JsonNode versionNode = body.get("version");
        if (versionNode == null || !versionNode.isIntegralNumber()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "version é obrigatória (número inteiro): a versão do card que você leu.");
        }
        ShowcaseSession session = repository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Review não encontrada."));
        ShowcaseTask task = taskRepository.findById(cardId)
                .filter(t -> sessionId.equals(t.getSessionId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Card não encontrado nesta Review."));
        if (versionNode.asLong() != task.versionOrDefault()) {
            throw new CardVersionConflictException(withAttachments(task));
        }

        ShowcaseTask candidate = task.copy();
        applyPatch(body, candidate);
        sanitizeLinks(candidate);
        if (candidate.sameContentAs(task)) {
            return withAttachments(task);
        }
        if (!candidate.sameDecisionFieldsAs(task) && accessPolicy != null && accessPolicy.decisionLockEnforced()
                && !accessPolicy.canDecide(session.getSquadName(), session.getCreatedBy(), callerId)) {
            throw new DecisionRestrictedException(List.of(task.getId()));
        }
        ShowcaseTask before = task.copy();
        task.applyContentFrom(candidate);
        applyDecisionAuthorship(task, before, callerId);
        LocalDateTime now = LocalDateTime.now();
        task.setVersion(before.versionOrDefault() + 1);
        task.setUpdatedAt(now);
        // A Review "Ao vivo" é dada como abandonada pela data da última gravação: editar um card é atividade.
        session.setUpdatedAt(now);
        taskRepository.save(task);

        ShowcaseTask card = withAttachments(task);
        // O frontend antigo só entende SESSION_UPDATED com a sessão inteira; só monta isso se há quem receba.
        ShowcaseSession legacyPayload = webSocketHandler.hasLegacyConnections(sessionId) ? attachChildren(session) : null;
        runAfterCommit(() -> {
            Map<String, Object> payload = new HashMap<>();
            payload.put("cardId", card.getId());
            payload.put("version", card.getVersion());
            payload.put("card", card);
            payload.put("sessionUpdatedAt", session.getUpdatedAt());
            webSocketHandler.broadcastToOptIn(sessionId, "CARD_UPDATED", payload);
            if (legacyPayload != null) {
                webSocketHandler.broadcastToLegacy(sessionId, "SESSION_UPDATED", legacyPayload);
            }
        });
        return card;
    }

    private ShowcaseTask withAttachments(ShowcaseTask task) {
        ShowcaseTask view = task.copy();
        view.setAttachments(fileService.listBySession(task.getSessionId()).stream()
                .filter(f -> task.getId().equals(f.getTaskId())).toList());
        return view;
    }

    /** Aplica ao {@code candidate} só os campos permitidos do corpo; evidence mescla campo a campo, metrics substitui. */
    private void applyPatch(JsonNode body, ShowcaseTask candidate) {
        ObjectMapper mapper = lenient();
        try {
            ObjectNode simple = mapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = body.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> f = fields.next();
                if (PATCHABLE_FIELDS.contains(f.getKey())) {
                    simple.set(f.getKey(), f.getValue());
                }
            }
            if (simple.size() > 0) {
                mapper.readerForUpdating(candidate).readValue(simple);
            }
            JsonNode evidence = body.get("evidence");
            if (evidence != null && evidence.isObject()) {
                if (candidate.getEvidence() == null) {
                    candidate.setEvidence(com.agilespace.backend.domain.ShowcaseEvidence.builder().build());
                }
                mapper.readerForUpdating(candidate.getEvidence()).readValue(evidence);
            }
            if (body.has("metrics")) {
                JsonNode metrics = body.get("metrics");
                List<ShowcaseImpactMetric> list = metrics == null || metrics.isNull()
                        ? new ArrayList<>()
                        : mapper.convertValue(metrics, new TypeReference<List<ShowcaseImpactMetric>>() { });
                candidate.setMetrics(new ArrayList<>(list));
            }
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Algum campo do card tem um valor inválido.");
        }
    }

    private ObjectMapper lenient() {
        ObjectMapper m = lenientMapper;
        if (m == null) {
            m = (objectMapper != null ? objectMapper : new ObjectMapper()).copy()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            lenientMapper = m;
        }
        return m;
    }

    // --- Participantes do resumo ---

    /** Grava quem estava online na sala (mais o apresentador e quem finalizou). Registro de presença vazio = lista vazia. */
    private void storeParticipants(String sessionId, String callerId) {
        if (participantRepository == null) {
            return;
        }
        participantRepository.deleteBySessionId(sessionId);
        Set<String> ids = new LinkedHashSet<>();
        if (presenceRegistry != null) {
            ids.addAll(presenceRegistry.snapshot(PresenceModule.SHOWCASE, sessionId).online());
        }
        if (ids.isEmpty()) {
            return;
        }
        if (presenterRegistry != null) {
            presenterRegistry.current(sessionId).ifPresent(p -> ids.add(p.userId()));
        }
        if (callerId != null && !callerId.isBlank()) {
            ids.add(callerId);
        }
        Map<String, String> names = new HashMap<>();
        if (userRepository != null) {
            for (User u : userRepository.findAllById(ids)) {
                names.put(u.getId(), u.getName());
            }
        }
        List<ShowcaseParticipant> rows = new ArrayList<>();
        int position = 0;
        for (String id : ids) {
            String name = names.get(id);
            rows.add(ShowcaseParticipant.builder().id(UUID.randomUUID().toString()).sessionId(sessionId).userId(id)
                    .displayName(name != null && !name.isBlank() ? name : "Participante").sortOrder(position++).build());
        }
        participantRepository.saveAll(rows);
    }

    // --- Apresentador (efêmero, em memória) ---

    private static Map<String, Object> presenterPayload(ShowcasePresenterDto presenter) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("presenter", presenter);
        return payload;
    }

    private ShowcaseSession requireSession(String sessionId) {
        return repository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Review não encontrada."));
    }

    private void requireCanPresent(ShowcaseSession session, String callerId) {
        if (callerId == null || callerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Faça login para apresentar.");
        }
        boolean creator = callerId.equals(session.getCreatedBy());
        boolean current = presenterRegistry != null && presenterRegistry.current(session.getId())
                .map(p -> callerId.equals(p.userId())).orElse(false);
        boolean leader = accessPolicy != null && accessPolicy.isAdminOrSquadLeader(session.getSquadName(), callerId);
        if (!creator && !current && !leader) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Só quem criou a Review, quem está apresentando, a liderança da squad ou um administrador controla a apresentação.");
        }
    }

    /** Publica o card que o apresentador está mostrando; quem publica passa a ser o apresentador. */
    @Transactional(readOnly = true)
    public ShowcasePresenterDto publishPresenter(String sessionId, String callerId, String cardId, String step) {
        ShowcaseSession session = requireSession(sessionId);
        requireCanPresent(session, callerId);
        if (isFinished(session.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A Review já foi finalizada.");
        }
        String cleanStep = step == null || step.isBlank() ? null : step.trim();
        if (cleanStep != null && !STEP_PATTERN.matcher(cleanStep).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "step inválido: até 40 caracteres, só letras, números, \"_\" e \"-\".");
        }
        String cleanCard = cardId == null || cardId.isBlank() ? null : cardId.trim();
        if (cleanCard != null && taskRepository.findById(cleanCard).filter(t -> sessionId.equals(t.getSessionId())).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Card não encontrado nesta Review.");
        }
        if (presenterRegistry == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Apresentador indisponível.");
        }
        String name = callerName(callerId);
        ShowcasePresenterDto presenter = presenterRegistry.publish(sessionId, callerId,
                name != null && !name.isBlank() ? name : "Participante", cleanCard, cleanStep);
        webSocketHandler.broadcastToOptIn(sessionId, "PRESENTER_UPDATED", presenterPayload(presenter));
        return presenter;
    }

    /** Encerra a apresentação (idempotente). */
    @Transactional(readOnly = true)
    public void clearPresenter(String sessionId, String callerId) {
        ShowcaseSession session = requireSession(sessionId);
        requireCanPresent(session, callerId);
        if (presenterRegistry != null && presenterRegistry.clear(sessionId)) {
            webSocketHandler.broadcastToOptIn(sessionId, "PRESENTER_UPDATED", presenterPayload(null));
        }
    }

    @Transactional(readOnly = true)
    public java.util.Optional<ShowcasePresenterDto> currentPresenter(String sessionId) {
        requireSession(sessionId);
        return presenterRegistry == null ? java.util.Optional.empty() : presenterRegistry.current(sessionId);
    }
}
