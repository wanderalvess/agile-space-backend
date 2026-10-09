package com.agilespace.backend.controller;

import com.agilespace.backend.domain.*;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.SquadService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/squads")
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class SquadController {

    private final SquadService squadService;
    private final com.agilespace.backend.service.SquadSyncService squadSyncService;
    private final com.agilespace.backend.service.SquadSyncGuard squadSyncGuard;
    private final com.agilespace.backend.service.SquadCapacityService squadCapacityService;
    private final UserRepository userRepository;
    private final com.agilespace.backend.service.UserProjectResolverService userProjectResolverService;
    private final com.agilespace.backend.service.SquadAccessService squadAccessService;
    private final com.agilespace.backend.service.SquadTeamService squadTeamService;

    /**
     * Leitura: qualquer membro real da squad (ou admin/liderança) — nunca auto-vincula.
     * Antes desta checagem, todo GET de /api/squads/** exigia só autenticação, sem checar
     * pertencimento — qualquer usuário autenticado da aplicação lia dado de qualquer squad.
     * Lógica em SquadAccessService (reaproveitada por poker/showcase/retro/health-check/
     * brainstorming, que tinham o mesmo buraco).
     */
    private void requireSquadReadAccess(String squadId, HttpServletRequest request) {
        squadAccessService.requireSquadReadAccess(squadId, request);
    }

    /**
     * Escrita: igual à leitura, mas com um fallback a mais — em ambientes limpos onde o
     * usuário ainda não possui squad/projeto configurado, auto-associa pra permitir o
     * primeiro fluxo de sincronização (efeito colateral que uma leitura nunca deve ter).
     */
    private void requireSquadWriteAccess(String squadId, HttpServletRequest request) {
        if (squadAccessService.matchesSquad(squadId, request)) {
            return;
        }
        if (bootstrapNewSquad(squadId, request)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a membros desta squad ou administradores.");
    }

    /**
     * Gestão: configuração da squad, roster, capacidade e dado nominal por pessoa. Só liderança da squad
     * (ou admin); ver SquadAccessService.canManageSquad.
     */
    private void requireSquadManageAccess(String squadId, HttpServletRequest request) {
        if (squadAccessService.canManageSquad(squadId, request)) {
            return;
        }
        if (bootstrapNewSquad(squadId, request)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Só a liderança da squad (Agile Master, People Lead, Tech Lead, PO…) ou um administrador pode fazer isso.");
    }

    /**
     * Primeiro uso em ambiente limpo: a squad ainda não existe (nem config, nem pessoas) e quem chama não tem
     * squad. Só nesse caso vincula quem chama à squad nova. Antes, qualquer usuário sem squad se vinculava a
     * QUALQUER squad já existente só chamando uma escrita, e passava a ler os dados dela.
     */
    private boolean bootstrapNewSquad(String squadId, HttpServletRequest request) {
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        User caller = userId != null ? userRepository.findById(userId).orElse(null) : null;
        if (caller == null) {
            return false;
        }
        boolean hasNoSquad = caller.getSquadId() == null || caller.getSquadId().isBlank() || "Sem Time".equalsIgnoreCase(caller.getSquadId().trim());
        if (!hasNoSquad) {
            return false;
        }
        boolean squadAlreadyExists = squadService.getSquad(squadId).isPresent() || !squadService.getMembers(squadId).isEmpty();
        if (squadAlreadyExists) {
            return false;
        }
        boolean hasNoProject = caller.getDefaultProjectId() == null || caller.getDefaultProjectId().isBlank() || "Sem Time".equalsIgnoreCase(caller.getDefaultProjectId().trim());
        caller.setSquadId(squadId);
        if (hasNoProject) {
            caller.setDefaultProjectId(squadId);
        }
        userRepository.save(caller);
        return true;
    }

    private User callerUser(HttpServletRequest request) {
        String id = callerId(request);
        return id != null ? userRepository.findById(id).orElse(null) : null;
    }

    private String callerId(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
    }

    private boolean isAdmin(HttpServletRequest request) {
        return "ADMIN".equalsIgnoreCase((String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE));
    }

    // ----- Squad Config -----
    /** Só as squads que quem chama pode ler (antes: a lista inteira, com JQL e dono do sync, para qualquer login). */
    @GetMapping
    public ResponseEntity<List<Squad>> getAllSquads(HttpServletRequest request) {
        return ResponseEntity.ok(squadService.getAllSquads().stream()
                .filter(sq -> squadAccessService.matchesSquad(sq.getId(), request))
                .toList());
    }

    // Motor de sync roda no backend (SquadSyncService) em vez de client-side —
    // ver plano de unificação Squad Pulse + jiradash, Fase 2. callerUserId vem
    // sempre do token validado, nunca do corpo da requisição, senão qualquer
    // chamador autenticado poderia sincronizar usando o PAT salvo de outra
    // pessoa só citando o userId dela.
    @PostMapping("/{squadId}/sync")
    public ResponseEntity<Void> syncSquad(
            @PathVariable String squadId,
            @RequestParam(required = false, defaultValue = "false") boolean forceFull,
            HttpServletRequest request) {
        requireSquadWriteAccess(squadId, request);
        // Sem isso, um tick do sync agendado (SquadSyncScheduler) podia coincidir com
        // alguém clicando "Sincronizar" ao mesmo tempo — duas syncs do mesmo squad
        // escrevendo por cima uma da outra. Risco que só existe desde que o sync
        // agendado existe (Fase 4); antes só um humano por vez clicava o botão.
        if (!squadSyncGuard.tryAcquire(squadId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma sincronização em andamento para esta squad.");
        }
        try {
            String callerUserId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
            squadSyncService.syncSquad(squadId, callerUserId, forceFull);
            return ResponseEntity.ok().build();
        } finally {
            squadSyncGuard.release(squadId);
        }
    }

    @PostMapping("/{squadId}/force-resync-sprint")
    public ResponseEntity<Void> forceResyncSprint(
            @PathVariable String squadId,
            @RequestParam String sprintId,
            HttpServletRequest request) {
        requireSquadWriteAccess(squadId, request);
        if (!squadSyncGuard.tryAcquire(squadId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma sincronização em andamento para esta squad.");
        }
        try {
            String callerUserId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
            squadSyncService.forceResyncSprint(squadId, callerUserId, sprintId);
            return ResponseEntity.ok().build();
        } finally {
            squadSyncGuard.release(squadId);
        }
    }

    @GetMapping("/{squadId}")
    public ResponseEntity<Squad> getSquad(@PathVariable String squadId, HttpServletRequest request) {
        requireSquadReadAccess(squadId, request);
        return squadService.getSquad(squadId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{squadId}")
    public ResponseEntity<Squad> saveSquad(@PathVariable String squadId, @RequestBody Squad squad, HttpServletRequest request) {
        if (!squadAccessService.canManageSquad(squadId, request)) {
            // Membro comum continua podendo salvar o que a tela do Painel já deixa qualquer membro mexer (cerimônias, unidade
            // de estimativa, número do quadro). Projeto, JQL, domínio do Jira, capacidade, fases e sync agendado são da liderança.
            requireSquadWriteAccess(squadId, request);
            if (touchesLeadershipOnlyFields(squad)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Só a liderança da squad (Agile Master, People Lead, Tech Lead, PO…) ou um administrador pode alterar a configuração do Jira, a capacidade e as fases.");
            }
            squad.setName(null);
        }
        // Campos que só o sync grava (estado da sincronização, sprint ativa, histórico) não vêm do cliente.
        squad.setActiveSprintId(null);
        squad.setSchemaVersion(null);
        squad.setLastSyncAt(null);
        squad.setLastFullReconcileAt(null);
        squad.setLastSyncStatus(null);
        squad.setLastSyncBy(null);
        squad.setLastSyncIssueCount(null);
        squad.setLastSyncError(null);
        squad.setSprintHistory(null);
        // O sync agendado usa o token do Jira do "dono". Ninguém indica outra pessoa como dono: só a si mesmo
        // (ou desliga com vazio); admin pode indicar qualquer um.
        String owner = squad.getSyncOwnerUserId();
        if (owner != null && !owner.isBlank() && !owner.trim().equals(callerId(request)) && !isAdmin(request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "O sincronismo agendado só pode usar o token do Jira de quem o ativa.");
        }
        requireSafeCeremonyLinks(squad);
        squad.setId(squadId);
        return ResponseEntity.ok(squadService.saveSquad(squad));
    }

    // ----- Metrics Rollup -----
    /** Sem sprintId: o rollup da sprint ativa (ou o mais recente). Com sprintId: o daquela sprint, ou 404. */
    @GetMapping("/{squadId}/rollup")
    public ResponseEntity<SquadMetricsRollup> getRollup(
            @PathVariable String squadId,
            @RequestParam(required = false) String sprintId,
            HttpServletRequest request) {
        requireSquadReadAccess(squadId, request);
        java.util.Optional<SquadMetricsRollup> rollup = (sprintId != null && !sprintId.isBlank())
                ? squadService.getRollup(squadId, sprintId)
                : squadService.getRollup(squadId);
        return rollup.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{squadId}/rollup")
    public ResponseEntity<SquadMetricsRollup> saveRollup(@PathVariable String squadId, @RequestBody SquadMetricsRollup rollup, HttpServletRequest request) {
        requireSquadManageAccess(squadId, request);
        if (rollup.getSprintId() == null || rollup.getSprintId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe a sprint do rollup.");
        }
        rollup.setSquadId(squadId);
        return ResponseEntity.ok(squadService.saveRollup(rollup));
    }

    // ----- Issue Snapshots -----
    @GetMapping("/{squadId}/issues")
    public ResponseEntity<List<SquadIssueSnapshot>> getIssues(
            @PathVariable String squadId,
            @RequestParam(required = false) String sprintId,
            HttpServletRequest request) {
        requireSquadReadAccess(squadId, request);
        return ResponseEntity.ok(squadService.getIssues(squadId, sprintId));
    }

    @GetMapping("/{squadId}/issues/by-assignee")
    public ResponseEntity<List<SquadIssueSnapshot>> getIssuesByAssignee(
            @PathVariable String squadId,
            @RequestParam String assigneeId,
            HttpServletRequest request) {
        requireSquadReadAccess(squadId, request);
        return ResponseEntity.ok(squadService.getIssuesByAssignee(squadId, assigneeId));
    }

    @GetMapping("/{squadId}/issues/{jiraKey}")
    public ResponseEntity<SquadIssueSnapshot> getIssueByKey(
            @PathVariable String squadId,
            @PathVariable String jiraKey,
            HttpServletRequest request) {
        requireSquadReadAccess(squadId, request);
        return squadService.getIssueByKey(squadId, jiraKey)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{squadId}/issues/batch")
    public ResponseEntity<List<SquadIssueSnapshot>> batchUpsertIssues(
            @PathVariable String squadId,
            @RequestBody List<SquadIssueSnapshot> snapshots,
            HttpServletRequest request) {
        requireSquadManageAccess(squadId, request);
        return ResponseEntity.ok(squadService.batchUpsertIssues(squadId, snapshots));
    }

    @DeleteMapping("/{squadId}/issues/batch")
    public ResponseEntity<Void> batchDeleteIssues(
            @PathVariable String squadId,
            @RequestBody List<String> keys,
            HttpServletRequest request) {
        requireSquadManageAccess(squadId, request);
        squadService.batchDeleteIssues(squadId, keys);
        return ResponseEntity.noContent().build();
    }

    // ----- Members -----
    /** Squads da própria pessoa. Consultar o vínculo de outra pessoa (por e-mail, id…) é só de admin. */
    @GetMapping("/by-user")
    public ResponseEntity<List<SquadMember>> getSquadMembersForUser(@RequestParam String identifier, HttpServletRequest request) {
        if (!isAdmin(request) && !"LEAD".equalsIgnoreCase((String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE))) {
            String userId = callerId(request);
            User caller = userId != null ? userRepository.findById(userId).orElse(null) : null;
            String wanted = identifier == null ? "" : identifier.trim();
            boolean isSelf = caller != null && !wanted.isEmpty() && (
                    wanted.equalsIgnoreCase(caller.getId())
                            || wanted.equalsIgnoreCase(caller.getEmail() == null ? "" : caller.getEmail().trim())
                            || wanted.equalsIgnoreCase(caller.getJiraAccountId() == null ? "" : caller.getJiraAccountId().trim()));
            if (!isSelf) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Você só pode consultar as suas próprias squads.");
            }
        }
        return ResponseEntity.ok(squadService.getSquadMembersForUser(identifier));
    }

    @GetMapping("/{squadId}/members")
    public ResponseEntity<List<SquadMember>> getMembers(@PathVariable String squadId, HttpServletRequest request) {
        requireSquadReadAccess(squadId, request);
        return ResponseEntity.ok(squadService.getMembers(squadId));
    }

    @PostMapping("/{squadId}/members/{jiraAccountId}")
    public ResponseEntity<SquadMember> saveMember(
            @PathVariable String squadId,
            @PathVariable String jiraAccountId,
            @RequestBody SquadMember member,
            HttpServletRequest request) {
        if (isSelfClaimOnly(member)) {
            // "Sou eu": qualquer membro da squad liga a PRÓPRIA conta a uma linha livre do roster.
            requireSquadWriteAccess(squadId, request);
            String callerIdentifier = claimIdentifierOf(member, request);
            return ResponseEntity.ok(squadService.claimMember(squadId, jiraAccountId, callerIdentifier));
        }
        requireSquadManageAccess(squadId, request);
        // O vínculo de conta nunca é gravado por aqui (só por "Sou eu"): senão a liderança poderia vincular
        // a conta de outra pessoa a uma linha e, por ela, dar acesso à squad.
        member.setClaimedByUid(null);
        // O papel na equipe só muda por POST/PATCH /team/members (Agile Master/People Lead, validado e com auditoria).
        member.setRole(null);
        return ResponseEntity.ok(squadService.saveMember(squadId, jiraAccountId, member));
    }

    /** meetLink vira href no Painel: só http(s) (javascript:, data: etc. executam código ao clicar). */
    static void requireSafeCeremonyLinks(Squad sq) {
        if (sq.getCeremonies() == null || !sq.getCeremonies().isArray()) return;
        for (com.fasterxml.jackson.databind.JsonNode c : sq.getCeremonies()) {
            String link = c.path("meetLink").asText("").trim();
            if (link.isEmpty()) continue;
            boolean ok;
            try {
                java.net.URI u = java.net.URI.create(link);
                ok = ("https".equalsIgnoreCase(u.getScheme()) || "http".equalsIgnoreCase(u.getScheme())) && u.getHost() != null;
            } catch (IllegalArgumentException e) {
                ok = false;
            }
            if (!ok) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Link da cerimônia inválido: use um endereço que comece com https:// (ou http://).");
            }
        }
    }

    private static boolean touchesLeadershipOnlyFields(Squad sq) {
        return sq.getJiraProjectKey() != null || sq.getSyncJql() != null || sq.getJiraDomain() != null
                || sq.getSprintFieldId() != null || sq.getRankingEnabled() != null || sq.getRankingEnabledAt() != null
                || sq.getReconcileIntervalHours() != null || sq.getDefaultDailyCapacityHours() != null
                || sq.getCapacityCalculationMethod() != null || sq.getCapacityJql() != null || sq.getCapacityFormula() != null
                || sq.getPhases() != null || sq.getSyncOwnerUserId() != null;
    }

    /** Payload só com claimedByUid (e, no máximo, updatedAt/ids): é o "Sou eu". */
    private static boolean isSelfClaimOnly(SquadMember m) {
        return m.getClaimedByUid() != null && !m.getClaimedByUid().isBlank()
                && m.getDisplayName() == null && m.getEmail() == null && m.getRole() == null
                && m.getCapacityHoursPerDay() == null && m.getSystemCalculatedCapacityHoursPerDay() == null
                && m.getCalibrationNotes() == null && m.getOverrideType() == null;
    }

    /** O claimedByUid enviado tem que ser a identidade de quem chama (id da conta ou e-mail). */
    private String claimIdentifierOf(SquadMember member, HttpServletRequest request) {
        String userId = callerId(request);
        User caller = userId != null ? userRepository.findById(userId).orElse(null) : null;
        String sent = member.getClaimedByUid().trim();
        if (caller == null || !(sent.equalsIgnoreCase(caller.getId())
                || (caller.getEmail() != null && sent.equalsIgnoreCase(caller.getEmail().trim())))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Você só pode vincular a sua própria conta.");
        }
        return caller.getId();
    }

    @DeleteMapping("/{squadId}/members/{jiraAccountId}")
    public ResponseEntity<Void> deleteMember(
            @PathVariable String squadId,
            @PathVariable String jiraAccountId,
            HttpServletRequest request) {
        // Remover pessoa do time: Agile Master/People Lead da equipe (ou admin), sem tirar a última liderança.
        squadTeamService.removeMember(squadId, callerUser(request), jiraAccountId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{squadId}/members/batch")
    public ResponseEntity<List<SquadMember>> batchUpsertMembers(
            @PathVariable String squadId,
            @RequestBody List<SquadMember> members,
            HttpServletRequest request) {
        requireSquadManageAccess(squadId, request);
        return ResponseEntity.ok(squadService.batchUpsertMembers(squadId, members));
    }

    // ----- Member Metrics -----
    @GetMapping("/{squadId}/member-metrics")
    public ResponseEntity<List<SquadMemberMetric>> getMemberMetrics(@PathVariable String squadId, HttpServletRequest request) {
        // Horas e utilização por pessoa: dado nominal, só da liderança (ranking é opt-in por squad).
        requireSquadManageAccess(squadId, request);
        return ResponseEntity.ok(squadService.getMemberMetrics(squadId));
    }

    @PostMapping("/{squadId}/member-metrics/batch")
    public ResponseEntity<List<SquadMemberMetric>> batchUpsertMemberMetrics(
            @PathVariable String squadId,
            @RequestBody List<SquadMemberMetric> metrics,
            HttpServletRequest request) {
        requireSquadManageAccess(squadId, request);
        return ResponseEntity.ok(squadService.batchUpsertMemberMetrics(squadId, metrics));
    }

    // ----- Daily Snapshots -----
    @GetMapping("/{squadId}/daily-snapshots")
    public ResponseEntity<List<SquadDailySnapshot>> getDailySnapshots(
            @PathVariable String squadId,
            @RequestParam(required = false) String since,
            HttpServletRequest request) {
        requireSquadReadAccess(squadId, request);
        return ResponseEntity.ok(squadService.getDailySnapshots(squadId, since));
    }

    @PostMapping("/{squadId}/daily-snapshots/batch")
    public ResponseEntity<List<SquadDailySnapshot>> batchUpsertDailySnapshots(
            @PathVariable String squadId,
            @RequestBody List<SquadDailySnapshot> snapshots,
            HttpServletRequest request) {
        requireSquadManageAccess(squadId, request);
        return ResponseEntity.ok(squadService.batchUpsertDailySnapshots(squadId, snapshots));
    }

    // ----- Worklog Cache -----
    @GetMapping("/{squadId}/worklog-cache")
    public ResponseEntity<List<SquadIssueWorklogCache>> getWorklogCache(
            @PathVariable String squadId,
            @RequestParam(required = false) String sprintId,
            HttpServletRequest request) {
        // Horas lançadas por autor: dado nominal, só da liderança.
        requireSquadManageAccess(squadId, request);
        return ResponseEntity.ok(squadService.getWorklogCache(squadId, sprintId));
    }

    @PostMapping("/{squadId}/worklog-cache/batch")
    public ResponseEntity<List<SquadIssueWorklogCache>> batchUpsertWorklogCache(
            @PathVariable String squadId,
            @RequestBody List<SquadIssueWorklogCache> entries,
            HttpServletRequest request) {
        requireSquadManageAccess(squadId, request);
        return ResponseEntity.ok(squadService.batchUpsertWorklogCache(squadId, entries));
    }

    @DeleteMapping("/{squadId}/worklog-cache/{jiraKey}")
    public ResponseEntity<Void> deleteWorklogCacheEntry(
            @PathVariable String squadId,
            @PathVariable String jiraKey,
            HttpServletRequest request) {
        requireSquadManageAccess(squadId, request);
        squadService.deleteWorklogCacheEntry(squadId, jiraKey);
        return ResponseEntity.noContent().build();
    }

    // ----- Person Config (papel/capacidade, herança sprint atual -> anterior -> global) -----
    @GetMapping("/{squadId}/person-config/{jiraAccountId}")
    public ResponseEntity<com.agilespace.backend.service.SquadCapacityService.ResolvedPersonConfig> getPersonConfig(
            @PathVariable String squadId,
            @PathVariable String jiraAccountId,
            @RequestParam(required = false) String sprintId,
            HttpServletRequest request) {
        requireSquadReadAccess(squadId, request);
        return ResponseEntity.ok(squadCapacityService.resolve(squadId, sprintId, jiraAccountId));
    }

    @PutMapping("/{squadId}/person-config/{jiraAccountId}")
    public ResponseEntity<SquadPersonConfig> savePersonConfig(
            @PathVariable String squadId,
            @PathVariable String jiraAccountId,
            @RequestParam(required = false) String sprintId,
            @RequestBody SquadPersonConfig updates,
            HttpServletRequest request) {
        requireSquadManageAccess(squadId, request);
        return ResponseEntity.ok(squadCapacityService.save(squadId, jiraAccountId, sprintId, updates));
    }

    // ----- Panels -----
    @GetMapping("/{squadId}/panels")
    public ResponseEntity<List<SquadPanel>> getPanels(@PathVariable String squadId, HttpServletRequest request) {
        requireSquadReadAccess(squadId, request);
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        return ResponseEntity.ok(squadService.listPanelsForUser(squadId, userId));
    }

    @PostMapping("/{squadId}/panels")
    public ResponseEntity<SquadPanel> createPanel(
            @PathVariable String squadId,
            @Valid @RequestBody SquadPanel panel,
            HttpServletRequest request) {
        requireSquadWriteAccess(squadId, request);
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        return ResponseEntity.status(HttpStatus.CREATED).body(squadService.createPanel(squadId, userId, panel));
    }

    @PutMapping("/{squadId}/panels/{panelId}")
    public ResponseEntity<SquadPanel> updatePanel(
            @PathVariable String squadId,
            @PathVariable UUID panelId,
            @RequestBody SquadPanel panel,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        try {
            return ResponseEntity.ok(squadService.updatePanel(panelId, userId, panel));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{squadId}/panels/{panelId}")
    public ResponseEntity<Void> deletePanel(
            @PathVariable String squadId,
            @PathVariable UUID panelId,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        try {
            squadService.deletePanel(panelId, userId);
            return ResponseEntity.noContent().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
