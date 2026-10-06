package com.agilespace.backend.service;

import com.agilespace.backend.domain.ProjectConfig;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.ProjectDetailDto;
import com.agilespace.backend.dto.ProjectMemberRoleDto;
import com.agilespace.backend.dto.SegmentHierarchyDto;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class JiraProfieldsService {

    private final ProjectConfigRepository projectConfigRepository;
    private final ProjectMemberRoleRepository projectMemberRoleRepository;
    private final UserRepository userRepository;
    private final JiraAdminService jiraAdminService;
    private final JiraService jiraService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private RestTemplate createSslLenientRestTemplate() {
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
                        log.error("Erro ao configurar SSL leniente para Profields", e);
                    }
                }
                super.prepareConnection(connection, httpMethod);
            }
        };
        factory.setConnectTimeout(15000);
        factory.setReadTimeout(45000);
        return new RestTemplate(factory);
    }

    /**
     * Sincroniza o projeto a partir da API Profields do Jira.
     * Endpoint: https://{domain}/rest/profields/api/2.0/layouts/projects/{projectKey}?expand=predefined
     */
    @Transactional
    public ProjectDetailDto syncProjectFromProfields(String domain, String projectKey, String token) {
        return syncProjectFromProfields(domain, projectKey, token, null);
    }

    /**
     * Sobrecarga que recebe quem disparou a sincronização. Garante que esse usuário sempre saia
     * com um vínculo no projeto (fallback Agile Master) mesmo que seu e-mail não bata com nenhum
     * membro retornado pelo Profields — sem isso ele fica sem projeto associado e trava no
     * onboarding, já que o sync não sabe reconciliar "quem está criando" com "quem o Jira retornou".
     */
    @Transactional
    public ProjectDetailDto syncProjectFromProfields(String domain, String projectKey, String token, User creator) {
        ProfieldsSnapshot snapshot = fetchProfieldsSnapshot(domain, projectKey, token, creator);
        String cleanKey = snapshot.project().getId();

        // Salva Projeto
        ProjectConfig project = projectConfigRepository.save(snapshot.project());

        // Atualiza Membros
        projectMemberRoleRepository.deleteByProjectId(cleanKey);
        projectMemberRoleRepository.flush(); // Garante que a exclusão ocorreu antes do insert

        List<ProjectMemberRole> members = projectMemberRoleRepository.saveAll(snapshot.members());
        projectMemberRoleRepository.flush(); // Força o insert imediato

        return toDetailDto(project, members);
    }


    /** Cargos sem governança: qualquer um pode ser escolhido na prévia da importação. */
    private static final Set<String> SELF_ASSIGNABLE_ROLES = Set.of(
            "DEVELOPER", "DESENVOLVEDOR(A)", "QA", "ANALISTA DE QA", "DESIGNER", "UX", "SME", "STAKEHOLDER / OBSERVADOR");

    /**
     * Confirma a importação com as escolhas do usuário na prévia. Reconsulta o Jira (fonte de verdade) e só aceita:
     * (1) editar campos do projeto; (2) escolher QUAIS pessoas entram — somente pessoas que o Jira devolveu;
     * (3) trocar o cargo para um cargo sem governança, ou manter exatamente o cargo de liderança que o Jira trouxe
     * (ninguém se promove a liderança pela prévia); (4) marcar "sou eu" numa pessoa ainda sem conta vinculada.
     * Quem importa e não se selecionou entra como Developer, nunca como liderança.
     */
    @Transactional
    public ProjectDetailDto confirmImportFromProfields(String domain, String projectKey, String token,
                                                       com.agilespace.backend.dto.ProjectImportConfirmRequest request, User creator) {
        if (request == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Dados da importação ausentes");
        }
        ProfieldsSnapshot base = fetchProfieldsSnapshot(domain, projectKey, token, creator, false);
        ProfieldsSnapshot applied = applyConfirmation(base, request, creator);
        String cleanKey = applied.project().getId();
        ProjectConfig project = applied.project();
        List<ProjectMemberRole> chosen = applied.members();

        ProjectConfig saved = projectConfigRepository.save(project);
        projectMemberRoleRepository.deleteByProjectId(cleanKey);
        projectMemberRoleRepository.flush();
        List<ProjectMemberRole> members = projectMemberRoleRepository.saveAll(chosen);
        projectMemberRoleRepository.flush();
        return toDetailDto(saved, members);
    }


    /** Parte pura da confirmação: aplica edições e seleção sobre o que o Jira devolveu. Sem acesso a banco ou rede. */
    ProfieldsSnapshot applyConfirmation(ProfieldsSnapshot snapshot, com.agilespace.backend.dto.ProjectImportConfirmRequest request, User creator) {
        String cleanKey = snapshot.project().getId();
        ProjectConfig project = snapshot.project();

        // Campos editados na prévia (vazio = não informado)
        project.setSegmentName(blankToNull(request.getSegmentName()));
        project.setTribeName(blankToNull(request.getTribeName()));
        project.setLocality(blankToNull(request.getLocality()));
        project.setVicePresident(blankToNull(request.getVicePresident()));
        project.setVpArea(blankToNull(request.getVpArea()));
        project.setStatus(blankToNull(request.getStatus()));
        project.setCreationDate(blankToNull(request.getCreationDate()));
        if (request.getName() != null && !request.getName().isBlank()) {
            project.setName(request.getName().trim());
        }

        Map<String, ProjectMemberRole> byAccount = new HashMap<>();
        Map<String, ProjectMemberRole> byEmail = new HashMap<>();
        for (ProjectMemberRole m : snapshot.members()) {
            if (m.getJiraAccountId() != null && !m.getJiraAccountId().isBlank()) byAccount.putIfAbsent(m.getJiraAccountId(), m);
            if (m.getEmail() != null && !m.getEmail().isBlank()) byEmail.putIfAbsent(m.getEmail().toLowerCase(java.util.Locale.ROOT), m);
        }

        List<ProjectMemberRole> chosen = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        boolean creatorLinked = false;
        for (com.agilespace.backend.dto.ProjectImportConfirmRequest.Member req :
                request.getMembers() == null ? List.<com.agilespace.backend.dto.ProjectImportConfirmRequest.Member>of() : request.getMembers()) {
            ProjectMemberRole base = req.getJiraAccountId() != null ? byAccount.get(req.getJiraAccountId()) : null;
            if (base == null && req.getEmail() != null) base = byEmail.get(req.getEmail().toLowerCase(java.util.Locale.ROOT));
            if (base == null) continue; // pessoa que o Jira não devolveu: não entra
            String dedupe = base.getJiraAccountId() != null ? base.getJiraAccountId() : String.valueOf(base.getEmail());
            if (!taken.add(dedupe)) continue;

            String requestedRole = req.getRoleName() == null ? "" : req.getRoleName().trim();
            String finalRole = base.getRoleName();
            if (!requestedRole.isEmpty()) {
                boolean sameAsJira = requestedRole.equalsIgnoreCase(base.getRoleName());
                boolean selfAssignable = SELF_ASSIGNABLE_ROLES.contains(requestedRole.toUpperCase(java.util.Locale.ROOT));
                if (sameAsJira || selfAssignable) finalRole = requestedRole;
            }
            String upper = finalRole.toUpperCase(java.util.Locale.ROOT);
            String userId = base.getUserId();
            if (req.isLinkToMe() && creator != null && (userId == null || userId.equals(creator.getId()))) {
                userId = creator.getId();
            }
            if (creator != null && creator.getId().equals(userId)) creatorLinked = true;

            chosen.add(ProjectMemberRole.builder()
                    .projectId(cleanKey)
                    .roleName(finalRole)
                    .roleKey(upper.replaceAll("[^A-Z0-9]+", "_"))
                    .jiraAccountId(base.getJiraAccountId())
                    .displayName(base.getDisplayName())
                    .email(base.getEmail())
                    .avatarUrl(base.getAvatarUrl())
                    .userId(userId)
                    .isLeadership(LEADERSHIP_ROLE_NAMES.contains(upper))
                    .build());
        }

        if (creator != null && !creatorLinked) {
            chosen.add(ProjectMemberRole.builder()
                    .projectId(cleanKey)
                    .roleName("Developer")
                    .roleKey("DEVELOPER")
                    .jiraAccountId(creator.getJiraAccountId())
                    .displayName(creator.getName())
                    .email(creator.getEmail())
                    .userId(creator.getId())
                    .isLeadership(false)
                    .build());
        }

        // "Pessoas no time" não é digitado: é quantas pessoas sem cargo de liderança entram.
        project.setDevTeamSize((int) chosen.stream().filter(m -> !m.isLeadership()).count());
        return new ProfieldsSnapshot(project, chosen);
    }

    /**
     * Dry-run do sync: consulta o Profields, monta projeto + membros (inclusive vínculo com usuários
     * já cadastrados e fallback Agile Master do criador) e devolve o DTO SEM gravar nada.
     * Usado pelo onboarding pra o usuário conferir o que vai entrar antes de confirmar.
     */
    @Transactional(readOnly = true)
    public ProjectDetailDto previewProjectFromProfields(String domain, String projectKey, String token, User creator) {
        ProfieldsSnapshot snapshot = fetchProfieldsSnapshot(domain, projectKey, token, creator);
        return toDetailDto(snapshot.project(), snapshot.members());
    }

    record ProfieldsSnapshot(ProjectConfig project, List<ProjectMemberRole> members) {}

    /**
     * Busca o projeto no Profields e monta as entidades em memória. Não persiste.
     */
    private ProfieldsSnapshot fetchProfieldsSnapshot(String domain, String projectKey, String token, User creator) {
        return fetchProfieldsSnapshot(domain, projectKey, token, creator, true);
    }

    private ProfieldsSnapshot fetchProfieldsSnapshot(String domain, String projectKey, String token, User creator, boolean creatorFallback) {
        if (token == null || token.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Token do Jira é obrigatório para sincronizar o projeto");
        }

        String cleanDomain = (domain != null && !domain.isBlank())
                ? domain.trim().replace("https://", "").replace("http://", "")
                : "jira.empresa.com.br";
        String cleanKey = projectKey.trim().toUpperCase();

        log.info("Consultando Profields para projeto {} no domínio {}", cleanKey, cleanDomain);

        JsonNode rootNode;
        String jiraProjectName = null;
        try {
            RestTemplate restTemplate = createSslLenientRestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + token.trim());
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AgileSpace/1.0");
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            String url = "https://" + cleanDomain + "/rest/profields/api/2.0/layouts/projects/" + cleanKey + "?expand=predefined";
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "Jira Profields retornou resposta inválida para o projeto " + cleanKey);
            }
            rootNode = objectMapper.readTree(response.getBody());
            jiraProjectName = fetchJiraProjectName(restTemplate, entity, cleanDomain, cleanKey);
            JsonNode valuesNode = fetchProfieldsValues(restTemplate, entity, cleanDomain, cleanKey);
            if (valuesNode != null) {
                log.info("Profields {}: forma da resposta de valores: {}", cleanKey, describeShapeWithTypes(valuesNode));
                rootNode = toSyntheticFields(rootNode, valuesNode);
            }
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Falha ao consultar projeto {} no Profields: {}", cleanKey, e.getMessage());
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Não foi possível obter dados do Profields para o projeto " + cleanKey + ": " + e.getMessage(), e);
        }

        ProjectConfig project = parseProfieldsJson(cleanKey, rootNode);
        // O Jira guarda o nome do projeto ("Projeto X - Y") separado da chave (DDWMISSI). Só sobrescreve se o
        // nome atual ainda é a própria chave (ou vazio): não desfaz um nome que alguém já ajustou no sistema.
        if (jiraProjectName != null && !jiraProjectName.isBlank()
                && (project.getName() == null || project.getName().isBlank() || project.getName().equalsIgnoreCase(cleanKey))) {
            project.setName(jiraProjectName.trim());
        }
        List<ProjectMemberRole> members = parseProfieldsMembers(cleanKey, rootNode);
        if (members.isEmpty()) {
            // O layout do Profields traz só as definições dos campos (sem valores), então não há pessoas ali.
            // Usa a mesma descoberta do importador do admin: papéis do projeto, grupos, líderes e atividade recente.
            members = new ArrayList<>(discoverMembersFromJira(cleanDomain, cleanKey, token));
        }
        // Diagnóstico: só rótulos/estrutura, sem valores. Ajuda a mapear campos que o parser não reconheceu.
        Map<String, JsonNode> receivedFields = new HashMap<>();
        collectFields(rootNode, receivedFields);
        log.info("Profields {}: rotulos de campo recebidos: {}", cleanKey, receivedFields.keySet());
        log.info("Profields {}: estrutura recebida: {}", cleanKey, describeStructure(rootNode));
        log.info("Profields {}: segmento={} tribo={} localidade={} vp={} status={} devTeam={} membros={}",
                cleanKey, project.getSegmentName() != null, project.getTribeName() != null,
                project.getLocality() != null, project.getVpArea() != null, project.getStatus() != null,
                project.getDevTeamSize(), members.size());

        members = dedupeMembers(members);
        embedAvatars(members, cleanDomain, token);

        for (ProjectMemberRole m : members) {
            // Tenta vincular com usuário do banco se já existir
            if (m.getEmail() != null && !m.getEmail().isBlank()) {
                Optional<User> existingUser = userRepository.findByEmail(m.getEmail());
                existingUser.ifPresent(u -> m.setUserId(u.getId()));
            }
        }

        if (creatorFallback && creator != null && members.stream().noneMatch(m -> creator.getId().equals(m.getUserId()))) {
            log.warn("Usuário {} ({}) disparou o sync do projeto {} mas não bateu com nenhum membro retornado pelo Profields — vinculando como Agile Master fallback pra não travar o onboarding.",
                    creator.getId(), creator.getEmail(), cleanKey);
            members.add(ProjectMemberRole.builder()
                    .projectId(cleanKey)
                    .roleName("Agile Master")
                    .roleKey("AGILE_MASTER")
                    .jiraAccountId(creator.getJiraAccountId())
                    .displayName(creator.getName())
                    .email(creator.getEmail())
                    .userId(creator.getId())
                    .isLeadership(true)
                    .build());
        }

        return new ProfieldsSnapshot(project, members);
    }

    /**
     * Retorna os detalhes de um projeto cadastrado.
     */
    @Transactional(readOnly = true)
    public Optional<ProjectDetailDto> getProjectDetails(String projectKey) {
        String key = projectKey.trim().toUpperCase();
        return projectConfigRepository.findById(key)
                .map(project -> {
                    List<ProjectMemberRole> members = projectMemberRoleRepository.findByProjectId(key);
                    return toDetailDto(project, members);
                });
    }

    /**
     * Lista todos os projetos cadastrados.
     */
    @Transactional(readOnly = true)
    public List<ProjectDetailDto> getAllProjects() {
        return projectConfigRepository.findAllByOrderBySegmentNameAscNameAsc().stream()
                .map(project -> {
                    List<ProjectMemberRole> members = projectMemberRoleRepository.findByProjectId(project.getId());
                    return toDetailDto(project, members);
                })
                .collect(Collectors.toList());
    }

    /**
     * Retorna a hierarquia completa: Segmento -> Tribos -> Projetos.
     */
    @Transactional(readOnly = true)
    public List<SegmentHierarchyDto> getSegmentHierarchy() {
        List<ProjectConfig> all = projectConfigRepository.findAllByOrderBySegmentNameAscNameAsc();

        // Agrupa por Segmento
        Map<String, List<ProjectConfig>> bySegment = all.stream()
                .collect(Collectors.groupingBy(
                        p -> (p.getSegmentName() != null && !p.getSegmentName().isBlank()) ? p.getSegmentName() : "Segmento Geral",
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<SegmentHierarchyDto> hierarchy = new ArrayList<>();

        for (Map.Entry<String, List<ProjectConfig>> segEntry : bySegment.entrySet()) {
            String segmentName = segEntry.getKey();
            List<ProjectConfig> segProjects = segEntry.getValue();

            // Agrupa por Tribo
            Map<String, List<ProjectConfig>> byTribe = segProjects.stream()
                    .collect(Collectors.groupingBy(
                            p -> (p.getTribeName() != null && !p.getTribeName().isBlank()) ? p.getTribeName() : "Geral",
                            LinkedHashMap::new,
                            Collectors.toList()
                    ));

            List<SegmentHierarchyDto.TribeGroupDto> tribeGroups = new ArrayList<>();
            for (Map.Entry<String, List<ProjectConfig>> tribeEntry : byTribe.entrySet()) {
                String tribeName = tribeEntry.getKey();
                List<SegmentHierarchyDto.ProjectSummaryDto> projectSummaries = tribeEntry.getValue().stream()
                        .map(p -> {
                            int totalLeaders = (int) projectMemberRoleRepository.findByProjectId(p.getId()).stream()
                                    .filter(ProjectMemberRole::isLeadership)
                                    .count();
                            return SegmentHierarchyDto.ProjectSummaryDto.builder()
                                    .id(p.getId())
                                    .name(p.getName())
                                    .status(p.getStatus())
                                    .devTeamSize(p.getDevTeamSize())
                                    .locality(p.getLocality())
                                    .totalLeaders(totalLeaders)
                                    .build();
                        })
                        .collect(Collectors.toList());

                tribeGroups.add(SegmentHierarchyDto.TribeGroupDto.builder()
                        .tribeName(tribeName)
                        .projects(projectSummaries)
                        .build());
            }

            hierarchy.add(SegmentHierarchyDto.builder()
                    .segmentName(segmentName)
                    .tribes(tribeGroups)
                    .build());
        }

        return hierarchy;
    }

    /**
     * Parser do JSON do Profields para extrair os dados gerais e regras de fluxo.
     */
    private ProjectConfig parseProfieldsJson(String projectKey, JsonNode root) {
        ProjectConfig project = projectConfigRepository.findById(projectKey)
                .orElse(ProjectConfig.builder().id(projectKey).name(projectKey).build());

        project.setProfieldsRawJson(root.toString());

        // Profields Layout contém "fields" ou "sections"
        Map<String, JsonNode> fieldMap = new HashMap<>();
        collectFields(root, fieldMap);
        Map<String, JsonNode> norm = normalizeKeys(fieldMap);

        // 1. Informações Gerais (rótulos comparados sem acento/caixa/pontuação, com sinônimos)
        JsonNode segment = findField(norm, "Segmento Projeto", "Segmento do Projeto", "Segmento");
        if (segment != null) project.setSegmentName(extractTextValue(segment));
        JsonNode locality = findField(norm, "Localidade", "Localização", "Local");
        if (locality != null) project.setLocality(extractTextValue(locality));
        JsonNode tribe = findField(norm, "Tribo", "Tribe", "Tribo do Projeto");
        if (tribe != null) project.setTribeName(extractTextValue(tribe));
        JsonNode vicePresident = findField(norm, "Vice-Presidente", "Vice Presidente", "Vice-Presidência");
        if (vicePresident != null) project.setVicePresident(extractUserOrText(vicePresident));
        JsonNode vpArea = findField(norm, "VP", "Área VP", "Area do VP", "VP Área");
        if (vpArea != null) project.setVpArea(extractTextValue(vpArea));

        // 2. Status e Números
        JsonNode devTeamNode = findField(norm, "Dev Team", "Número de Pessoas no DevTeam", "Numero de Pessoas no Dev Team", "DevTeam");
        if (devTeamNode != null) project.setDevTeamSize(extractNumericValue(devTeamNode));
        JsonNode statusNode = findField(norm, "Status Projeto", "Status do Projeto", "Status");
        if (statusNode != null) project.setStatus(extractTextValue(statusNode));
        JsonNode created = findField(norm, "Data de Criação", "Data Criação", "Criado em");
        if (created != null) project.setCreationDate(extractTextValue(created));

        // 3. Campos de Validações de Fluxo
        if (fieldMap.containsKey("Documentação Automática TDN")) {
            project.setAutoTdnDoc(extractBooleanValue(fieldMap.get("Documentação Automática TDN")));
        }
        if (fieldMap.containsKey("Desativar Sub-tarefa Automática")) {
            project.setDisableAutoSubtasks(extractBooleanValue(fieldMap.get("Desativar Sub-tarefa Automática")));
        }
        if (fieldMap.containsKey("Cria sub-tarefa específica")) {
            project.setSpecificSubtasks(extractTextValue(fieldMap.get("Cria sub-tarefa específica")));
        }
        if (fieldMap.containsKey("Expedição SAAS")) {
            project.setSaasExpedition(extractBooleanValue(fieldMap.get("Expedição SAAS")));
        }
        if (fieldMap.containsKey("Expedição Exclusiva Engenharia")) {
            project.setEngineeringOnlyExpedition(extractBooleanValue(fieldMap.get("Expedição Exclusiva Engenharia")));
        }
        if (fieldMap.containsKey("Apontamento Opcional")) {
            project.setOptionalWorklog(extractBooleanValue(fieldMap.get("Apontamento Opcional")));
        }

        return project;
    }

    /**
     * Parser para extrair as Pessoas e seus papéis no Profields.
     */
    private List<ProjectMemberRole> parseProfieldsMembers(String projectKey, JsonNode root) {
        List<ProjectMemberRole> members = new ArrayList<>();
        Map<String, JsonNode> fieldMap = new HashMap<>();
        collectFields(root, fieldMap);
        Map<String, JsonNode> norm = normalizeKeys(fieldMap);

        // Mapeamento dos papéis oficiais
        Map<String, String> roleDefinitions = new LinkedHashMap<>();
        roleDefinitions.put("Agile Master", "AGILE_MASTER");
        roleDefinitions.put("Agile Coach", "AGILE_COACH");
        roleDefinitions.put("Product Owner", "PRODUCT_OWNER");
        roleDefinitions.put("Product Manager", "PRODUCT_MANAGER");
        roleDefinitions.put("People Lead", "PEOPLE_LEAD");
        roleDefinitions.put("Tribe Lead", "TRIBE_LEAD");
        roleDefinitions.put("Team Lead", "TEAM_LEAD");
        roleDefinitions.put("Arquiteto Digital", "DIGITAL_ARCHITECT");

        for (Map.Entry<String, String> entry : roleDefinitions.entrySet()) {
            String roleName = entry.getKey();
            String roleKey = entry.getValue();

            JsonNode valNode = findField(norm, roleName, roleName + "s", roleName.replace(" ", "-"));
            if (valNode != null) {
                List<MemberExtracted> extracted = extractUsersFromNode(valNode);
                for (MemberExtracted m : extracted) {
                    members.add(ProjectMemberRole.builder()
                            .projectId(projectKey)
                            .roleName(roleName)
                            .roleKey(roleKey)
                            .jiraAccountId(m.accountId)
                            .displayName(m.displayName)
                            .email(m.email)
                            .avatarUrl(m.avatarUrl)
                            .isLeadership(true)
                            .build());
                }
            }
        }

        return members;
    }



    /**
     * Descobre as pessoas do time pela API padrão do Jira (mesma lógica do importador do admin) e converte em
     * papéis de projeto. Falha silenciosa: sem pessoas, o chamador cai no fallback de Agile Master.
     */
    private List<ProjectMemberRole> discoverMembersFromJira(String domain, String projectKey, String token) {
        try {
            com.agilespace.backend.dto.JiraSyncRequest request = new com.agilespace.backend.dto.JiraSyncRequest();
            request.setProjectKey(projectKey);
            request.setJiraDomain(domain);
            request.setToken(token.trim());
            com.agilespace.backend.dto.JiraProjectPreviewDto preview = jiraAdminService.previewProject(request);
            List<ProjectMemberRole> found = new ArrayList<>();
            if (preview != null && preview.getMembers() != null) {
                for (com.agilespace.backend.dto.JiraMemberCandidateDto c : preview.getMembers()) {
                    String role = (c.getRole() == null || c.getRole().isBlank()) ? "Developer" : c.getRole();
                    found.add(ProjectMemberRole.builder()
                            .projectId(projectKey)
                            .roleName(role)
                            .roleKey(role.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]+", "_"))
                            .jiraAccountId(c.getJiraAccountId())
                            .displayName(c.getDisplayName())
                            .email(c.getEmail())
                            .avatarUrl(c.getAvatarUrl())
                            .isLeadership(LEADERSHIP_ROLE_NAMES.contains(role.toUpperCase(java.util.Locale.ROOT)))
                            .build());
                }
            }
            log.info("Profields {}: {} pessoa(s) descobertas pela API padrão do Jira", projectKey, found.size());
            return found;
        } catch (Exception e) {
            log.warn("Profields {}: não foi possível descobrir pessoas pela API padrão do Jira: {}", projectKey, e.getMessage());
            return Collections.emptyList();
        }
    }




    /** Foto maior que isso é descartada (o 48x48 do Jira costuma ter poucos KB). */
    static final int MAX_AVATAR_BYTES = 40_000;

    /**
     * Converte a imagem baixada do Jira em data URI. Null se não for imagem ou passar do limite de tamanho.
     */
    static String toDataUri(byte[] body, org.springframework.http.MediaType contentType) {
        if (body == null || body.length == 0 || body.length > MAX_AVATAR_BYTES) return null;
        if (contentType == null || !"image".equals(contentType.getType())) return null;
        return "data:" + contentType.getType() + "/" + contentType.getSubtype() + ";base64,"
                + java.util.Base64.getEncoder().encodeToString(body);
    }

    /**
     * A foto do Jira só abre com login (o navegador não consegue carregar a URL direto). Baixa cada uma com o token de
     * quem está importando e guarda como imagem embutida, para todo o time ver sem precisar de token. Em paralelo e
     * com limite de tempo; quem falhar fica sem foto (iniciais).
     */
    private void embedAvatars(List<ProjectMemberRole> members, String domain, String token) {
        List<ProjectMemberRole> withUrl = members.stream()
                .filter(m -> !isBlank(m.getAvatarUrl()) && m.getAvatarUrl().startsWith("http"))
                .toList();
        // Quem não tem URL válida não deve ficar com um endereço que o navegador não consegue abrir.
        members.stream().filter(m -> !isBlank(m.getAvatarUrl()) && !m.getAvatarUrl().startsWith("http") && !m.getAvatarUrl().startsWith("data:"))
                .forEach(m -> m.setAvatarUrl(null));
        if (withUrl.isEmpty()) return;

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.min(8, withUrl.size()));
        try {
            List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
            for (ProjectMemberRole m : withUrl) {
                String url = m.getAvatarUrl();
                futures.add(pool.submit(() -> fetchAvatarDataUri(domain, token, url)));
            }
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
            for (int i = 0; i < withUrl.size(); i++) {
                String dataUri = null;
                try {
                    long remaining = Math.max(1, deadline - System.nanoTime());
                    dataUri = futures.get(i).get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
                } catch (Exception ignored) {
                    futures.get(i).cancel(true);
                }
                withUrl.get(i).setAvatarUrl(dataUri);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private String fetchAvatarDataUri(String domain, String token, String url) {
        try {
            ResponseEntity<?> r = jiraService.getAttachment(domain, token, url);
            if (r.getStatusCode().is2xxSuccessful() && r.getBody() instanceof byte[] bytes) {
                return toDataUri(bytes, r.getHeaders().getContentType());
            }
        } catch (Exception e) {
            log.debug("Avatar não baixado: {}", e.getMessage());
        }
        return null;
    }

    /** Nome do projeto no Jira (campo "name" de /rest/api/2/project/{key}); null se não for possível ler. */
    private String fetchJiraProjectName(RestTemplate rt, HttpEntity<Void> entity, String domain, String key) {
        try {
            ResponseEntity<String> r = rt.exchange("https://" + domain + "/rest/api/2/project/" + key, HttpMethod.GET, entity, String.class);
            JsonNode n = objectMapper.readTree(r.getBody());
            return n.hasNonNull("name") ? n.get("name").asText() : null;
        } catch (Exception e) {
            log.warn("Profields {}: não foi possível ler o nome do projeto no Jira: {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * A mesma pessoa pode vir mais de uma vez (campo do Profields + atividade em issues, ou contas Jira com id
     * diferente e o mesmo e-mail). Junta por e-mail (sem caixa) ou nome normalizado e fica com o cargo de maior
     * prioridade: liderança > cargo específico > Developer.
     */
    static List<ProjectMemberRole> dedupeMembers(List<ProjectMemberRole> in) {
        List<ProjectMemberRole> out = new ArrayList<>();
        for (ProjectMemberRole m : in) {
            ProjectMemberRole existing = null;
            for (ProjectMemberRole o : out) {
                if (samePerson(o, m)) { existing = o; break; }
            }
            if (existing == null) {
                out.add(m);
                continue;
            }
            ProjectMemberRole keep = rank(m) > rank(existing) ? m : existing;
            ProjectMemberRole other = keep == m ? existing : m;
            if (isBlank(keep.getEmail())) keep.setEmail(other.getEmail());
            if (isBlank(keep.getJiraAccountId())) keep.setJiraAccountId(other.getJiraAccountId());
            if (isBlank(keep.getAvatarUrl())) keep.setAvatarUrl(other.getAvatarUrl());
            if (keep.getUserId() == null) keep.setUserId(other.getUserId());
            out.set(out.indexOf(existing), keep);
        }
        return out;
    }

    private static boolean samePerson(ProjectMemberRole a, ProjectMemberRole b) {
        if (!isBlank(a.getEmail()) && !isBlank(b.getEmail()) && a.getEmail().trim().equalsIgnoreCase(b.getEmail().trim())) return true;
        if (!isBlank(a.getJiraAccountId()) && a.getJiraAccountId().equals(b.getJiraAccountId())) return true;
        String na = normalizeLabel(a.getDisplayName());
        return !na.isEmpty() && na.equals(normalizeLabel(b.getDisplayName()));
    }

    private static int rank(ProjectMemberRole m) {
        if (m.isLeadership()) return 3;
        return "DEVELOPER".equalsIgnoreCase(m.getRoleKey()) ? 1 : 2;
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    /** Endpoint de valores do Profields: GET /rest/profields/api/2.0/values/projects/{key} (lista de valores por campo). */
    private JsonNode fetchProfieldsValues(RestTemplate rt, HttpEntity<Void> entity, String domain, String key) {
        try {
            String url = "https://" + domain + "/rest/profields/api/2.0/values/projects/" + key;
            ResponseEntity<String> r = rt.exchange(url, HttpMethod.GET, entity, String.class);
            if (!r.getStatusCode().is2xxSuccessful() || r.getBody() == null) return null;
            return objectMapper.readTree(r.getBody());
        } catch (Exception e) {
            log.warn("Profields {}: não foi possível ler os valores dos campos: {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * Junta layout (definições dos campos) com valores num JSON simples [{name, value}] que o parser entende.
     * O valor é associado ao campo pelo nome embutido no próprio item ou pelo id (fieldId / field.id / customFieldId)
     * cruzado com os ids do layout.
     */
    JsonNode toSyntheticFields(JsonNode layout, JsonNode values) {
        Map<String, String> nameById = new HashMap<>();
        indexLayoutFieldIds(layout, nameById);

        com.fasterxml.jackson.databind.node.ArrayNode out = objectMapper.createArrayNode();
        collectValueItems(values, nameById, out, 0);
        return out;
    }

    private void indexLayoutFieldIds(JsonNode node, Map<String, String> nameById) {
        if (node == null) return;
        if (node.isObject()) {
            JsonNode field = node.get("field");
            if (field != null && field.isObject() && field.has("name")) {
                String name = field.get("name").asText();
                for (String idKey : List.of("id", "customFieldId")) {
                    if (field.has(idKey) && !field.get(idKey).isNull()) {
                        String id = field.get(idKey).asText();
                        nameById.putIfAbsent(id, name);
                        nameById.putIfAbsent(id.replace("customfield_", ""), name);
                    }
                }
            }
            node.fields().forEachRemaining(e -> indexLayoutFieldIds(e.getValue(), nameById));
        } else if (node.isArray()) {
            for (JsonNode c : node) indexLayoutFieldIds(c, nameById);
        }
    }

    private void collectValueItems(JsonNode node, Map<String, String> nameById, com.fasterxml.jackson.databind.node.ArrayNode out, int depth) {
        if (node == null || depth > 6) return;
        if (node.isArray()) {
            for (JsonNode c : node) collectValueItems(c, nameById, out, depth + 1);
            return;
        }
        if (!node.isObject()) return;

        JsonNode value = null;
        for (String vk : VALUE_KEYS) {
            if (node.has(vk)) { value = node.get(vk); break; }
        }
        if (value != null) {
            String label = null;
            for (String lk : LABEL_KEYS) {
                if (node.has(lk) && node.get(lk).isTextual()) { label = node.get(lk).asText(); break; }
            }
            if (label == null && node.has("field") && node.get("field").isObject() && node.get("field").has("name")) {
                label = node.get("field").get("name").asText();
            }
            if (label == null) {
                for (String idKey : List.of("fieldId", "customFieldId", "id")) {
                    if (node.has(idKey) && !node.get(idKey).isNull()) {
                        String id = node.get(idKey).asText();
                        label = nameById.getOrDefault(id, nameById.get(id.replace("customfield_", "")));
                        if (label != null) break;
                    }
                }
                if (label == null && node.has("field") && node.get("field").isObject()) {
                    JsonNode f = node.get("field");
                    for (String idKey : List.of("id", "customFieldId")) {
                        if (f.has(idKey)) {
                            String id = f.get(idKey).asText();
                            label = nameById.getOrDefault(id, nameById.get(id.replace("customfield_", "")));
                            if (label != null) break;
                        }
                    }
                }
            }
            if (label != null) {
                com.fasterxml.jackson.databind.node.ObjectNode item = objectMapper.createObjectNode();
                item.put("name", label);
                item.set("value", value);
                out.add(item);
                return;
            }
        }
        node.fields().forEachRemaining(e -> {
            if (e.getValue().isContainerNode()) collectValueItems(e.getValue(), nameById, out, depth + 1);
        });
    }

    /** Forma do JSON só com chaves e TIPOS (string/number/object/array/…), nunca conteúdo. */
    static String describeShapeWithTypes(JsonNode root) {
        Set<String> lines = new LinkedHashSet<>();
        walkShape(root, "$", lines, 0);
        String joined = String.join(" ; ", lines);
        return joined.length() > 6000 ? joined.substring(0, 6000) + "…" : joined;
    }

    private static void walkShape(JsonNode node, String path, Set<String> lines, int depth) {
        if (node == null || depth > 8) return;
        if (node.isArray()) {
            lines.add(path + "=array");
            for (JsonNode c : node) walkShape(c, path + "[]", lines, depth + 1);
        } else if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                String t = v.isTextual() ? "string" : v.isNumber() ? "number" : v.isBoolean() ? "bool" : v.isNull() ? "null" : v.isArray() ? "array" : "object";
                lines.add(path + "." + e.getKey() + "=" + t);
                if (v.isContainerNode()) walkShape(v, path + "." + e.getKey(), lines, depth + 1);
            });
        }
    }

    /** Chaves que costumam guardar o rótulo do campo no layout do Profields. */
    private static final List<String> LABEL_KEYS = List.of("name", "label", "title", "fieldName", "displayName");
    /** Chaves que costumam guardar o valor do campo. */
    private static final List<String> VALUE_KEYS = List.of("value", "values", "text", "displayValue", "selectedValue");

    private void collectFields(JsonNode node, Map<String, JsonNode> fieldMap) {
        if (node == null) return;
        if (node.isObject()) {
            boolean hasValue = VALUE_KEYS.stream().anyMatch(node::has);
            if (hasValue) {
                for (String key : LABEL_KEYS) {
                    JsonNode label = node.get(key);
                    if (label != null && label.isTextual() && !label.asText().isBlank()) {
                        fieldMap.putIfAbsent(label.asText().trim(), node);
                    }
                }
            }
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectFields(child, fieldMap);
            }
        } else if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (entry.getValue().isContainerNode()) {
                    collectFields(entry.getValue(), fieldMap);
                }
            });
        }
    }

    /** "Área do VP:" -> "areadovp": sem acento, caixa, espaço ou pontuação. */
    static String normalizeLabel(String label) {
        if (label == null) return "";
        String decomposed = java.text.Normalizer.normalize(label, java.text.Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static Map<String, JsonNode> normalizeKeys(Map<String, JsonNode> fieldMap) {
        Map<String, JsonNode> norm = new LinkedHashMap<>();
        fieldMap.forEach((label, node) -> norm.putIfAbsent(normalizeLabel(label), node));
        return norm;
    }

    private static JsonNode findField(Map<String, JsonNode> norm, String... labels) {
        for (String label : labels) {
            JsonNode node = norm.get(normalizeLabel(label));
            if (node != null) return node;
        }
        return null;
    }

    /**
     * Resumo da forma do JSON do Profields para diagnóstico: caminhos e rótulos de campo, NUNCA valores
     * (os valores podem conter nomes de pessoas). Limitado a ~4000 caracteres.
     */
    static String describeStructure(JsonNode root) {
        Set<String> paths = new LinkedHashSet<>();
        walkStructure(root, "$", paths, 0);
        String joined = String.join(" ; ", paths);
        return joined.length() > 9000 ? joined.substring(0, 9000) + "…" : joined;
    }

    private static void walkStructure(JsonNode node, String path, Set<String> paths, int depth) {
        if (node == null || depth > 20) return;
        if (node.isArray()) {
            for (JsonNode child : node) walkStructure(child, path + "[]", paths, depth + 1);
        } else if (node.isObject()) {
            for (String key : LABEL_KEYS) {
                JsonNode label = node.get(key);
                if (label != null && label.isTextual()) {
                    paths.add(path + "{" + key + "=" + label.asText() + ", chaves=" + keyNames(node) + "}");
                }
            }
            node.fields().forEachRemaining(e -> {
                if (e.getValue().isContainerNode()) walkStructure(e.getValue(), path + "." + e.getKey(), paths, depth + 1);
            });
        }
    }

    private static String keyNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names.toString();
    }

    private String extractTextValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        for (String key : VALUE_KEYS) {
            if (!node.has(key)) continue;
            String text = textOf(node.get(key));
            if (text != null && !text.isBlank()) return text;
        }
        return node.isTextual() ? node.asText() : null;
    }

    /** Texto de um valor que pode ser string, número, objeto de opção ({name|label|value|displayName}) ou lista deles. */
    private String textOf(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isTextual() || v.isNumber() || v.isBoolean()) return v.asText();
        if (v.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode item : v) {
                String t = textOf(item);
                if (t != null && !t.isBlank()) parts.add(t);
            }
            return parts.isEmpty() ? null : String.join(", ", parts);
        }
        if (v.isObject()) {
            for (String key : List.of("name", "label", "value", "displayName", "title", "text")) {
                if (v.has(key)) {
                    String t = textOf(v.get(key));
                    if (t != null && !t.isBlank()) return t;
                }
            }
        }
        return null;
    }

    private String extractUserOrText(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.has("value")) {
            JsonNode v = node.get("value");
            if (v.has("displayName")) return v.get("displayName").asText();
            if (v.has("name")) return v.get("name").asText();
            if (v.isTextual()) return v.asText();
        }
        return extractTextValue(node);
    }

    private Integer extractNumericValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.has("value")) {
            JsonNode v = node.get("value");
            if (v.isNumber()) return v.asInt();
            try {
                return Integer.parseInt(v.asText().replaceAll("[^0-9]", ""));
            } catch (Exception ignored) {}
        }
        if (node.isNumber()) return node.asInt();
        return null;
    }

    private Boolean extractBooleanValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String text = extractTextValue(node);
        if (text != null) {
            String lower = text.trim().toLowerCase();
            return lower.equals("sim") || lower.equals("true") || lower.equals("yes") || lower.equals("1");
        }
        return false;
    }

    private List<MemberExtracted> extractUsersFromNode(JsonNode node) {
        List<MemberExtracted> list = new ArrayList<>();
        if (node == null || node.isNull()) return list;

        JsonNode val = node.has("value") ? node.get("value") : node;
        if (val.isArray()) {
            for (JsonNode item : val) {
                MemberExtracted m = parseSingleUserNode(item);
                if (m != null) list.add(m);
            }
        } else {
            MemberExtracted m = parseSingleUserNode(val);
            if (m != null) list.add(m);
        }
        return list;
    }

    private MemberExtracted parseSingleUserNode(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String accountId = node.has("accountId") ? node.get("accountId").asText() : (node.has("name") ? node.get("name").asText() : null);
        String displayName = node.has("displayName") ? node.get("displayName").asText() : (node.has("name") ? node.get("name").asText() : null);
        String email = node.has("emailAddress") ? node.get("emailAddress").asText() : null;
        String avatarUrl = null;
        if (node.has("avatarUrls") && node.get("avatarUrls").has("48x48")) {
            avatarUrl = node.get("avatarUrls").get("48x48").asText();
        }

        if (displayName == null && node.isTextual()) {
            displayName = node.asText();
        }

        if (displayName != null && !displayName.isBlank()) {
            return new MemberExtracted(accountId, displayName, email, avatarUrl);
        }
        return null;
    }

    private static final Set<String> LEADERSHIP_ROLE_NAMES = Set.of(
            "AGILE MASTER", "AGILE COACH", "PRODUCT OWNER", "PRODUCT MANAGER",
            "PEOPLE LEAD", "TRIBE LEAD", "TEAM LEAD", "TECH LEAD", "SCRUM MASTER"
    );

    /**
     * Cria um projeto do zero, sem depender do Jira — para quem quer começar sem sincronizar nada.
     * O criador vira automaticamente o Agile Master do projeto recém-criado.
     */
    @Transactional
    public ProjectDetailDto createManualProject(com.agilespace.backend.dto.CreateProjectRequestDto request, User creator) {
        String key = request.getId().trim().toUpperCase();
        if (projectConfigRepository.existsById(key)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Já existe um projeto com essa chave");
        }

        ProjectConfig project = ProjectConfig.builder()
                .id(key)
                .name(request.getName().trim())
                .segmentName(blankToNull(request.getSegmentName()))
                .tribeName(blankToNull(request.getTribeName()))
                .status("EM ANDAMENTO")
                .devTeamSize(1)
                .build();
        project = projectConfigRepository.save(project);

        ProjectMemberRole founder = ProjectMemberRole.builder()
                .projectId(key)
                .roleName("Agile Master")
                .roleKey("AGILE_MASTER")
                .jiraAccountId(creator.getJiraAccountId())
                .displayName(creator.getName())
                .email(creator.getEmail())
                .userId(creator.getId())
                .isLeadership(true)
                .build();
        projectMemberRoleRepository.save(founder);

        return toDetailDto(project, List.of(founder));
    }

    /**
     * Papéis autodeclaráveis por qualquer usuário autenticado via join — contribuidor puro,
     * sem nenhum acesso de governança/liderança. Papel de liderança (ver LEADERSHIP_ROLE_NAMES)
     * só entra vinculado pelo sync real do Jira Profields ou por um admin — nunca autodeclarado,
     * porque isLeadership=true aqui já libera governança da squad, e Tribe Lead/Agile Coach/People
     * Lead especificamente disparam isTransversalLeader (acesso a toda a tribo/segmento) em
     * UserProjectResolverService. Allowlist (não blocklist) de propósito: uma variante de grafia
     * de um papel de liderança que escapasse de um blocklist ainda cai fora daqui.
     */
    private static final Set<String> SELF_SERVICE_JOIN_ROLE_NAMES = Set.of(
            "DEVELOPER", "DESENVOLVEDOR(A)",
            "QA", "ANALISTA DE QA",
            "DESIGNER",
            "UX",
            "SME",
            "STAKEHOLDER / OBSERVADOR"
    );

    /**
     * Vincula o usuário autenticado a um projeto já existente, com um papel de contribuidor —
     * fluxo de auto-atendimento pra quando alguém já configurou o projeto mas o vínculo automático
     * por e-mail não encontrou o usuário (ex: cadastro com e-mail diferente do que está no Jira).
     * Papel de liderança não é aceito aqui — ver SELF_SERVICE_JOIN_ROLE_NAMES.
     */
    @Transactional
    public void joinProject(String projectKey, String roleName, User user) {
        String key = projectKey.trim().toUpperCase();
        if (!projectConfigRepository.existsById(key)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Projeto não encontrado");
        }

        boolean alreadyMember = projectMemberRoleRepository.findByProjectId(key).stream()
                .anyMatch(m -> user.getId().equals(m.getUserId()));
        if (alreadyMember) {
            return;
        }

        String cleanRoleName = roleName.trim();
        if (!SELF_SERVICE_JOIN_ROLE_NAMES.contains(cleanRoleName.toUpperCase())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "Papéis de liderança não podem ser autodeclarados. Sincronize com o Jira ou peça pra um administrador vincular seu papel.");
        }
        String roleKey = cleanRoleName.toUpperCase().replace(" ", "_").replaceAll("[^A-Z_]", "");

        ProjectMemberRole member = ProjectMemberRole.builder()
                .projectId(key)
                .roleName(cleanRoleName)
                .roleKey(roleKey)
                .jiraAccountId(user.getJiraAccountId())
                .displayName(user.getName())
                .email(user.getEmail())
                .userId(user.getId())
                .isLeadership(false)
                .build();
        projectMemberRoleRepository.save(member);
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private ProjectDetailDto toDetailDto(ProjectConfig project, List<ProjectMemberRole> members) {
        List<ProjectMemberRoleDto> memberDtos = members.stream()
                .map(m -> ProjectMemberRoleDto.builder()
                        .id(m.getId())
                        .projectId(m.getProjectId())
                        .roleName(m.getRoleName())
                        .roleKey(m.getRoleKey())
                        .jiraAccountId(m.getJiraAccountId())
                        .displayName(m.getDisplayName())
                        .email(m.getEmail())
                        .avatarUrl(m.getAvatarUrl())
                        .userId(m.getUserId())
                        .leadership(m.isLeadership())
                        .build())
                .collect(Collectors.toList());

        return ProjectDetailDto.builder()
                .id(project.getId())
                .name(project.getName())
                .segmentName(project.getSegmentName())
                .tribeName(project.getTribeName())
                .locality(project.getLocality())
                .vicePresident(project.getVicePresident())
                .vpArea(project.getVpArea())
                .devTeamSize(project.getDevTeamSize())
                .status(project.getStatus())
                .creationDate(project.getCreationDate())
                .autoTdnDoc(project.getAutoTdnDoc())
                .disableAutoSubtasks(project.getDisableAutoSubtasks())
                .specificSubtasks(project.getSpecificSubtasks())
                .saasExpedition(project.getSaasExpedition())
                .engineeringOnlyExpedition(project.getEngineeringOnlyExpedition())
                .optionalWorklog(project.getOptionalWorklog())
                .members(memberDtos)
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .build();
    }

    private static class MemberExtracted {
        String accountId;
        String displayName;
        String email;
        String avatarUrl;

        public MemberExtracted(String accountId, String displayName, String email, String avatarUrl) {
            this.accountId = accountId;
            this.displayName = displayName;
            this.email = email;
            this.avatarUrl = avatarUrl;
        }
    }
}
