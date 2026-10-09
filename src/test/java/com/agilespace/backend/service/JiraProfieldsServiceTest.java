package com.agilespace.backend.service;

import com.agilespace.backend.domain.ProjectConfig;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.CreateProjectRequestDto;
import com.agilespace.backend.dto.ProjectDetailDto;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("JiraProfieldsService - Integração de Projetos Profields/Jira e Papéis de Membros")
class JiraProfieldsServiceTest {

    @Mock private ProjectConfigRepository projectConfigRepository;
    @Mock private ProjectMemberRoleRepository projectMemberRoleRepository;
    @Mock private UserRepository userRepository;
    @Mock private JiraAdminService jiraAdminService;
    @Mock private JiraService jiraService;

    @InjectMocks
    private JiraProfieldsService service;

    @Nested
    @DisplayName("Validação de Token e Sincronização Profields")
    class ValidationTests {

        @Test
        @DisplayName("Deve exigir token PAT do Jira ao tentar sincronizar projeto Profields")
        void syncProjectFromProfieldsRequiresToken() {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.syncProjectFromProfields("empresa.atlassian.net", "PROJ1", null));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());

            ResponseStatusException exBlank = assertThrows(ResponseStatusException.class,
                    () -> service.syncProjectFromProfields("empresa.atlassian.net", "PROJ1", "   "));
            assertEquals(HttpStatus.BAD_REQUEST, exBlank.getStatusCode());

            verifyNoInteractions(projectConfigRepository);
        }

        private User caller(String id, String role) {
            return User.builder().id(id).role(role).email(id + "@x.com").name(id).build();
        }

        private ProjectMemberRole leader(String userId) {
            return ProjectMemberRole.builder().projectId("PROJ1").roleName("Agile Master").roleKey("AGILE_MASTER")
                    .userId(userId).isLeadership(true).build();
        }

        @Test
        @DisplayName("Usuário que não é liderança não pode reimportar projeto que já tem time")
        void syncRejectsNonLeaderOnExistingProject() {
            when(projectMemberRoleRepository.findByProjectId("PROJ1")).thenReturn(List.of(leader("dono")));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.syncProjectFromProfields("evil.example.com", "proj1", "tok", caller("intruso", "MEMBER")));
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
            verify(projectMemberRoleRepository, never()).deleteByProjectId(any());
        }

        @Test
        @DisplayName("Confirmação de importação também exige liderança do projeto existente")
        void confirmRejectsNonLeaderOnExistingProject() {
            when(projectMemberRoleRepository.findByProjectId("PROJ1")).thenReturn(List.of(leader("dono")));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.confirmImportFromProfields("evil.example.com", "PROJ1", "tok",
                            new com.agilespace.backend.dto.ProjectImportConfirmRequest(), caller("intruso", "MEMBER")));
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
            verify(projectMemberRoleRepository, never()).deleteByProjectId(any());
        }

        @Test
        @DisplayName("Liderança do projeto e ADMIN passam pelo guard (falham só adiante, sem token)")
        void syncAllowsLeaderAndAdmin() {
            lenient().when(projectMemberRoleRepository.findByProjectId("PROJ1")).thenReturn(List.of(leader("dono")));

            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                    () -> service.syncProjectFromProfields("empresa.atlassian.net", "PROJ1", null, caller("dono", "MEMBER"))).getStatusCode());
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                    () -> service.syncProjectFromProfields("empresa.atlassian.net", "PROJ1", null, caller("adm", "ADMIN"))).getStatusCode());
        }

        @Test
        @DisplayName("Domínio com caminho, credencial ou query é rejeitado antes de qualquer chamada")
        void syncRejectsMalformedDomain() {
            for (String bad : List.of("evil.com/x#", "user@evil.com", "evil.com?a=b", "a b.com")) {
                ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                        () -> service.previewProjectFromProfields(bad, "PROJ1", "tok", caller("u", "MEMBER")));
                assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode(), bad);
            }
        }

        @Test
        @DisplayName("Deve retornar vazio quando projeto Profields não for encontrado")
        void getProjectDetailsReturnsEmptyWhenNotFound() {
            when(projectConfigRepository.findById("PROJ1")).thenReturn(Optional.empty());

            Optional<ProjectDetailDto> result = service.getProjectDetails("proj1");

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("Criação Manual e Ingressão de Membros")
    class ManualProjectAndMembershipTests {

        @Test
        @DisplayName("Deve rejeitar criação de projeto com ID duplicado (HTTP 409 Conflict)")
        void createManualProjectRejectsDuplicateKey() {
            when(projectConfigRepository.existsById("MEUTIME")).thenReturn(true);
            CreateProjectRequestDto request = CreateProjectRequestDto.builder().id("meutime").name("Meu Time").build();
            User creator = User.builder().id("u1").name("Criador").email("criador@empresa.com").role("ADMIN").build();

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.createManualProject(request, creator));
            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        }

        @Test
        @DisplayName("Criador que já é Agile Master no roster entra como Agile Master, com liderança")
        void createManualProjectMakesCreatorAgileMaster() {
            when(projectMemberRoleRepository.findByUserId("u1")).thenReturn(List.of(
                    ProjectMemberRole.builder().projectId("OUTRA").roleName("Agile Master").roleKey("AGILE_MASTER").userId("u1").isLeadership(true).build()));
            when(projectConfigRepository.existsById("MEUTIME")).thenReturn(false);
            when(projectConfigRepository.save(any(ProjectConfig.class))).thenAnswer(i -> i.getArgument(0));
            when(projectMemberRoleRepository.save(any(ProjectMemberRole.class))).thenAnswer(i -> i.getArgument(0));

            CreateProjectRequestDto request = CreateProjectRequestDto.builder().id("meutime").name("Meu Time").build();
            User creator = User.builder().id("u1").name("Criador").email("criador@empresa.com").role("ADMIN").build();

            ProjectDetailDto result = service.createManualProject(request, creator);

            assertEquals("MEUTIME", result.getId());
            assertEquals(1, result.getMembers().size());
            assertEquals("AGILE_MASTER", result.getMembers().get(0).getRoleKey());
            assertTrue(result.getMembers().get(0).isLeadership());
        }

        @Test
        @DisplayName("Admin sem papel de AM/PL cria a equipe sem virar liderança")
        void createManualProjectDoesNotPromoteAdmin() {
            when(projectConfigRepository.existsById("MEUTIME")).thenReturn(false);
            when(projectConfigRepository.save(any(ProjectConfig.class))).thenAnswer(i -> i.getArgument(0));
            when(projectMemberRoleRepository.save(any(ProjectMemberRole.class))).thenAnswer(i -> i.getArgument(0));
            User admin = User.builder().id("u9").name("Adm").email("adm@empresa.com").role("ADMIN").build();

            ProjectDetailDto result = service.createManualProject(
                    CreateProjectRequestDto.builder().id("meutime").name("Meu Time").build(), admin);

            assertEquals("DEVELOPER", result.getMembers().get(0).getRoleKey());
            assertFalse(result.getMembers().get(0).isLeadership());
        }

        @Test
        @DisplayName("Quem não é AM, PL nem admin não cria equipe (403)")
        void createManualProjectRejectsNonManager() {
            CreateProjectRequestDto request = CreateProjectRequestDto.builder().id("meutime").name("Meu Time").build();
            User dev = User.builder().id("u2").name("Dev").email("dev@empresa.com").build();

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.createManualProject(request, dev));
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
            verify(projectConfigRepository, never()).save(any(ProjectConfig.class));
        }

        @Test
        @DisplayName("People Lead (por roster de outra equipe) pode criar equipe")
        void createManualProjectAllowsPeopleLeadFromRoster() {
            User pl = User.builder().id("u3").name("Paula").email("paula@empresa.com").build();
            when(projectMemberRoleRepository.findByUserId("u3")).thenReturn(List.of(
                    ProjectMemberRole.builder().projectId("OUTRA").roleName("People Lead").roleKey("PEOPLE_LEAD").userId("u3").isLeadership(true).build()));
            when(projectConfigRepository.existsById("MEUTIME")).thenReturn(false);
            when(projectConfigRepository.save(any(ProjectConfig.class))).thenAnswer(i -> i.getArgument(0));
            when(projectMemberRoleRepository.save(any(ProjectMemberRole.class))).thenAnswer(i -> i.getArgument(0));

            ProjectDetailDto result = service.createManualProject(
                    CreateProjectRequestDto.builder().id("meutime").name("Meu Time").build(), pl);

            assertEquals("MEUTIME", result.getId());
            assertEquals("PEOPLE_LEAD", result.getMembers().get(0).getRoleKey());
            assertTrue(result.getMembers().get(0).isLeadership());
        }

        @Test
        @DisplayName("Linha do roster casa com a pessoa por e-mail, conta do Jira ou nome completo")
        void rowMatchesUserByEmailAccountOrName() {
            User u = User.builder().id("u4").name("Ana Paula Nogueira").email("ana@x.com").jiraAccountId("ana.n").build();
            assertTrue(JiraProfieldsService.rowMatchesUser(
                    ProjectMemberRole.builder().email("ANA@x.com").displayName("Outro").build(), u));
            assertTrue(JiraProfieldsService.rowMatchesUser(
                    ProjectMemberRole.builder().jiraAccountId("ana.n").displayName("Outro").build(), u));
            assertTrue(JiraProfieldsService.rowMatchesUser(
                    ProjectMemberRole.builder().displayName("ana paula nogueira").build(), u));
            assertFalse(JiraProfieldsService.rowMatchesUser(
                    ProjectMemberRole.builder().email("outra@x.com").displayName("Ana Nogueira").build(), u));
        }

        @Test
        @DisplayName("Deve rejeitar auto-atribuição de papel de liderança (Product Owner/Scrum Master) ao entrar")
        void joinProjectRejectsLeadershipRole() {
            when(projectConfigRepository.existsById("PROJ1")).thenReturn(true);
            when(projectMemberRoleRepository.findByProjectId("PROJ1")).thenReturn(Collections.emptyList());

            User user = User.builder().id("u1").name("User").email("user@empresa.com").jiraAccountId("user.acc").build();

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.joinProject("proj1", "Product Owner", user));
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
            verify(projectMemberRoleRepository, never()).save(any(ProjectMemberRole.class));
        }

        @Test
        @DisplayName("Deve aceitar papel de desenvolvedor sem flag de liderança")
        void joinProjectAcceptsContributorRoleWithoutLeadership() {
            when(projectConfigRepository.existsById("PROJ1")).thenReturn(true);
            when(projectMemberRoleRepository.findByProjectId("PROJ1")).thenReturn(Collections.emptyList());
            when(projectMemberRoleRepository.save(any(ProjectMemberRole.class))).thenAnswer(i -> i.getArgument(0));

            User user = User.builder().id("u1").name("User").email("user@empresa.com").jiraAccountId("user.acc").build();
            service.joinProject("proj1", "Developer", user);

            ArgumentCaptor<ProjectMemberRole> captor = ArgumentCaptor.forClass(ProjectMemberRole.class);
            verify(projectMemberRoleRepository).save(captor.capture());
            assertFalse(captor.getValue().isLeadership());
            assertEquals("DEVELOPER", captor.getValue().getRoleKey());
        }
    }

    @Nested
    @DisplayName("Parser tolerante do layout do Profields")
    class ParserTests {

        private final ObjectMapper mapper = new ObjectMapper();

        private ProjectConfig parse(String json) throws Exception {
            when(projectConfigRepository.findById("DDW")).thenReturn(Optional.empty());
            Method m = JiraProfieldsService.class.getDeclaredMethod("parseProfieldsJson", String.class, JsonNode.class);
            m.setAccessible(true);
            return (ProjectConfig) m.invoke(service, "DDW", mapper.readTree(json));
        }

        @Test
        @DisplayName("Normaliza rótulos: sem acento, caixa ou pontuação")
        void normalizesLabels() {
            assertEquals("areadovp", JiraProfieldsService.normalizeLabel("Área do VP:"));
            assertEquals("segmentoprojeto", JiraProfieldsService.normalizeLabel("  SEGMENTO   Projeto "));
            assertEquals("vicepresidente", JiraProfieldsService.normalizeLabel("Vice-Presidente"));
        }

        @Test
        @DisplayName("Reconhece campos com acento/caixa diferentes e valores em objeto ou lista")
        void parsesVariantLabelsAndValueShapes() throws Exception {
            ProjectConfig p = parse("""
                {"sections":[{"title":"Geral","fields":[
                  {"label":"SEGMENTO PROJETO","value":{"name":"Varejo"}},
                  {"title":"Tribo:","values":[{"label":"Distribuição"}]},
                  {"fieldName":"Localização","text":"Joinville"},
                  {"name":"Área VP","value":"Operações"},
                  {"name":"Número de Pessoas no Dev Team","value":"12 pessoas"},
                  {"name":"Status do Projeto","value":{"value":"EM ANDAMENTO"}}
                ]}]}
                """);
            assertEquals("Varejo", p.getSegmentName());
            assertEquals("Distribuição", p.getTribeName());
            assertEquals("Joinville", p.getLocality());
            assertEquals("Operações", p.getVpArea());
            assertEquals(12, p.getDevTeamSize());
            assertEquals("EM ANDAMENTO", p.getStatus());
        }

        @Test
        @DisplayName("Resumo de diagnóstico traz rótulos e chaves, nunca valores")
        void describeStructureHasNoValues() throws Exception {
            JsonNode root = mapper.readTree("{\"fields\":[{\"name\":\"Tribo\",\"value\":\"SEGREDO-NOME-DE-PESSOA\"}]}");
            String outline = JiraProfieldsService.describeStructure(root);
            assertTrue(outline.contains("name=Tribo"));
            assertFalse(outline.contains("SEGREDO-NOME-DE-PESSOA"));
        }
    }

    @Nested
    @DisplayName("Descoberta de pessoas pela API padrão do Jira")
    class DiscoveryTests {

        @Test
        @DisplayName("Converte candidatos do importador do admin em papéis de projeto, marcando liderança")
        void convertsCandidatesToRoles() throws Exception {
            com.agilespace.backend.dto.JiraProjectPreviewDto preview = com.agilespace.backend.dto.JiraProjectPreviewDto.builder()
                    .members(List.of(
                            com.agilespace.backend.dto.JiraMemberCandidateDto.builder().jiraAccountId("a1").displayName("Ana PO").email("ana@x.com").role("Product Owner").score(100).build(),
                            com.agilespace.backend.dto.JiraMemberCandidateDto.builder().jiraAccountId("b2").displayName("Beto Dev").email("beto@x.com").role("Developer").score(10).build()))
                    .build();
            when(jiraAdminService.previewProject(any())).thenReturn(preview);

            Method m = JiraProfieldsService.class.getDeclaredMethod("discoverMembersFromJira", String.class, String.class, String.class);
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<ProjectMemberRole> roles = (List<ProjectMemberRole>) m.invoke(service, "jira.x.com", "DDW", "tok");

            assertEquals(2, roles.size());
            assertEquals("PRODUCT_OWNER", roles.get(0).getRoleKey());
            assertTrue(roles.get(0).isLeadership());
            assertEquals("DEVELOPER", roles.get(1).getRoleKey());
            assertFalse(roles.get(1).isLeadership());
        }

        @Test
        @DisplayName("Falha do Jira vira lista vazia, sem estourar o onboarding")
        void failureYieldsEmpty() throws Exception {
            when(jiraAdminService.previewProject(any())).thenThrow(new RuntimeException("boom"));
            Method m = JiraProfieldsService.class.getDeclaredMethod("discoverMembersFromJira", String.class, String.class, String.class);
            m.setAccessible(true);
            assertTrue(((List<?>) m.invoke(service, "jira.x.com", "DDW", "tok")).isEmpty());
        }
    }

    @Nested
    @DisplayName("Valores do Profields cruzados com o layout")
    class ValuesTests {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @DisplayName("Associa valor ao campo pelo id do layout e pelo nome embutido")
        void joinsValuesWithLayout() throws Exception {
            JsonNode layout = mapper.readTree("""
                {"sections":[{"children":[{"children":[{"children":[
                  {"field":{"id":"10","customFieldId":"customfield_77","name":"Tribo"}},
                  {"field":{"id":"11","name":"Segmento Projeto"}},
                  {"field":{"id":"12","name":"Agile Master"}}
                ]}]}]}]}
                """);
            JsonNode values = mapper.readTree("""
                [{"fieldId":10,"value":{"name":"Distribuição"}},
                 {"field":{"id":11},"values":["Varejo"]},
                 {"name":"Agile Master","value":[{"displayName":"Ana Souza","accountId":"a1"}]}]
                """);
            JsonNode synth = service.toSyntheticFields(layout, values);
            assertEquals(3, synth.size());
            assertEquals("Tribo", synth.get(0).get("name").asText());
            assertEquals("Segmento Projeto", synth.get(1).get("name").asText());
            assertEquals("Agile Master", synth.get(2).get("name").asText());
        }

        @Test
        @DisplayName("Forma com tipos não vaza valores")
        void shapeHasNoValues() throws Exception {
            String shape = JiraProfieldsService.describeShapeWithTypes(mapper.readTree("[{\"fieldId\":10,\"value\":\"SEGREDO\"}]"));
            assertTrue(shape.contains("value=string"));
            assertFalse(shape.contains("SEGREDO"));
        }
    }

    @Nested
    @DisplayName("Confirmação da importação com escolhas da prévia")
    class ConfirmationTests {

        private ProjectMemberRole member(String acc, String name, String email, String role, boolean lead, String userId) {
            return ProjectMemberRole.builder().projectId("DDW").jiraAccountId(acc).displayName(name).email(email)
                    .roleName(role).roleKey(role.toUpperCase().replace(' ', '_')).isLeadership(lead).userId(userId).build();
        }

        private JiraProfieldsService.ProfieldsSnapshot snapshot() {
            ProjectConfig project = ProjectConfig.builder().id("DDW").name("DDW").build();
            return new JiraProfieldsService.ProfieldsSnapshot(project, new java.util.ArrayList<>(List.of(
                    member("po1", "Ana PO", "ana@x.com", "Product Owner", true, null),
                    member("dev1", "Beto Dev", "beto@x.com", "Developer", false, null),
                    member("dev2", "Caio QA", "caio@x.com", "Developer", false, null))));
        }

        private com.agilespace.backend.dto.ProjectImportConfirmRequest.Member req(String acc, String role, boolean me) {
            return com.agilespace.backend.dto.ProjectImportConfirmRequest.Member.builder().jiraAccountId(acc).roleName(role).linkToMe(me).build();
        }

        @Test
        @DisplayName("Só entram as pessoas selecionadas e o cargo pode mudar para um cargo sem governança")
        void selectionAndRoleChange() {
            User me = User.builder().id("u-me").name("Eu").email("eu@x.com").build();
            var request = com.agilespace.backend.dto.ProjectImportConfirmRequest.builder()
                    .name("Projeto Missisauga - Winthor").segmentName("Distribuição").locality("")
                    .members(List.of(req("dev2", "QA", false), req("po1", "Product Owner", false)))
                    .build();

            var applied = service.applyConfirmation(snapshot(), request, me);

            assertEquals("Distribuição", applied.project().getSegmentName());
            assertNull(applied.project().getLocality());          // vazio vira "não informado"
            assertEquals("Projeto Missisauga - Winthor", applied.project().getName());
            assertEquals(2, applied.project().getDevTeamSize()); // sem liderança: Caio(QA) + o próprio usuário
            // PO + Caio(QA) + o próprio usuário como Developer (não se selecionou)
            assertEquals(3, applied.members().size());
            assertTrue(applied.members().stream().anyMatch(m -> "dev2".equals(m.getJiraAccountId()) && "QA".equals(m.getRoleName()) && !m.isLeadership()));
            assertTrue(applied.members().stream().noneMatch(m -> "dev1".equals(m.getJiraAccountId())));
            ProjectMemberRole self = applied.members().stream().filter(m -> "u-me".equals(m.getUserId())).findFirst().orElseThrow();
            assertEquals("Developer", self.getRoleName());
            assertFalse(self.isLeadership());
        }

        @Test
        @DisplayName("Ninguém se promove a liderança pela prévia: cargo de liderança só se vier do Jira")
        void noSelfPromotion() {
            User me = User.builder().id("u-me").name("Eu").email("eu@x.com").build();
            var request = com.agilespace.backend.dto.ProjectImportConfirmRequest.builder()
                    .members(List.of(req("dev1", "Agile Master", true)))
                    .build();

            var applied = service.applyConfirmation(snapshot(), request, me);

            ProjectMemberRole beto = applied.members().stream().filter(m -> "dev1".equals(m.getJiraAccountId())).findFirst().orElseThrow();
            assertEquals("Developer", beto.getRoleName());       // pedido de Agile Master ignorado
            assertFalse(beto.isLeadership());
            assertEquals("u-me", beto.getUserId());              // "sou eu" vincula a conta
        }

        @Test
        @DisplayName("'Sou eu' numa linha de liderança só vale se e-mail, conta ou nome batem com a pessoa")
        void linkToMeOnLeadershipRowNeedsMatch() {
            User me = User.builder().id("u-me").name("Eu").email("eu@x.com").build();
            var request = com.agilespace.backend.dto.ProjectImportConfirmRequest.builder()
                    .members(List.of(req("po1", "Product Owner", true)))
                    .build();

            var applied = service.applyConfirmation(snapshot(), request, me);

            ProjectMemberRole ana = applied.members().stream().filter(m -> "po1".equals(m.getJiraAccountId())).findFirst().orElseThrow();
            assertNull(ana.getUserId());                          // não é ela: vínculo ignorado
        }

        @Test
        @DisplayName("Pessoa que o Jira não devolveu não entra e 'sou eu' não rouba conta já vinculada")
        void unknownIgnoredAndLinkedAccountsProtected() {
            User me = User.builder().id("u-me").name("Eu").email("eu@x.com").build();
            var snap = snapshot();
            snap.members().set(1, member("dev1", "Beto Dev", "beto@x.com", "Developer", false, "u-outro"));
            var request = com.agilespace.backend.dto.ProjectImportConfirmRequest.builder()
                    .members(List.of(req("fantasma", "Developer", false), req("dev1", "Developer", true)))
                    .build();

            var applied = service.applyConfirmation(snap, request, me);

            assertTrue(applied.members().stream().noneMatch(m -> "fantasma".equals(m.getJiraAccountId())));
            ProjectMemberRole beto = applied.members().stream().filter(m -> "dev1".equals(m.getJiraAccountId())).findFirst().orElseThrow();
            assertEquals("u-outro", beto.getUserId());           // continua da outra conta
        }
    }

    @Nested
    @DisplayName("Pessoas repetidas na importação")
    class DedupeTests {

        private ProjectMemberRole m(String acc, String name, String email, String role, boolean lead) {
            return ProjectMemberRole.builder().projectId("DDW").jiraAccountId(acc).displayName(name).email(email)
                    .roleName(role).roleKey(role.toUpperCase().replace(' ', '_')).isLeadership(lead).build();
        }

        @Test
        @DisplayName("Mesma pessoa por e-mail (sem caixa) vira uma só, com o cargo de maior prioridade")
        void mergesByEmailKeepingHighestRole() {
            List<ProjectMemberRole> out = JiraProfieldsService.dedupeMembers(List.of(
                    m("a1", "Marielen Leite", "marielen.leite@totvs.com.br", "Agile Master", true),
                    m("a2", "Marielen Cristine de Almeida Leite", "MARIELEN.LEITE@TOTVS.COM.BR", "Developer", false)));
            assertEquals(1, out.size());
            assertEquals("Agile Master", out.get(0).getRoleName());
            assertTrue(out.get(0).isLeadership());
        }

        @Test
        @DisplayName("Sem e-mail, junta por nome normalizado; pessoas diferentes ficam separadas")
        void mergesByNameAndKeepsDistinctPeople() {
            List<ProjectMemberRole> out = JiraProfieldsService.dedupeMembers(List.of(
                    m("a1", "Bruna de Brito Alves", null, "Developer", false),
                    m("a2", "BRUNA DE BRITO ALVES", "bruna@totvs.com.br", "QA", false),
                    m("a3", "Charly Ribeiro", "charly@totvs.com.br", "Developer", false)));
            assertEquals(2, out.size());
            ProjectMemberRole bruna = out.stream().filter(p -> p.getDisplayName().toLowerCase().contains("bruna")).findFirst().orElseThrow();
            assertEquals("QA", bruna.getRoleName());                 // cargo específico vence Developer
            assertEquals("bruna@totvs.com.br", bruna.getEmail());    // completa o e-mail que faltava
        }
    }

    @Nested
    @DisplayName("Foto do usuário do Jira")
    class AvatarTests {

        @Test
        @DisplayName("Imagem pequena vira data URI; não-imagem, vazia ou grande demais é descartada")
        void toDataUriRules() {
            byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
            String uri = JiraProfieldsService.toDataUri(png, org.springframework.http.MediaType.IMAGE_PNG);
            assertTrue(uri.startsWith("data:image/png;base64,"));
            assertNull(JiraProfieldsService.toDataUri(png, org.springframework.http.MediaType.TEXT_HTML));   // página de login
            assertNull(JiraProfieldsService.toDataUri(new byte[0], org.springframework.http.MediaType.IMAGE_PNG));
            assertNull(JiraProfieldsService.toDataUri(new byte[JiraProfieldsService.MAX_AVATAR_BYTES + 1], org.springframework.http.MediaType.IMAGE_PNG));
            assertNull(JiraProfieldsService.toDataUri(png, null));
        }

        @Test
        @DisplayName("Descoberta de pessoas leva a URL da foto adiante")
        void discoveryKeepsAvatarUrl() throws Exception {
            com.agilespace.backend.dto.JiraProjectPreviewDto preview = com.agilespace.backend.dto.JiraProjectPreviewDto.builder()
                    .members(List.of(com.agilespace.backend.dto.JiraMemberCandidateDto.builder()
                            .jiraAccountId("a1").displayName("Ana").email("ana@x.com").role("Developer")
                            .avatarUrl("https://jira.x.com/secure/useravatar?ownerId=ana").build()))
                    .build();
            when(jiraAdminService.previewProject(any())).thenReturn(preview);
            Method m = JiraProfieldsService.class.getDeclaredMethod("discoverMembersFromJira", String.class, String.class, String.class);
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<ProjectMemberRole> roles = (List<ProjectMemberRole>) m.invoke(service, "jira.x.com", "DDW", "tok");
            assertEquals("https://jira.x.com/secure/useravatar?ownerId=ana", roles.get(0).getAvatarUrl());
        }
    }
}
