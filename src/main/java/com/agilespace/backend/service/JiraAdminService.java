package com.agilespace.backend.service;

import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.SquadMetricsRollup;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.*;
import com.agilespace.backend.repository.SquadMemberRepository;
import com.agilespace.backend.repository.SquadMetricsRollupRepository;
import com.agilespace.backend.repository.SquadRepository;
import com.agilespace.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class JiraAdminService {

    @Autowired
    private SquadRepository squadRepository;

    @Autowired
    private SquadMemberRepository squadMemberRepository;

    @Autowired(required = false)
    private SquadMemberExclusions squadMemberExclusions;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SquadMetricsRollupRepository squadMetricsRollupRepository;

    @Autowired
    private JiraService jiraService;

    private final ObjectMapper objectMapper;

    public JiraAdminService() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Passo 1: Consulta o Jira, detecta todos os membros e calcula os cargos sugeridos,
     * retornando a lista para validação e edição antes da persistência no banco.
     */
    public JiraProjectPreviewDto previewProject(JiraSyncRequest request) {
        String domain = JiraService.cleanDomain(request.getJiraDomain());
        String projectKey = request.getProjectKey().trim();
        if (!projectKey.matches("[A-Za-z0-9_]{1,50}")) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Chave de projeto inválida");
        }
        String token = request.getToken().trim();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        headers.set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        String projectUrl = "https://" + domain + "/rest/api/2/project/" + projectKey;
        JsonNode projectNode = getJson(projectUrl, entity, domain);
        String projectName = projectNode.has("name") ? projectNode.get("name").asText() : projectKey;

        Map<String, MemberCandidate> candidateMap = new LinkedHashMap<>();

        java.util.function.BiConsumer<MemberInfo, String> recordMember = (info, rawRole) -> {
            if (info == null || info.accountId == null || info.accountId.isBlank()) return;
            if (isBotOrIntegrationAccount(info.displayName, info.email, info.accountId)) return;

            RoleScore eval = evaluateRole(rawRole);
            candidateMap.compute(info.accountId, (k, existing) -> {
                if (existing == null) {
                    return new MemberCandidate(info.accountId, info.displayName, info.email, eval.canonicalRole, eval.score, info.avatarUrl);
                }
                String bestName = (existing.displayName != null && !existing.displayName.isBlank()) ? existing.displayName : info.displayName;
                String bestEmail = (existing.email != null && !existing.email.isBlank()) ? existing.email : info.email;
                String bestAvatar = (existing.avatarUrl != null && !existing.avatarUrl.isBlank()) ? existing.avatarUrl : info.avatarUrl;
                if (eval.score > existing.score) {
                    return new MemberCandidate(k, bestName, bestEmail, eval.canonicalRole, eval.score, bestAvatar);
                }
                return new MemberCandidate(k, bestName, bestEmail, existing.role, existing.score, bestAvatar);
            });
        };

        // 1. Capturar o Project Lead (Líder / Gestor do Projeto no Jira)
        if (projectNode.has("lead") && !projectNode.get("lead").isNull()) {
            MemberInfo leadInfo = extractMemberInfo(projectNode.get("lead"));
            if (leadInfo != null) {
                log.debug("Project Lead detectado no projeto {}", projectKey);
                recordMember.accept(leadInfo, "Tech Lead");
            }
        }

        // 2. Buscar por papéis (roles) do projeto
        try {
            JsonNode rolesNode = null;
            if (projectNode.has("roles") && projectNode.get("roles").isObject()) {
                rolesNode = projectNode.get("roles");
            } else {
                try {
                    String rolesUrl = "https://" + domain + "/rest/api/2/project/" + projectKey + "/role";
                    rolesNode = getJson(rolesUrl, entity, domain);
                } catch (Exception e) {
                    log.warn("Falha ao buscar roles do projeto {} via endpoint /role: {}", projectKey, e.getMessage());
                }
            }

            if (rolesNode != null && rolesNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> roleFields = rolesNode.fields();
                while (roleFields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = roleFields.next();
                    String roleName = entry.getKey();
                    String roleUrl = entry.getValue().asText();
                    try {
                        JsonNode roleDetail = getJson(roleUrl, entity, domain);
                        JsonNode actors = roleDetail.get("actors");
                        if (actors != null && actors.isArray()) {
                            for (JsonNode actor : actors) {
                                boolean isGroupActor = actor.has("actorGroup")
                                        || (actor.has("type") && actor.get("type").asText().contains("group"));
                                MemberInfo m = isGroupActor ? null : extractMemberInfo(actor);
                                if (m != null) {
                                    log.debug("Membro encontrado no Project Role '{}' do projeto {}", roleName, projectKey);
                                    recordMember.accept(m, roleName);
                                } else if (actor.has("name") && (actor.has("actorGroup") || (actor.has("type") && actor.get("type").asText().contains("group")))) {
                                    String groupName = actor.has("name") ? actor.get("name").asText() : "";
                                    if (!groupName.isBlank()) {
                                        try {
                                            String groupUrl = "https://" + domain + "/rest/api/2/group/member?groupname="
                                                    + java.net.URLEncoder.encode(groupName, java.nio.charset.StandardCharsets.UTF_8);
                                            JsonNode groupNode = getJson(groupUrl, entity, domain);
                                            JsonNode values = groupNode.has("values") ? groupNode.get("values") : groupNode.get("users");
                                            if (values != null && values.isArray()) {
                                                for (JsonNode uNode : values) {
                                                    MemberInfo gm = extractMemberInfo(uNode);
                                                    if (gm != null) {
                                                        recordMember.accept(gm, roleName);
                                                    }
                                                }
                                            }
                                        } catch (Exception gEx) {
                                            log.debug("Grupo {}: {}", groupName, gEx.getMessage());
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to fetch members for role {}: {}", roleName, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Roles do projeto {} no Jira: {}", projectKey, e.getMessage());
        }

        // 3. Buscar Líderes de Componentes
        try {
            String compUrl = "https://" + domain + "/rest/api/2/project/" + projectKey + "/components";
            JsonNode compNode = getJson(compUrl, entity, domain);
            if (compNode != null && compNode.isArray()) {
                for (JsonNode comp : compNode) {
                    if (comp.has("lead") && !comp.get("lead").isNull()) {
                        MemberInfo compLead = extractMemberInfo(comp.get("lead"));
                        if (compLead != null) {
                            recordMember.accept(compLead, "Tech Lead");
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Falha ao buscar líderes de componentes do projeto {}: {}", projectKey, e.getMessage());
        }

        // 4. Épicos e Iniciativas via JQL
        try {
            String epicSearchUrl = "https://" + domain + "/rest/api/2/search?jql=project%3D" + projectKey + "+AND+issuetype+in+(Epic%2CEpico%2C%C3%89pico%2CInitiative%2CIniciativa%2CFeature%2CTema)+ORDER+BY+updated+DESC&fields=*all&expand=names&maxResults=100";
            collectMembersFromJql(epicSearchUrl, entity, recordMember, domain);
        } catch (Exception e) {
            log.warn("Falha ao buscar membros via épicos/iniciativas do projeto {}: {}", projectKey, e.getMessage());
        }

        // 5. Tarefas Recentes via JQL
        try {
            String searchUrl = "https://" + domain + "/rest/api/2/search?jql=project%3D" + projectKey + "+ORDER+BY+updated+DESC&fields=*all&expand=names&maxResults=100";
            collectMembersFromJql(searchUrl, entity, recordMember, domain);
        } catch (Exception e) {
            log.warn("Falha ao buscar membros via tarefas recentes do projeto {}: {}", projectKey, e.getMessage());
        }

        List<JiraMemberCandidateDto> members = candidateMap.values().stream()
                .map(c -> JiraMemberCandidateDto.builder()
                        .jiraAccountId(c.accountId)
                        .displayName(c.displayName)
                        .email(c.email)
                        .avatarUrl(c.avatarUrl)
                        .role(c.role)
                        .score(c.score)
                        .selected(true)
                        .capacityHoursPerDay(8.0)
                        .build())
                .sorted(Comparator.comparingInt(JiraMemberCandidateDto::getScore).reversed()
                        .thenComparing(JiraMemberCandidateDto::getDisplayName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());

        return JiraProjectPreviewDto.builder()
                .squadId(projectKey)
                .squadName(projectName)
                .jiraDomain(domain)
                .totalCandidates(members.size())
                .members(members)
                .build();
    }

    /**
     * Passo 2: O usuário revisou, alterou cargos ou removeu pessoas e confirmou.
     * Persiste a Squad, sincroniza os membros e atualiza de forma atômica a tabela `users`.
     */
    @Transactional
    public JiraSyncResult confirmSync(JiraConfirmSyncRequest request) {
        String projectKey = request.getSquadId().trim().toUpperCase();
        String domain = request.getJiraDomain() != null ? request.getJiraDomain().trim() : "";
        String projectName = request.getSquadName() != null && !request.getSquadName().isBlank()
                ? request.getSquadName().trim()
                : projectKey;

        // 1. Salva ou atualiza a Squad
        Squad squad = squadRepository.findById(projectKey).orElse(new Squad());
        squad.setId(projectKey);
        squad.setName(projectName);
        squad.setJiraProjectKey(projectKey);
        if (!domain.isBlank()) squad.setJiraDomain(domain);
        squad.setLastSyncAt(Instant.now().toString());
        squad.setLastSyncStatus("success");
        squadRepository.save(squad);

        // 1.1 Salva ou atualiza o Rollup de Métricas da Squad
        SquadMetricsRollup rollup = squadMetricsRollupRepository.findById(projectKey).orElse(new SquadMetricsRollup());
        rollup.setSquadId(projectKey);
        if (rollup.getSprintName() == null || rollup.getSprintName().isBlank()) {
            rollup.setSprintName("Sprint Ativa (" + projectKey + ")");
        }
        if (rollup.getTotalIssues() == null) rollup.setTotalIssues(0);
        if (rollup.getDoneIssues() == null) rollup.setDoneIssues(0);
        if (rollup.getInProgressIssues() == null) rollup.setInProgressIssues(0);
        if (rollup.getBugIssues() == null) rollup.setBugIssues(0);
        if (rollup.getStaleIssues() == null) rollup.setStaleIssues(0);
        if (rollup.getDueSoonIssues() == null) rollup.setDueSoonIssues(0);
        if (rollup.getOverdueIssues() == null) rollup.setOverdueIssues(0);
        if (rollup.getEstimateTotalSec() == null) rollup.setEstimateTotalSec(0L);
        if (rollup.getRemainingTotalSec() == null) rollup.setRemainingTotalSec(0L);
        if (rollup.getLoggedTotalSec() == null) rollup.setLoggedTotalSec(0L);
        if (rollup.getWorkdaysTotal() == null) rollup.setWorkdaysTotal(10);
        if (rollup.getWorkdaysRemaining() == null) rollup.setWorkdaysRemaining(10);
        rollup.setComputedAt(Instant.now().toString());
        squadMetricsRollupRepository.save(rollup);

        // Quem a liderança removeu à mão do time não volta por esta importação.
        SquadMemberExclusions.Removed removedByHand = SquadMemberExclusions.of(squadMemberExclusions, projectKey);
        List<JiraMemberCandidateDto> approvedMembers = request.getMembers() != null
                ? request.getMembers().stream()
                        .filter(JiraMemberCandidateDto::isSelected)
                        .filter(m -> {
                            boolean skip = removedByHand.matches(m.getJiraAccountId(), m.getEmail());
                            if (skip) log.info("Importação da Squad {}: {} foi removido(a) à mão do time e não será recolocado(a).", projectKey, m.getDisplayName());
                            return !skip;
                        })
                        .collect(Collectors.toList())
                : Collections.emptyList();

        Set<String> approvedAccountIds = approvedMembers.stream()
                .map(JiraMemberCandidateDto::getJiraAccountId)
                .collect(Collectors.toSet());

        // 2. Se replaceExisting = true, remove membros antigos da squad que não foram aprovados
        if (request.isReplaceExisting()) {
            List<SquadMember> currentMembers = squadMemberRepository.findBySquadIdOrderByDisplayNameAsc(projectKey);
            for (SquadMember current : currentMembers) {
                if (!approvedAccountIds.contains(current.getJiraAccountId())) {
                    log.info("Removendo membro não confirmado da Squad {}: {}", projectKey, current.getDisplayName());
                    squadMemberRepository.delete(current);

                    // Se syncUsers ativo, desvincula o squadId do usuário
                    if (request.isSyncUsers()) {
                        userRepository.findByJiraAccountId(current.getJiraAccountId()).ifPresent(u -> {
                            if (projectKey.equalsIgnoreCase(u.getSquadId())) {
                                u.setSquadId(null);
                                u.setUpdatedAt(LocalDateTime.now());
                                userRepository.save(u);
                            }
                        });
                        if (current.getEmail() != null && !current.getEmail().isBlank()) {
                            userRepository.findByEmail(current.getEmail()).ifPresent(u -> {
                                if (projectKey.equalsIgnoreCase(u.getSquadId())) {
                                    u.setSquadId(null);
                                    u.setUpdatedAt(LocalDateTime.now());
                                    userRepository.save(u);
                                }
                            });
                        }
                    }
                }
            }
        }

        // 3. Salva todos os membros aprovados e atualiza a base central de usuários
        int membersSaved = 0;
        for (JiraMemberCandidateDto m : approvedMembers) {
            membersSaved++;
            String dbId = projectKey + "_" + m.getJiraAccountId();
            SquadMember member = squadMemberRepository.findById(dbId).orElse(new SquadMember());
            member.setDbId(dbId);
            member.setSquadId(projectKey);
            member.setJiraAccountId(m.getJiraAccountId());
            member.setDisplayName(m.getDisplayName());
            member.setEmail(m.getEmail());
            member.setRole(m.getRole());
            member.setCapacityHoursPerDay(m.getCapacityHoursPerDay() != null ? m.getCapacityHoursPerDay() : 8.0);
            member.setUpdatedAt(Instant.now().toString());

            if (request.isSyncUsers()) {
                try {
                    User user = userRepository.findById(m.getJiraAccountId()).orElse(null);
                    if (user == null && m.getEmail() != null && !m.getEmail().isBlank()) {
                        user = userRepository.findByEmail(m.getEmail()).orElse(null);
                    }
                    if (user == null) {
                        user = userRepository.findByJiraAccountId(m.getJiraAccountId()).orElse(null);
                    }

                    if (user == null) {
                        user = new User();
                        user.setId(m.getJiraAccountId());
                        user.setName(m.getDisplayName());
                        user.setEmail(m.getEmail() != null && !m.getEmail().isBlank() ? m.getEmail() : m.getJiraAccountId() + "@empresa.com");
                        user.setJobTitle(m.getRole()); // Cargo validado pelo usuário (não é tier de autorização)
                        user.setSquadId(projectKey);
                        user.setJiraAccountId(m.getJiraAccountId());
                        user.setAvatarSeed(m.getDisplayName());
                        user.setDailyHours(m.getCapacityHoursPerDay() != null ? m.getCapacityHoursPerDay().intValue() : 8);
                        user.setCreatedAt(LocalDateTime.now());
                        user.setUpdatedAt(LocalDateTime.now());
                        user = userRepository.save(user);
                    } else {
                        // Atualiza dados e cargo validado. NUNCA grava em user.role: esse campo é o tier
                        // de autorização (ver User.role/UserRole) e sincronizar squad não deve poder
                        // rebaixar/elevar o acesso admin de alguém a partir de um texto de cargo do Jira.
                        user.setName(m.getDisplayName());
                        user.setJobTitle(m.getRole());
                        user.setSquadId(projectKey);
                        user.setJiraAccountId(m.getJiraAccountId());
                        if (m.getCapacityHoursPerDay() != null) {
                            user.setDailyHours(m.getCapacityHoursPerDay().intValue());
                        }
                        user.setUpdatedAt(LocalDateTime.now());
                        user = userRepository.save(user);
                    }

                    member.setClaimedByUid(user.getId());
                } catch (Exception ex) {
                    log.warn("Erro ao sincronizar usuário {} na tabela users: {}", m.getJiraAccountId(), ex.getMessage());
                }
            }

            squadMemberRepository.save(member);
        }

        return JiraSyncResult.builder()
                .squadId(projectKey)
                .squadName(projectName)
                .membersFound(membersSaved)
                .syncedAt(Instant.now().toString())
                .build();
    }

    /**
     * Sincronização direta legado / compatibilidade.
     */
    @Transactional
    public JiraSyncResult syncProject(JiraSyncRequest request) {
        JiraProjectPreviewDto preview = previewProject(request);
        JiraConfirmSyncRequest confirm = JiraConfirmSyncRequest.builder()
                .squadId(preview.getSquadId())
                .squadName(preview.getSquadName())
                .jiraDomain(preview.getJiraDomain())
                .replaceExisting(true)
                .syncUsers(true)
                .members(preview.getMembers())
                .build();
        return confirmSync(confirm);
    }

    private void collectMembersFromJql(String searchUrl, HttpEntity<Void> entity, java.util.function.BiConsumer<MemberInfo, String> recordMember, String domain) {
        JsonNode searchNode = getJson(searchUrl, entity, domain);
        Map<String, String> fieldNamesMap = new HashMap<>();
        if (searchNode.has("names") && searchNode.get("names").isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = searchNode.get("names").fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                fieldNamesMap.put(entry.getKey(), entry.getValue().asText());
            }
        }

        if (searchNode.has("issues") && searchNode.get("issues").isArray()) {
            for (JsonNode issue : searchNode.get("issues")) {
                if (!issue.has("fields") || !issue.get("fields").isObject()) continue;
                JsonNode fields = issue.get("fields");

                Iterator<Map.Entry<String, JsonNode>> fieldIt = fields.fields();
                while (fieldIt.hasNext()) {
                    Map.Entry<String, JsonNode> fEntry = fieldIt.next();
                    String fieldKey = fEntry.getKey();
                    JsonNode fieldValue = fEntry.getValue();
                    String rawRoleName = fieldNamesMap.getOrDefault(fieldKey, fieldKey);

                    if ("assignee".equalsIgnoreCase(fieldKey)) {
                        rawRoleName = "Developer";
                    } else if ("reporter".equalsIgnoreCase(fieldKey) || "creator".equalsIgnoreCase(fieldKey)) {
                        rawRoleName = "Solicitante";
                    }

                    if (fieldValue.isArray()) {
                        for (JsonNode item : fieldValue) {
                            if (isUserObject(item)) {
                                MemberInfo m = extractMemberInfo(item);
                                if (m != null) {
                                    recordMember.accept(m, rawRoleName);
                                }
                            }
                        }
                    } else if (isUserObject(fieldValue)) {
                        MemberInfo m = extractMemberInfo(fieldValue);
                        if (m != null) {
                            recordMember.accept(m, rawRoleName);
                        }
                    }
                }
            }
        }
    }

    /**
     * GET no Jira. A URL pode vir de dentro de uma resposta do próprio Jira (links dos Project Roles); por isso só
     * é seguida se for https e do MESMO host configurado — senão o token e a requisição iriam para onde o JSON mandar.
     */
    private JsonNode getJson(String url, HttpEntity<Void> entity, String domain) {
        java.net.URI uri;
        try {
            uri = java.net.URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Endereço inválido devolvido pelo Jira.", e);
        }
        String expectedHost = domain.replaceFirst(":[0-9]+$", "");
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || !uri.getHost().equalsIgnoreCase(expectedHost)) {
            throw new RuntimeException("Endereço fora do domínio do Jira configurado foi ignorado.");
        }
        try {
            ResponseEntity<String> response = jiraService.exchangeGet(uri, entity);
            return objectMapper.readTree(response.getBody());
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            String body = e.getResponseBodyAsString();
            if (body != null && body.contains("Cloudflare")) {
                throw new RuntimeException("Requisição bloqueada pelo Cloudflare WAF (403 Forbidden).", e);
            }
            if (body != null && body.contains("errorMessages")) {
                try {
                    JsonNode errNode = objectMapper.readTree(body);
                    if (errNode.has("errorMessages") && errNode.get("errorMessages").isArray() && !errNode.get("errorMessages").isEmpty()) {
                        String msg = errNode.get("errorMessages").get(0).asText();
                        throw new RuntimeException("Erro na API do Jira: " + msg, e);
                    }
                } catch (RuntimeException re) {
                    throw re;
                } catch (Exception ignored) {}
            }
            throw new RuntimeException("O Jira respondeu " + e.getStatusCode().value() + " ao consultar o projeto.", e);
        } catch (Exception e) {
            throw new RuntimeException("Falha ao consultar o Jira: " + JiraService.describe(e), e);
        }
    }

    private boolean isBotOrIntegrationAccount(String displayName, String email, String accountId) {
        String combined = ((displayName != null ? displayName : "") + " " +
                           (email != null ? email : "") + " " +
                           (accountId != null ? accountId : "")).toLowerCase(java.util.Locale.ROOT);
        return combined.contains("integrador") ||
               combined.contains("zendesk") ||
               combined.contains("plataformas") ||
               combined.contains("robo") ||
               combined.contains("bot@") ||
               combined.contains("service_account") ||
               combined.contains("no-reply") ||
               combined.contains("daemon@") ||
               combined.contains("jira_admin") ||
               combined.contains("automation");
    }

    private boolean isUserObject(JsonNode node) {
        if (node == null || !node.isObject()) return false;
        if (node.has("self") && node.get("self").asText().contains("/user")) return true;
        if (node.has("actorUser") && node.get("actorUser").isObject()) return true;
        if (node.has("displayName") && (node.has("name") || node.has("key") || node.has("accountId") || node.has("emailAddress") || node.has("avatarUrls") || node.has("active"))) return true;
        if (node.has("name") && (node.has("key") || node.has("emailAddress") || node.has("displayName") || node.has("accountId"))) return true;
        if (node.has("emailAddress") && (node.has("name") || node.has("displayName") || node.has("key") || node.has("accountId"))) return true;
        if (node.has("user") && node.get("user").isObject()) return true;
        if (node.has("value") && node.get("value").isObject() && isUserObject(node.get("value"))) return true;
        return false;
    }


    /**
     * URL da foto do usuário no Jira: avatarUrls (48x48 > 32x32 > 24x24 > 16x16) ou avatarUrl simples. Null se não houver.
     */
    static String extractAvatarUrl(JsonNode userNode) {
        if (userNode == null || userNode.isNull()) return null;
        JsonNode urls = userNode.get("avatarUrls");
        if (urls != null && urls.isObject()) {
            for (String size : new String[]{"48x48", "32x32", "24x24", "16x16"}) {
                if (urls.hasNonNull(size) && !urls.get(size).asText().isBlank()) return urls.get(size).asText();
            }
        }
        if (userNode.hasNonNull("avatarUrl") && !userNode.get("avatarUrl").asText().isBlank()) {
            return userNode.get("avatarUrl").asText();
        }
        return null;
    }

    private MemberInfo extractMemberInfo(JsonNode userNode) {
        if (userNode == null || userNode.isNull()) return null;
        if (userNode.has("actorUser") && userNode.get("actorUser").isObject()) {
            userNode = userNode.get("actorUser");
        }
        if (userNode.has("author") && userNode.get("author").isObject()) {
            userNode = userNode.get("author");
        }
        if (userNode.has("user") && userNode.get("user").isObject()) {
            userNode = userNode.get("user");
        }
        if (userNode.has("value") && userNode.get("value").isObject()) {
            userNode = userNode.get("value");
        }
        String accountId = null;
        if (userNode.has("accountId") && !userNode.get("accountId").asText().isBlank()) {
            accountId = userNode.get("accountId").asText();
        } else if (userNode.has("name") && !userNode.get("name").asText().isBlank()) {
            accountId = userNode.get("name").asText();
        } else if (userNode.has("key") && !userNode.get("key").asText().isBlank()) {
            accountId = userNode.get("key").asText();
        } else if (userNode.has("emailAddress") && !userNode.get("emailAddress").asText().isBlank()) {
            accountId = userNode.get("emailAddress").asText().split("@")[0].toLowerCase();
        }
        if (accountId == null || accountId.isBlank()) return null;
        String displayName = userNode.has("displayName") && !userNode.get("displayName").asText().isBlank()
                ? userNode.get("displayName").asText() 
                : accountId;
        String email = userNode.has("emailAddress") && !userNode.get("emailAddress").asText().isBlank()
                ? userNode.get("emailAddress").asText() 
                : null;
        return new MemberInfo(accountId, displayName, email, extractAvatarUrl(userNode));
    }

    /** `token` como palavra inteira ("rte" casa em "RTE", não em "Suporte"/"Parte"; "ui" não casa em "Equipe"). */
    static boolean hasWord(String name, String token) {
        return java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])" + java.util.regex.Pattern.quote(token) + "(?![\\p{L}\\p{N}])")
                .matcher(name).find();
    }

    static RoleScore evaluateRole(String rawRoleName) {
        if (rawRoleName == null || rawRoleName.isBlank()) return new RoleScore("Developer", 10);
        String name = rawRoleName.toLowerCase(java.util.Locale.ROOT).trim();

        // 1. Gestão de Produto (Product Owner, PM, GPM, Dono do Produto) -> Score 100
        if (name.contains("product owner") || name.equals("po") || name.equals("p.o") || 
            name.equals("p.o.") || name.contains("dono do produto") || name.contains("product manager") || 
            hasWord(name, "gpm") || name.contains("líder de produto") || name.contains("lider de produto") ||
            name.contains("lead product") || name.contains("analista de produto") || name.contains("product lead") ||
            name.contains("gerente de produto")) {
            return new RoleScore("Product Owner", 100);
        }

        // 2. Gestão de Pessoas, Gerência & Liderança Executiva / Gestão de Projetos -> Score 96
        if (name.contains("gestor") || name.contains("gestora") || name.contains("gerente") || 
            name.contains("gestão") || name.contains("gestao") || name.contains("project manager") || 
            name.equals("gp") || name.equals("pm") || name.contains("coordenador") || 
            name.contains("coordenadora") || name.contains("coordenação") || name.contains("coordenacao") ||
            name.contains("engineering manager") || name.equals("em") || name.contains("diretor") || 
            hasWord(name, "head") || name.contains("management") || name.contains("administrators") ||
            name.contains("administrador") || name.contains("administradora") || name.contains("people lead") ||
            name.equals("pl") || name.contains("líder de pessoas") || name.contains("lider de pessoas")) {
            return new RoleScore("People Lead", 96);
        }

        // 3. Agile Coach / Agile Coaching é cargo próprio (liderança transversal da tribo), não Agile Master.
        // Vem antes do bloco do AM porque "agile coaching" não pode cair no "scrum"/"agile" mais abaixo.
        if (name.contains("agile coach") || name.contains("coaching") || name.equals("coach") || name.equals("ac")) {
            return new RoleScore("Agile Coach", 94);
        }

        // 4. Gestão Ágil / Facilitação (Agile Master / Scrum Master / Agilista / RTE) -> Score 95
        if (name.contains("agile master") || name.contains("scrum master") || name.equals("am") ||
            name.equals("sm") || name.contains("agilista") || name.contains("facilitador") ||
            name.contains("facilitadora") || hasWord(name, "rte") || name.contains("release train") ||
            name.contains("scrum")) {
            return new RoleScore("Agile Master", 95);
        }

        // 4. Liderança Técnica / Arquitetura (Tech Lead / Arquiteto) -> Score 90
        if (name.contains("tech lead") || name.contains("líder técnico") || name.contains("lider tecnico") || 
            name.contains("tech leader") || name.contains("technical lead") || name.contains("arquiteto") || 
            name.contains("arquiteta") || name.contains("lead developer") || name.contains("lead dev") ||
            name.contains("líder de desenvolvimento") || name.contains("lider de desenvolvimento") ||
            name.contains("líder de equipe") || name.contains("lider de equipe") || name.contains("team lead") ||
            name.contains("project lead") || name.contains("component lead") || name.contains("líder") || 
            name.contains("lider") || name.contains("leader") || name.equals("lead")) {
            return new RoleScore("Tech Lead", 90);
        }

        // 5. Tribe Lead / Liderança da Tribo -> Score 85
        if (name.contains("tribe lead") || name.equals("tl") || name.contains("líder da tribo") || 
            name.contains("lider da tribo") || name.contains("líder de tribo") || name.contains("lider de tribo") ||
            name.contains("tribo") || name.contains("tribe")) {
            return new RoleScore("Tribe Lead", 85);
        }

        // 6. QA / Qualidade -> Score 70
        if (hasWord(name, "qa") || name.contains("teste") || name.contains("test") || 
            name.contains("qualidade") || name.contains("tester") || name.contains("quality")) {
            return new RoleScore("QA", 70);
        }

        // 7. Design / UX / UI -> Score 65
        if (hasWord(name, "ux") || hasWord(name, "ui") || name.contains("design") || 
            name.contains("designer") || name.contains("produto visual")) {
            return new RoleScore("UX/Designer", 65);
        }

        // 8. Especialista de Negócio / SME -> Score 60
        if (hasWord(name, "sme") || name.contains("subject matter expert") || name.contains("especialista") ||
            name.contains("business owner") || name.contains("analista de negócio") || name.contains("analista de negocio") ||
            name.contains("business analyst") || name.equals("ba") || name.contains("consultor") || name.contains("consultora")) {
            return new RoleScore("SME", 60);
        }

        // 9. Desenvolvedores -> Score 50
        if (name.contains("desenvolvedor") || name.contains("desenvolvedora") || name.contains("developer") || 
            hasWord(name, "dev") || name.contains("programador") || name.contains("programadora") || 
            name.contains("engenheiro") || name.contains("engenheira") || name.contains("software") || 
            name.contains("analista") || name.contains("assignee") || name.contains("responsável") || 
            name.contains("responsavel")) {
            return new RoleScore("Developer", 50);
        }

        return new RoleScore("Developer", 10);
    }

    static class RoleScore {
        final String canonicalRole;
        final int score;
        RoleScore(String canonicalRole, int score) {
            this.canonicalRole = canonicalRole;
            this.score = score;
        }
    }

    private static class MemberCandidate {
        final String accountId;
        final String displayName;
        final String email;
        final String role;
        final int score;

        final String avatarUrl;

        MemberCandidate(String accountId, String displayName, String email, String role, int score, String avatarUrl) {
            this.accountId = accountId;
            this.displayName = displayName;
            this.email = email;
            this.role = role;
            this.score = score;
            this.avatarUrl = avatarUrl;
        }
    }

    private static class MemberInfo {
        final String accountId;
        final String displayName;
        final String email;
        final String avatarUrl;

        MemberInfo(String accountId, String displayName, String email, String avatarUrl) {
            this.accountId = accountId;
            this.displayName = displayName;
            this.email = email;
            this.avatarUrl = avatarUrl;
        }
    }
}
