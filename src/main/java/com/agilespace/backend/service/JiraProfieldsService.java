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
import jakarta.annotation.PostConstruct;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void initDefaultProjectIfEmpty() {
        try {
            if (projectConfigRepository.count() == 0) {
                log.info("Inicializando projeto padrão de Governança Profields: DDWMISSI");
                syncProjectFromProfields(null, "DDWMISSI", null);
            }
        } catch (Exception e) {
            log.warn("Aviso na auto-inicialização do projeto Profields: {}", e.getMessage());
        }
    }

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
        String cleanDomain = (domain != null && !domain.isBlank()) 
                ? domain.trim().replace("https://", "").replace("http://", "") 
                : "jira.empresa.com.br";
        String cleanKey = projectKey.trim().toUpperCase();

        log.info("Iniciando sincronização Profields para projeto {} no domínio {}", cleanKey, cleanDomain);

        JsonNode rootNode = null;
        if (token != null && !token.isBlank()) {
            try {
                RestTemplate restTemplate = createSslLenientRestTemplate();
                HttpHeaders headers = new HttpHeaders();
                headers.set("Authorization", "Bearer " + token.trim());
                headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
                headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AgileSpace/1.0");
                HttpEntity<Void> entity = new HttpEntity<>(headers);

                String url = "https://" + cleanDomain + "/rest/profields/api/2.0/layouts/projects/" + cleanKey + "?expand=predefined";
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    rootNode = objectMapper.readTree(response.getBody());
                }
            } catch (Exception e) {
                log.warn("Não foi possível obter dados reais do Profields ({}), utilizando parser de fallback/mock.", e.getMessage());
            }
        }

        // Se não obteve dados da API remota ou está sem token, cria/retorna o modelo populado (ex: DDWMISSI)
        ProjectConfig project;
        List<ProjectMemberRole> members = new ArrayList<>();

        if (rootNode != null) {
            project = parseProfieldsJson(cleanKey, rootNode);
            members = parseProfieldsMembers(cleanKey, rootNode);
        } else {
            project = getOrCreateDefaultProjectConfig(cleanKey);
            members = getOrCreateDefaultMembers(cleanKey);
        }

        // Salva Projeto
        project = projectConfigRepository.save(project);

        // Atualiza Membros
        projectMemberRoleRepository.deleteByProjectId(cleanKey);
        projectMemberRoleRepository.flush(); // Garante que a exclusão ocorreu antes do insert

        for (ProjectMemberRole m : members) {
            // Tenta vincular com usuário do banco se já existir
            if (m.getEmail() != null && !m.getEmail().isBlank()) {
                Optional<User> existingUser = userRepository.findByEmail(m.getEmail());
                existingUser.ifPresent(u -> m.setUserId(u.getId()));
            }
        }
        
        members = projectMemberRoleRepository.saveAll(members);
        projectMemberRoleRepository.flush(); // Força o insert imediato

        return toDetailDto(project, members);
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

        // 1. Informações Gerais
        if (fieldMap.containsKey("Segmento Projeto")) {
            project.setSegmentName(extractTextValue(fieldMap.get("Segmento Projeto")));
        }
        if (fieldMap.containsKey("Localidade")) {
            project.setLocality(extractTextValue(fieldMap.get("Localidade")));
        }
        if (fieldMap.containsKey("Tribo")) {
            project.setTribeName(extractTextValue(fieldMap.get("Tribo")));
        }
        if (fieldMap.containsKey("Vice-Presidente")) {
            project.setVicePresident(extractUserOrText(fieldMap.get("Vice-Presidente")));
        }
        if (fieldMap.containsKey("VP")) {
            project.setVpArea(extractTextValue(fieldMap.get("VP")));
        }

        // 2. Status e Números
        if (fieldMap.containsKey("Dev Team") || fieldMap.containsKey("Número de Pessoas no DevTeam")) {
            JsonNode devTeamNode = fieldMap.getOrDefault("Dev Team", fieldMap.get("Número de Pessoas no DevTeam"));
            project.setDevTeamSize(extractNumericValue(devTeamNode));
        }
        if (fieldMap.containsKey("Status Projeto") || fieldMap.containsKey("Status")) {
            JsonNode statusNode = fieldMap.getOrDefault("Status Projeto", fieldMap.get("Status"));
            project.setStatus(extractTextValue(statusNode));
        }
        if (fieldMap.containsKey("Data de Criação")) {
            project.setCreationDate(extractTextValue(fieldMap.get("Data de Criação")));
        }

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

            if (fieldMap.containsKey(roleName)) {
                JsonNode valNode = fieldMap.get(roleName);
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

    private void collectFields(JsonNode node, Map<String, JsonNode> fieldMap) {
        if (node == null) return;
        if (node.has("name") && (node.has("value") || node.has("values") || node.has("text"))) {
            fieldMap.put(node.get("name").asText().trim(), node);
        }
        if (node.has("label") && (node.has("value") || node.has("values"))) {
            fieldMap.put(node.get("label").asText().trim(), node);
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

    private String extractTextValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.has("value")) {
            JsonNode v = node.get("value");
            if (v.isTextual()) return v.asText();
            if (v.has("name")) return v.get("name").asText();
            if (v.has("value")) return v.get("value").asText();
        }
        if (node.has("text")) return node.get("text").asText();
        if (node.isTextual()) return node.asText();
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

    /**
     * Cria a configuração padrão / mock baseada na imagem real enviada da TOTVS (DDWMISSI).
     */
    private ProjectConfig getOrCreateDefaultProjectConfig(String projectKey) {
        return projectConfigRepository.findById(projectKey).orElseGet(() ->
            ProjectConfig.builder()
                    .id(projectKey)
                    .name(projectKey)
                    .segmentName("Distribuição")
                    .tribeName("Distribuição")
                    .locality("Goiânia")
                    .vicePresident("Diretoria de Operações")
                    .vpArea("Engenharia & Produtos")
                    .devTeamSize(12)
                    .status("EM ANDAMENTO")
                    .creationDate("19/06/24")
                    .autoTdnDoc(false)
                    .disableAutoSubtasks(true)
                    .specificSubtasks("Sub-tarefas de Desenvolvimento e Testes Automatizados")
                    .saasExpedition(false)
                    .engineeringOnlyExpedition(false)
                    .optionalWorklog(false)
                    .build()
        );
    }

    /**
     * Cria os membros e lideranças oficiais baseados no layout do projeto da imagem.
     */
    private List<ProjectMemberRole> getOrCreateDefaultMembers(String projectKey) {
        List<ProjectMemberRole> list = new ArrayList<>();
        list.add(ProjectMemberRole.builder()
                .projectId(projectKey)
                .roleName("Agile Master")
                .roleKey("AGILE_MASTER")
                .jiraAccountId("marielen.leite")
                .displayName("Marielen Cristine de Almeida Leite")
                .email("marielen.leite@empresa.com.br")
                .isLeadership(true)
                .build());

        list.add(ProjectMemberRole.builder()
                .projectId(projectKey)
                .roleName("Agile Coach")
                .roleKey("AGILE_COACH")
                .jiraAccountId("kamila.santos")
                .displayName("Kamila Silveira dos Santos")
                .email("kamila.santos@empresa.com.br")
                .isLeadership(true)
                .build());

        list.add(ProjectMemberRole.builder()
                .projectId(projectKey)
                .roleName("Product Owner")
                .roleKey("PRODUCT_OWNER")
                .jiraAccountId("caio.almeida")
                .displayName("Caio Fabio Tavares Almeida")
                .email("caio.almeida@empresa.com.br")
                .isLeadership(true)
                .build());

        list.add(ProjectMemberRole.builder()
                .projectId(projectKey)
                .roleName("Product Manager")
                .roleKey("PRODUCT_MANAGER")
                .jiraAccountId("alvario.junior")
                .displayName("Alvario de Matos E Silva Junior")
                .email("alvario.junior@empresa.com.br")
                .isLeadership(true)
                .build());

        list.add(ProjectMemberRole.builder()
                .projectId(projectKey)
                .roleName("People Lead")
                .roleKey("PEOPLE_LEAD")
                .jiraAccountId("guilherme.marques")
                .displayName("Guilherme Silva Marques")
                .email("guilherme.marques@empresa.com.br")
                .isLeadership(true)
                .build());

        list.add(ProjectMemberRole.builder()
                .projectId(projectKey)
                .roleName("Tribe Lead")
                .roleKey("TRIBE_LEAD")
                .jiraAccountId("silas.lopes")
                .displayName("Silas Henrique de Oliveira Lopes")
                .email("silas.lopes@empresa.com.br")
                .isLeadership(true)
                .build());

        return list;
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
