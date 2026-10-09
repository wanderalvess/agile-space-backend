package com.agilespace.backend.service;

import com.agilespace.backend.domain.Squad;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.SquadMetricsRollup;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.JiraConfirmSyncRequest;
import com.agilespace.backend.dto.JiraMemberCandidateDto;
import com.agilespace.backend.dto.JiraProjectPreviewDto;
import com.agilespace.backend.dto.JiraSyncRequest;
import com.agilespace.backend.dto.JiraSyncResult;
import com.agilespace.backend.repository.SquadMemberRepository;
import com.agilespace.backend.repository.SquadMetricsRollupRepository;
import com.agilespace.backend.repository.SquadRepository;
import com.agilespace.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
@DisplayName("JiraAdminService - Sincronização Administrativa de Squads, Membros e Rollups do Jira")
class JiraAdminServiceTest {

    @Mock private SquadRepository squadRepository;
    @Mock private SquadMemberRepository squadMemberRepository;
    @Mock private UserRepository userRepository;
    @Mock private SquadMetricsRollupRepository squadMetricsRollupRepository;

    @InjectMocks
    private JiraAdminService service;

    private JiraMemberCandidateDto candidate(String accountId, String email, boolean selected) {
        return JiraMemberCandidateDto.builder()
                .jiraAccountId(accountId)
                .displayName("User " + accountId)
                .email(email)
                .role("Developer")
                .score(50)
                .selected(selected)
                .capacityHoursPerDay(6.0)
                .build();
    }

    @Nested
    @DisplayName("Sincronização de Squads e Rollups")
    class SquadSyncTests {

        @Test
        @DisplayName("Deve criar nova squad e rollup padrão ao confirmar sincronização")
        void confirmSyncCreatesNewSquadAndDefaultRollup() {
            JiraConfirmSyncRequest request = JiraConfirmSyncRequest.builder()
                    .squadId("proj1")
                    .squadName("Projeto Um")
                    .jiraDomain("empresa.atlassian.net")
                    .replaceExisting(false)
                    .syncUsers(false)
                    .members(Collections.emptyList())
                    .build();

            when(squadRepository.findById("PROJ1")).thenReturn(Optional.empty());
            when(squadRepository.save(any(Squad.class))).thenAnswer(i -> i.getArgument(0));
            when(squadMetricsRollupRepository.findById("PROJ1")).thenReturn(Optional.empty());
            when(squadMetricsRollupRepository.save(any(SquadMetricsRollup.class))).thenAnswer(i -> i.getArgument(0));

            JiraSyncResult result = service.confirmSync(request);

            ArgumentCaptor<Squad> squadCaptor = ArgumentCaptor.forClass(Squad.class);
            verify(squadRepository).save(squadCaptor.capture());
            assertEquals("PROJ1", squadCaptor.getValue().getId());
            assertEquals("Projeto Um", squadCaptor.getValue().getName());
            assertEquals("success", squadCaptor.getValue().getLastSyncStatus());

            ArgumentCaptor<SquadMetricsRollup> rollupCaptor = ArgumentCaptor.forClass(SquadMetricsRollup.class);
            verify(squadMetricsRollupRepository).save(rollupCaptor.capture());
            assertEquals(0, rollupCaptor.getValue().getTotalIssues());
            assertEquals(10, rollupCaptor.getValue().getWorkdaysTotal());

            assertEquals("PROJ1", result.getSquadId());
            assertEquals(0, result.getMembersFound());
        }

        @Test
        @DisplayName("Deve preservar métricas do rollup pré-existente ao sincronizar novamente")
        void confirmSyncPreservesExistingRollupValues() {
            JiraConfirmSyncRequest request = JiraConfirmSyncRequest.builder()
                    .squadId("proj1")
                    .replaceExisting(false)
                    .members(Collections.emptyList())
                    .build();

            SquadMetricsRollup existingRollup = SquadMetricsRollup.builder()
                    .squadId("PROJ1")
                    .sprintName("Sprint 5")
                    .totalIssues(42)
                    .build();

            when(squadRepository.findById("PROJ1")).thenReturn(Optional.empty());
            when(squadRepository.save(any(Squad.class))).thenAnswer(i -> i.getArgument(0));
            when(squadMetricsRollupRepository.findById("PROJ1")).thenReturn(Optional.of(existingRollup));
            when(squadMetricsRollupRepository.save(any(SquadMetricsRollup.class))).thenAnswer(i -> i.getArgument(0));

            service.confirmSync(request);

            ArgumentCaptor<SquadMetricsRollup> rollupCaptor = ArgumentCaptor.forClass(SquadMetricsRollup.class);
            verify(squadMetricsRollupRepository).save(rollupCaptor.capture());
            assertEquals("Sprint 5", rollupCaptor.getValue().getSprintName());
            assertEquals(42, rollupCaptor.getValue().getTotalIssues());
        }
    }

    @Nested
    @DisplayName("Sincronização de Membros e Usuários")
    class MemberSyncTests {

        @Test
        @DisplayName("Deve salvar apenas membros selecionados na requisição de confirmação")
        void confirmSyncSavesOnlySelectedMembers() {
            JiraConfirmSyncRequest request = JiraConfirmSyncRequest.builder()
                    .squadId("proj1")
                    .replaceExisting(false)
                    .syncUsers(false)
                    .members(Arrays.asList(
                            candidate("acc1", "acc1@empresa.com", true),
                            candidate("acc2", "acc2@empresa.com", false)
                    ))
                    .build();

            when(squadRepository.findById("PROJ1")).thenReturn(Optional.empty());
            when(squadRepository.save(any(Squad.class))).thenAnswer(i -> i.getArgument(0));
            when(squadMetricsRollupRepository.findById("PROJ1")).thenReturn(Optional.empty());
            when(squadMetricsRollupRepository.save(any(SquadMetricsRollup.class))).thenAnswer(i -> i.getArgument(0));
            when(squadMemberRepository.findById(anyString())).thenReturn(Optional.empty());
            when(squadMemberRepository.save(any(SquadMember.class))).thenAnswer(i -> i.getArgument(0));

            JiraSyncResult result = service.confirmSync(request);

            assertEquals(1, result.getMembersFound());
            verify(squadMemberRepository, times(1)).save(any(SquadMember.class));
        }

        @Test
        @DisplayName("Deve desvincular membros e usuários antigos ao marcar replaceExisting")
        void confirmSyncReplaceExistingRemovesUnapprovedMembersAndUnlinksUser() {
            JiraConfirmSyncRequest request = JiraConfirmSyncRequest.builder()
                    .squadId("proj1")
                    .replaceExisting(true)
                    .syncUsers(true)
                    .members(Collections.singletonList(candidate("acc1", "acc1@empresa.com", true)))
                    .build();

            SquadMember staying = SquadMember.builder().dbId("PROJ1_acc1").squadId("PROJ1").jiraAccountId("acc1").build();
            SquadMember leaving = SquadMember.builder().dbId("PROJ1_acc2").squadId("PROJ1").jiraAccountId("acc2").displayName("Saindo").build();

            User leavingUser = User.builder().id("acc2").squadId("PROJ1").email("acc2@empresa.com").build();

            when(squadRepository.findById("PROJ1")).thenReturn(Optional.empty());
            when(squadRepository.save(any(Squad.class))).thenAnswer(i -> i.getArgument(0));
            when(squadMetricsRollupRepository.findById("PROJ1")).thenReturn(Optional.empty());
            when(squadMetricsRollupRepository.save(any(SquadMetricsRollup.class))).thenAnswer(i -> i.getArgument(0));
            when(squadMemberRepository.findBySquadIdOrderByDisplayNameAsc("PROJ1")).thenReturn(Arrays.asList(staying, leaving));
            when(userRepository.findByJiraAccountId("acc2")).thenReturn(Optional.of(leavingUser));
            when(squadMemberRepository.findById(anyString())).thenReturn(Optional.empty());
            when(squadMemberRepository.save(any(SquadMember.class))).thenAnswer(i -> i.getArgument(0));
            when(userRepository.findById("acc1")).thenReturn(Optional.empty());
            when(userRepository.findByEmail("acc1@empresa.com")).thenReturn(Optional.empty());
            when(userRepository.findByJiraAccountId("acc1")).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

            service.confirmSync(request);

            verify(squadMemberRepository).delete(leaving);
            verify(squadMemberRepository, never()).delete(staying);

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository, atLeastOnce()).save(userCaptor.capture());
            boolean unlinkedLeavingUser = userCaptor.getAllValues().stream()
                    .anyMatch(u -> "acc2".equals(u.getId()) && u.getSquadId() == null);
            assertTrue(unlinkedLeavingUser);
        }

        @Test
        @DisplayName("Deve sincronizar cargo e capacidade sem alterar a role de autorização do usuário")
        void confirmSyncUpdatesJobTitleAndCapacityWithoutTouchingAuthorizationRole() {
            JiraConfirmSyncRequest request = JiraConfirmSyncRequest.builder()
                    .squadId("proj1")
                    .replaceExisting(false)
                    .syncUsers(true)
                    .members(Collections.singletonList(candidate("acc1", "acc1@empresa.com", true)))
                    .build();

            User existingUser = User.builder().id("acc1").email("acc1@empresa.com").role("ADMIN").dailyHours(8).build();

            when(squadRepository.findById("PROJ1")).thenReturn(Optional.empty());
            when(squadRepository.save(any(Squad.class))).thenAnswer(i -> i.getArgument(0));
            when(squadMetricsRollupRepository.findById("PROJ1")).thenReturn(Optional.empty());
            when(squadMetricsRollupRepository.save(any(SquadMetricsRollup.class))).thenAnswer(i -> i.getArgument(0));
            when(squadMemberRepository.findById(anyString())).thenReturn(Optional.empty());
            when(squadMemberRepository.save(any(SquadMember.class))).thenAnswer(i -> i.getArgument(0));
            when(userRepository.findById("acc1")).thenReturn(Optional.of(existingUser));
            when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

            service.confirmSync(request);

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(userCaptor.capture());
            User updated = userCaptor.getValue();
            assertEquals("ADMIN", updated.getRole(), "Papel ADMIN não pode ser rebaixado para MEMBER");
            assertEquals("Developer", updated.getJobTitle());
            assertEquals(6, updated.getDailyHours());
            assertEquals("PROJ1", updated.getSquadId());
        }
    }

    @Nested
    @DisplayName("Preview de Projeto via API do Jira (chamadas HTTP reais mockadas)")
    class PreviewProjectTests {

        private MockRestServiceServer mockServer;
        private static final String DOMAIN = "empresa.atlassian.net";
        private static final String PROJECT_KEY = "PROJ1";

        @BeforeEach
        void bindMockServer() {
            // As chamadas ao Jira passam pelo JiraService (TLS estrito, host bloqueado, redirecionamento controlado).
            JiraService jiraService = new JiraService();
            ReflectionTestUtils.setField(service, "jiraService", jiraService);
            RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(jiraService, "strictRestTemplate");
            mockServer = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        }

        private void expectEmptyRolesAndComponents() {
            mockServer.expect(requestTo("https://" + DOMAIN + "/rest/api/2/project/" + PROJECT_KEY + "/components"))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
            String epicSearchUrl = "https://" + DOMAIN + "/rest/api/2/search?jql=project%3D" + PROJECT_KEY
                    + "+AND+issuetype+in+(Epic%2CEpico%2C%C3%89pico%2CInitiative%2CIniciativa%2CFeature%2CTema)+ORDER+BY+updated+DESC&fields=*all&expand=names&maxResults=100";
            mockServer.expect(requestTo(epicSearchUrl))
                    .andRespond(withSuccess("{\"issues\": []}", MediaType.APPLICATION_JSON));
        }

        @Test
        @DisplayName("Deve detectar Project Lead e autor de issue recente como candidatos, sem duplicar")
        void previewProjectDetectsLeadAndIssueAuthors() {
            String projectJson = "{"
                    + "\"name\": \"Projeto Um\","
                    + "\"lead\": {\"accountId\": \"lead1\", \"displayName\": \"Lider Um\", \"emailAddress\": \"lider@empresa.com\"},"
                    + "\"roles\": {}"
                    + "}";
            mockServer.expect(requestTo("https://" + DOMAIN + "/rest/api/2/project/" + PROJECT_KEY))
                    .andRespond(withSuccess(projectJson, MediaType.APPLICATION_JSON));
            expectEmptyRolesAndComponents();

            String taskSearchUrl = "https://" + DOMAIN + "/rest/api/2/search?jql=project%3D" + PROJECT_KEY
                    + "+ORDER+BY+updated+DESC&fields=*all&expand=names&maxResults=100";
            String taskSearchJson = "{\"issues\": [{\"fields\": {\"reporter\": "
                    + "{\"accountId\": \"dev1\", \"displayName\": \"Dev Um\", \"emailAddress\": \"dev1@empresa.com\"}}}]}";
            mockServer.expect(requestTo(taskSearchUrl))
                    .andRespond(withSuccess(taskSearchJson, MediaType.APPLICATION_JSON));

            JiraSyncRequest request = JiraSyncRequest.builder()
                    .jiraDomain(DOMAIN).projectKey(PROJECT_KEY).token("fake-token").build();

            JiraProjectPreviewDto preview = service.previewProject(request);

            assertEquals("Projeto Um", preview.getSquadName());
            assertEquals(PROJECT_KEY, preview.getSquadId());
            assertEquals(2, preview.getTotalCandidates());
            assertTrue(preview.getMembers().stream().anyMatch(m -> "lead1".equals(m.getJiraAccountId())));
            assertTrue(preview.getMembers().stream().anyMatch(m -> "dev1".equals(m.getJiraAccountId())));
            mockServer.verify();
        }

        @Test
        @DisplayName("Nao deve incluir conta de bot/integracao detectada por nome ou e-mail")
        void previewProjectExcludesBotAccounts() {
            String projectJson = "{"
                    + "\"name\": \"Projeto Um\","
                    + "\"lead\": {\"accountId\": \"bot1\", \"displayName\": \"Zendesk Integrador\", \"emailAddress\": \"bot@empresa.com\"},"
                    + "\"roles\": {}"
                    + "}";
            mockServer.expect(requestTo("https://" + DOMAIN + "/rest/api/2/project/" + PROJECT_KEY))
                    .andRespond(withSuccess(projectJson, MediaType.APPLICATION_JSON));
            expectEmptyRolesAndComponents();

            String taskSearchUrl = "https://" + DOMAIN + "/rest/api/2/search?jql=project%3D" + PROJECT_KEY
                    + "+ORDER+BY+updated+DESC&fields=*all&expand=names&maxResults=100";
            mockServer.expect(requestTo(taskSearchUrl))
                    .andRespond(withSuccess("{\"issues\": []}", MediaType.APPLICATION_JSON));

            JiraSyncRequest request = JiraSyncRequest.builder()
                    .jiraDomain(DOMAIN).projectKey(PROJECT_KEY).token("fake-token").build();

            JiraProjectPreviewDto preview = service.previewProject(request);

            assertEquals(0, preview.getTotalCandidates());
            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("Segurança do preview e cargos sugeridos")
    class PreviewSecurityTests {

        private static final String DOMAIN = "empresa.atlassian.net";

        private MockRestServiceServer bind() {
            JiraService jiraService = new JiraService();
            ReflectionTestUtils.setField(service, "jiraService", jiraService);
            RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(jiraService, "strictRestTemplate");
            return MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        }

        private JiraSyncRequest req(String domain, String key) {
            return JiraSyncRequest.builder().jiraDomain(domain).projectKey(key).token("fake-token").build();
        }

        private void expectComponentsAndSearch(MockRestServiceServer server) {
            server.expect(requestTo("https://" + DOMAIN + "/rest/api/2/project/P/components"))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
            server.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(),
                            requestTo(org.hamcrest.Matchers.containsString("/rest/api/2/search")))
                    .andRespond(withSuccess("{\"issues\":[]}", MediaType.APPLICATION_JSON));
        }

        @Test
        @DisplayName("Link de Project Role para outro host é ignorado: o token não sai do domínio configurado")
        void roleUrlForAnotherHostIsNeverCalled() {
            MockRestServiceServer server = bind();
            String projectJson = "{\"name\":\"P\",\"roles\":{\"Developers\":\"https://evil.example.com/rest/api/2/project/P/role/1\","
                    + "\"Interno\":\"https://" + DOMAIN + "/rest/api/2/project/P/role/2\"}}";
            server.expect(requestTo("https://" + DOMAIN + "/rest/api/2/project/P"))
                    .andRespond(withSuccess(projectJson, MediaType.APPLICATION_JSON));
            server.expect(requestTo("https://" + DOMAIN + "/rest/api/2/project/P/role/2"))
                    .andRespond(withSuccess("{\"actors\":[]}", MediaType.APPLICATION_JSON));
            expectComponentsAndSearch(server);

            JiraProjectPreviewDto preview = service.previewProject(req(DOMAIN, "P"));

            assertEquals(0, preview.getTotalCandidates());
            // Nenhuma chamada a evil.example.com estava esperada: se tivesse sido feita, o mock teria falhado.
            server.verify();
        }

        @Test
        @DisplayName("Domínio com caminho, credencial ou parâmetros é recusado (400)")
        void rejectsDomainWithPathOrCredentials() {
            for (String bad : new String[] {"jira.empresa.com/x", "user@jira.empresa.com", "jira.empresa.com?a=b", "jira.empresa.com#x", ""}) {
                var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                        () -> service.previewProject(req(bad, "P")), bad);
                assertEquals(400, ex.getStatusCode().value());
            }
        }

        @Test
        @DisplayName("Chave de projeto com caracteres de URL é recusada (400)")
        void rejectsProjectKeyThatBreaksTheUrl() {
            var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> service.previewProject(req(DOMAIN, "P/../../x")));
            assertEquals(400, ex.getStatusCode().value());
        }

        @Test
        @DisplayName("Grupo com espaço no nome é consultado com o nome codificado (antes era descartado em silêncio)")
        void groupNameIsUrlEncoded() {
            MockRestServiceServer server = bind();
            server.expect(requestTo("https://" + DOMAIN + "/rest/api/2/project/P"))
                    .andRespond(withSuccess("{\"name\":\"P\",\"roles\":{\"Developers\":\"https://" + DOMAIN + "/rest/api/2/project/P/role/1\"}}", MediaType.APPLICATION_JSON));
            server.expect(requestTo("https://" + DOMAIN + "/rest/api/2/project/P/role/1"))
                    .andRespond(withSuccess("{\"actors\":[{\"name\":\"time dev\",\"type\":\"atlassian-group-role-actor\",\"actorGroup\":{\"name\":\"time dev\"}}]}", MediaType.APPLICATION_JSON));
            server.expect(requestTo("https://" + DOMAIN + "/rest/api/2/group/member?groupname=time+dev"))
                    .andRespond(withSuccess("{\"values\":[{\"accountId\":\"u1\",\"displayName\":\"Pessoa Um\",\"emailAddress\":\"u1@empresa.com\"}]}", MediaType.APPLICATION_JSON));
            expectComponentsAndSearch(server);

            JiraProjectPreviewDto preview = service.previewProject(req(DOMAIN, "P"));

            assertEquals(1, preview.getTotalCandidates());
            assertEquals("u1", preview.getMembers().get(0).getJiraAccountId());
        }

        @Test
        @DisplayName("'Suporte', 'Parte', 'Equipe' e 'Overhead' não viram Agile Master, UX ou People Lead")
        void ambiguousSubstringsDoNotPromote() {
            assertNotEquals("Agile Master", JiraAdminService.evaluateRole("Analista de Suporte").canonicalRole);
            assertNotEquals("Agile Master", JiraAdminService.evaluateRole("Responsável Suporte").canonicalRole);
            assertNotEquals("Agile Master", JiraAdminService.evaluateRole("Parte interessada").canonicalRole);
            assertNotEquals("UX/Designer", JiraAdminService.evaluateRole("Equipe").canonicalRole);
            assertNotEquals("People Lead", JiraAdminService.evaluateRole("Overhead").canonicalRole);
        }

        @Test
        @DisplayName("Siglas continuam reconhecidas quando são a palavra inteira")
        void acronymsStillRecognised() {
            assertEquals("Agile Master", JiraAdminService.evaluateRole("RTE").canonicalRole);
            assertEquals("Agile Master", JiraAdminService.evaluateRole("Release Train Engineer (RTE)").canonicalRole);
            assertEquals("UX/Designer", JiraAdminService.evaluateRole("UX").canonicalRole);
            assertEquals("UX/Designer", JiraAdminService.evaluateRole("Time de UI").canonicalRole);
            assertEquals("QA", JiraAdminService.evaluateRole("QA").canonicalRole);
            assertEquals("SME", JiraAdminService.evaluateRole("SME").canonicalRole);
            assertEquals("Developer", JiraAdminService.evaluateRole("Dev").canonicalRole);
            assertEquals("Product Owner", JiraAdminService.evaluateRole("GPM").canonicalRole);
            assertEquals("Agile Master", JiraAdminService.evaluateRole("Scrum Master").canonicalRole);
            assertEquals("Agile Coach", JiraAdminService.evaluateRole("Agile Coaching").canonicalRole);
        }
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("extractAvatarUrl prefere 48x48 e aceita avatarUrl simples")
    void extractAvatarUrlVariants() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        org.junit.jupiter.api.Assertions.assertEquals("https://j/48",
                JiraAdminService.extractAvatarUrl(om.readTree("{\"avatarUrls\":{\"16x16\":\"https://j/16\",\"48x48\":\"https://j/48\"}}")));
        org.junit.jupiter.api.Assertions.assertEquals("https://j/32",
                JiraAdminService.extractAvatarUrl(om.readTree("{\"avatarUrls\":{\"32x32\":\"https://j/32\"}}")));
        org.junit.jupiter.api.Assertions.assertEquals("https://j/a",
                JiraAdminService.extractAvatarUrl(om.readTree("{\"avatarUrl\":\"https://j/a\"}")));
        org.junit.jupiter.api.Assertions.assertNull(JiraAdminService.extractAvatarUrl(om.readTree("{\"displayName\":\"x\"}")));
        org.junit.jupiter.api.Assertions.assertNull(JiraAdminService.extractAvatarUrl(null));
    }
}
