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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
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

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SquadMetricsRollupRepository squadMetricsRollupRepository;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public JiraAdminService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                if (connection instanceof HttpsURLConnection) {
                    try {
                        SSLContext sslContext = SSLContext.getInstance("TLS");
                        sslContext.init(null, new TrustManager[]{
                            new X509TrustManager() {
                                public X509Certificate[] getAcceptedIssuers() { return null; }
                                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                            }
                        }, new SecureRandom());
                        ((HttpsURLConnection) connection).setSSLSocketFactory(sslContext.getSocketFactory());
                        ((HttpsURLConnection) connection).setHostnameVerifier((hostname, session) -> true);
                    } catch (Exception e) {
                        log.error("Error setting trust-all SSL context", e);
                    }
                }
                super.prepareConnection(connection, httpMethod);
            }
        };
        factory.setConnectTimeout(15000);
        factory.setReadTimeout(45000);
        this.restTemplate = new RestTemplate(factory);
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Passo 1: Consulta o Jira, detecta todos os membros e calcula os cargos sugeridos,
     * retornando a lista para validação e edição antes da persistência no banco.
     */
    public JiraProjectPreviewDto previewProject(JiraSyncRequest request) {
        String domain = request.getJiraDomain().trim().replace("https://", "").replace("http://", "");
        String projectKey = request.getProjectKey().trim();
        String token = request.getToken().trim();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        headers.set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        String projectUrl = "https://" + domain + "/rest/api/2/project/" + projectKey;
        JsonNode projectNode = getJson(projectUrl, entity);
        String projectName = projectNode.has("name") ? projectNode.get("name").asText() : projectKey;

        Map<String, MemberCandidate> candidateMap = new LinkedHashMap<>();

        java.util.function.BiConsumer<MemberInfo, String> recordMember = (info, rawRole) -> {
            if (info == null || info.accountId == null || info.accountId.isBlank()) return;
            if (isBotOrIntegrationAccount(info.displayName, info.email, info.accountId)) return;

            RoleScore eval = evaluateRole(rawRole);
            candidateMap.compute(info.accountId, (k, existing) -> {
                if (existing == null) {
                    return new MemberCandidate(info.accountId, info.displayName, info.email, eval.canonicalRole, eval.score);
                }
                String bestName = (existing.displayName != null && !existing.displayName.isBlank()) ? existing.displayName : info.displayName;
                String bestEmail = (existing.email != null && !existing.email.isBlank()) ? existing.email : info.email;
                if (eval.score > existing.score) {
                    return new MemberCandidate(k, bestName, bestEmail, eval.canonicalRole, eval.score);
                }
                return new MemberCandidate(k, bestName, bestEmail, existing.role, existing.score);
            });
        };

        // 1. Capturar o Project Lead (Líder / Gestor do Projeto no Jira)
        if (projectNode.has("lead") && !projectNode.get("lead").isNull()) {
            MemberInfo leadInfo = extractMemberInfo(projectNode.get("lead"));
            if (leadInfo != null) {
                log.info("Project Lead detectado: {} ({})", leadInfo.displayName, leadInfo.accountId);
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
                    rolesNode = getJson(rolesUrl, entity);
                } catch (Exception ignored) {}
            }

            if (rolesNode != null && rolesNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> roleFields = rolesNode.fields();
                while (roleFields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = roleFields.next();
                    String roleName = entry.getKey();
                    String roleUrl = entry.getValue().asText();
                    try {
                        JsonNode roleDetail = getJson(roleUrl, entity);
                        JsonNode actors = roleDetail.get("actors");
                        if (actors != null && actors.isArray()) {
                            for (JsonNode actor : actors) {
                                MemberInfo m = extractMemberInfo(actor);
                                if (m != null) {
                                    log.info("Membro em Project Role '{}': {} ({})", roleName, m.displayName, m.accountId);
                                    recordMember.accept(m, roleName);
                                } else if (actor.has("name") && (actor.has("actorGroup") || (actor.has("type") && actor.get("type").asText().contains("group")))) {
                                    String groupName = actor.has("name") ? actor.get("name").asText() : "";
                                    if (!groupName.isBlank()) {
                                        try {
                                            String groupUrl = "https://" + domain + "/rest/api/2/group/member?groupname=" + groupName;
                                            JsonNode groupNode = getJson(groupUrl, entity);
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
            JsonNode compNode = getJson(compUrl, entity);
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
        } catch (Exception ignored) {}

        // 4. Épicos e Iniciativas via JQL
        try {
            String epicSearchUrl = "https://" + domain + "/rest/api/2/search?jql=project%3D" + projectKey + "+AND+issuetype+in+(Epic%2CEpico%2C%C3%89pico%2CInitiative%2CIniciativa%2CFeature%2CTema)+ORDER+BY+updated+DESC&fields=*all&expand=names&maxResults=100";
            collectMembersFromJql(epicSearchUrl, entity, recordMember);
        } catch (Exception ignored) {}

        // 5. Tarefas Recentes via JQL
        try {
            String searchUrl = "https://" + domain + "/rest/api/2/search?jql=project%3D" + projectKey + "+ORDER+BY+updated+DESC&fields=*all&expand=names&maxResults=100";
            collectMembersFromJql(searchUrl, entity, recordMember);
        } catch (Exception ignored) {}

        List<JiraMemberCandidateDto> members = candidateMap.values().stream()
                .map(c -> JiraMemberCandidateDto.builder()
                        .jiraAccountId(c.accountId)
                        .displayName(c.displayName)
                        .email(c.email)
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

        List<JiraMemberCandidateDto> approvedMembers = request.getMembers() != null
                ? request.getMembers().stream().filter(JiraMemberCandidateDto::isSelected).collect(Collectors.toList())
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
                        user.setEmail(m.getEmail() != null && !m.getEmail().isBlank() ? m.getEmail() : m.getJiraAccountId() + "@totvs.com.br");
                        user.setRole(m.getRole()); // Cargo validado pelo usuário
                        user.setSquadId(projectKey);
                        user.setJiraAccountId(m.getJiraAccountId());
                        user.setAvatarSeed(m.getDisplayName());
                        user.setDailyHours(m.getCapacityHoursPerDay() != null ? m.getCapacityHoursPerDay().intValue() : 8);
                        user.setCreatedAt(LocalDateTime.now());
                        user.setUpdatedAt(LocalDateTime.now());
                        user = userRepository.save(user);
                    } else {
                        // Atualiza dados e CARGO validado
                        user.setName(m.getDisplayName());
                        user.setRole(m.getRole()); // Atualiza o cargo na tabela users!
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

    private void collectMembersFromJql(String searchUrl, HttpEntity<Void> entity, java.util.function.BiConsumer<MemberInfo, String> recordMember) {
        JsonNode searchNode = getJson(searchUrl, entity);
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

    private JsonNode getJson(String url, HttpEntity<Void> entity) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, entity, String.class);
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
            throw new RuntimeException("Failed to GET " + url + ": " + e.getStatusCode() + " " + e.getStatusText(), e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to GET " + url + ": " + e.getMessage(), e);
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
        return new MemberInfo(accountId, displayName, email);
    }

    private RoleScore evaluateRole(String rawRoleName) {
        if (rawRoleName == null || rawRoleName.isBlank()) return new RoleScore("Developer", 10);
        String name = rawRoleName.toLowerCase(java.util.Locale.ROOT).trim();

        // 1. Gestão de Produto (Product Owner, PM, GPM, Dono do Produto) -> Score 100
        if (name.contains("product owner") || name.equals("po") || name.equals("p.o") || 
            name.equals("p.o.") || name.contains("dono do produto") || name.contains("product manager") || 
            name.contains("gpm") || name.contains("líder de produto") || name.contains("lider de produto") ||
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
            name.contains("head") || name.contains("management") || name.contains("administrators") ||
            name.contains("administrador") || name.contains("administradora") || name.contains("people lead") ||
            name.equals("pl") || name.contains("líder de pessoas") || name.contains("lider de pessoas")) {
            return new RoleScore("People Lead", 96);
        }

        // 3. Gestão Ágil / Facilitação (Agile Master / Scrum Master / Agilista / RTE) -> Score 95
        if (name.contains("agile master") || name.contains("scrum master") || name.equals("am") || 
            name.equals("sm") || name.contains("agilista") || name.contains("facilitador") || 
            name.contains("facilitadora") || name.contains("rte") || name.contains("release train") ||
            name.contains("scrum") || name.contains("agile coach") || name.equals("ac")) {
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
        if (name.contains("qa") || name.contains("teste") || name.contains("test") || 
            name.contains("qualidade") || name.contains("tester") || name.contains("quality")) {
            return new RoleScore("QA", 70);
        }

        // 7. Design / UX / UI -> Score 65
        if (name.contains("ux") || name.contains("ui") || name.contains("design") || 
            name.contains("designer") || name.contains("produto visual")) {
            return new RoleScore("UX/Designer", 65);
        }

        // 8. Especialista de Negócio / SME -> Score 60
        if (name.contains("sme") || name.contains("subject matter expert") || name.contains("especialista") ||
            name.contains("business owner") || name.contains("analista de negócio") || name.contains("analista de negocio") ||
            name.contains("business analyst") || name.equals("ba") || name.contains("consultor") || name.contains("consultora")) {
            return new RoleScore("SME", 60);
        }

        // 9. Desenvolvedores -> Score 50
        if (name.contains("desenvolvedor") || name.contains("desenvolvedora") || name.contains("developer") || 
            name.contains("dev") || name.contains("programador") || name.contains("programadora") || 
            name.contains("engenheiro") || name.contains("engenheira") || name.contains("software") || 
            name.contains("analista") || name.contains("assignee") || name.contains("responsável") || 
            name.contains("responsavel")) {
            return new RoleScore("Developer", 50);
        }

        return new RoleScore("Developer", 10);
    }

    private static class RoleScore {
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

        MemberCandidate(String accountId, String displayName, String email, String role, int score) {
            this.accountId = accountId;
            this.displayName = displayName;
            this.email = email;
            this.role = role;
            this.score = score;
        }
    }

    private static class MemberInfo {
        final String accountId;
        final String displayName;
        final String email;
        MemberInfo(String accountId, String displayName, String email) {
            this.accountId = accountId;
            this.displayName = displayName;
            this.email = email;
        }
    }
}
