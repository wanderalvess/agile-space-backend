package com.agilespace.backend.service;

import com.agilespace.backend.domain.MembershipSource;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pessoas duplicadas na squad (mesmo nome e e-mail). O roster é simulado por uma lista em memória que imita as restrições do
 * banco (squad + conta únicos, e squad + e-mail normalizado único, V85), para provar que nenhum caminho cria duas linhas da
 * mesma pessoa e que homônimos diferentes continuam separados.
 */
@DisplayName("Pessoas duplicadas: um só registro por pessoa em todos os caminhos de criação")
class PessoasDuplicadasTest {

    private List<SquadMember> db;
    private SquadMemberRepository memberRepository;
    private SquadMemberExclusionRepository exclusionRepository;
    private ProjectMemberRoleRepository projectRoles;
    private UserRepository userRepository;
    private List<ProjectMemberRole> pmr;
    private SquadService squadService;
    private SquadTeamService teamService;

    private final User pl = User.builder().id("pl1").email("pl@x.com").name("Paula Lead").role("MEMBER").build();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        db = new ArrayList<>();
        pmr = new ArrayList<>();
        memberRepository = mock(SquadMemberRepository.class);
        exclusionRepository = mock(SquadMemberExclusionRepository.class);
        projectRoles = mock(ProjectMemberRoleRepository.class);
        userRepository = mock(UserRepository.class);

        when(memberRepository.findBySquadIdOrderByDisplayNameAsc(any())).thenAnswer(i -> db.stream()
                .filter(m -> m.getSquadId().equals(i.getArgument(0))).sorted(Comparator.comparing(SquadMember::getDisplayName)).toList());
        when(memberRepository.findBySquadIdAndJiraAccountId(any(), any())).thenAnswer(i -> db.stream()
                .filter(m -> m.getSquadId().equals(i.getArgument(0)) && m.getJiraAccountId().equals(i.getArgument(1))).findFirst());
        when(memberRepository.save(any())).thenAnswer(i -> upsert(i.getArgument(0)));
        when(memberRepository.saveAll(any())).thenAnswer(i -> {
            List<SquadMember> out = new ArrayList<>();
            for (SquadMember m : (Iterable<SquadMember>) i.getArgument(0)) out.add(upsert(m));
            return out;
        });
        doAnswer(i -> db.removeIf(m -> m.getDbId().equals(((SquadMember) i.getArgument(0)).getDbId()))).when(memberRepository).delete(any());
        doAnswer(i -> {
            for (SquadMember m : (Iterable<SquadMember>) i.getArgument(0)) db.removeIf(x -> x.getDbId().equals(m.getDbId()));
            return null;
        }).when(memberRepository).deleteAll(any());
        when(projectRoles.findByProjectId("SQ1")).thenAnswer(i -> new ArrayList<>(pmr));
        when(projectRoles.save(any())).thenAnswer(i -> {
            pmr.add(i.getArgument(0));
            return i.getArgument(0);
        });
        pmr.add(ProjectMemberRole.builder().id("r-pl").projectId("SQ1").roleName("People Lead").roleKey("PEOPLE_LEAD").isLeadership(true)
                .email("pl@x.com").userId("pl1").displayName("Paula Lead").build());

        squadService = new SquadService(null, null, null, memberRepository, null, null, null, userRepository, null, exclusionRepository);
        teamService = new SquadTeamService(memberRepository, exclusionRepository, projectRoles, userRepository, null);
    }

    /** Imita o banco: chave (squad, conta) e (squad, e-mail normalizado) únicas. */
    private SquadMember upsert(SquadMember m) {
        db.removeIf(x -> x.getDbId().equals(m.getDbId()));
        for (SquadMember x : db) {
            if (!x.getSquadId().equals(m.getSquadId())) continue;
            String ne = PersonIdentity.normEmail(m.getEmail());
            if (x.getJiraAccountId().equals(m.getJiraAccountId()) || (ne != null && ne.equals(PersonIdentity.normEmail(x.getEmail())))) {
                throw new DataIntegrityViolationException("duplicate key value violates unique constraint \"ux_squad_members_squad_email\"");
            }
        }
        db.add(m);
        return m;
    }

    private SquadMember row(String account, String name, String email) {
        return SquadMember.builder().dbId("SQ1_" + account).squadId("SQ1").jiraAccountId(account).displayName(name).email(email).build();
    }

    private long count(String email) {
        return db.stream().filter(m -> email.equalsIgnoreCase(m.getEmail() == null ? "" : m.getEmail().trim())).count();
    }

    @Nested
    @DisplayName("Regra de identidade (conta > e-mail > nome)")
    class Identidade {
        @Test
        void emailNormalizadoCasaMesmoComCaixaEEspacos() {
            List<SquadMember> roster = List.of(row("a1", "Ana Lima", "ana@x.com"));
            assertNotNull(PersonIdentity.resolve(roster, "outro", " ANA@X.COM ", "Qualquer"));
        }

        @Test
        void nomeSoCasaQuandoNaoHaComoSerOutraPessoa() {
            List<SquadMember> roster = List.of(row("manual-sq1-ana-lima", "Ana Lima", null));
            assertNotNull(PersonIdentity.resolve(roster, "5f1c0jira", null, "ana  LIMA"));
            assertNotNull(PersonIdentity.resolve(List.of(row("manual-x", "José Álvaro", null)), "5f1c", null, "Jose Alvaro"));
        }

        @Test
        void homonimosComEmailsDiferentesOuContasReaisDiferentesNaoSeFundem() {
            assertNull(PersonIdentity.resolve(List.of(row("a1", "Ana Lima", "ana1@x.com")), "a2", "ana2@x.com", "Ana Lima"));
            assertNull(PersonIdentity.resolve(List.of(row("5f1c-real-1", "Ana Lima", null)), "5f1c-real-2", null, "Ana Lima"));
            assertNull(PersonIdentity.resolve(List.of(row("manual-a", "Ana Lima", null), row("manual-b", "Ana Lima", null)), "novo", null, "Ana Lima"));
        }
    }

    @Nested
    @DisplayName("Cadastro manual (SquadTeamService.addMember)")
    class CadastroManual {
        @Test
        @DisplayName("Duplo clique / dois pedidos: o segundo é 409 e continua uma só linha (antes, a chave por relógio criava duas)")
        void duploCliqueNaoDuplica() {
            teamService.addMember("SQ1", pl, new SquadTeamService.AddRequest(null, "Bia@X.com ", "Bia Souza", "Developer"));
            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> teamService.addMember("SQ1", pl,
                    new SquadTeamService.AddRequest(null, "bia@x.com", "Bia Souza", "Developer")));
            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
            assertEquals(1, count("bia@x.com"));
            assertEquals("bia@x.com", db.get(0).getEmail(), "e-mail gravado normalizado");
            assertEquals("bia@x.com", db.get(0).getJiraAccountId(), "chave estável: a mesma da planilha");
            assertEquals(2, pmr.size(), "um vínculo para a Paula e um para a Bia");
        }

        @Test
        @DisplayName("Mesmo nome sem e-mail duas vezes: 409 pedindo o e-mail; com e-mails diferentes são duas pessoas (decisão pendente: cadastro com e-mail ao lado de um pré-cadastro só por nome)")
        void homonimoSemEmail() {
            teamService.addMember("SQ1", pl, new SquadTeamService.AddRequest(null, null, "Caio Dias", "QA"));
            assertThrows(ResponseStatusException.class,
                    () -> teamService.addMember("SQ1", pl, new SquadTeamService.AddRequest(null, null, "caio  dias", "QA")));
            teamService.addMember("SQ1", pl, new SquadTeamService.AddRequest(null, "caio1@x.com", "Caio Dias", "QA"));
            teamService.addMember("SQ1", pl, new SquadTeamService.AddRequest(null, "caio2@x.com", "Caio Dias", "QA"));
            assertEquals(3, db.size(), "o pré-cadastro sem e-mail e as duas pessoas com e-mail");
        }

        @Test
        @DisplayName("Pessoa que já tem vínculo (fundador/Jira) e entra no roster: reaproveita o vínculo, sem segunda linha igual")
        void reaproveitaVinculoExistente() {
            pmr.add(ProjectMemberRole.builder().id("r-dev").projectId("SQ1").roleName("Developer").roleKey("DEVELOPER")
                    .email("dev@x.com").displayName("Dev Um").userId("dev1").build());
            teamService.addMember("SQ1", pl, new SquadTeamService.AddRequest(null, "DEV@x.com", "Dev Um", "Developer"));
            assertEquals(1, pmr.stream().filter(r -> "dev@x.com".equalsIgnoreCase(r.getEmail())).count());
            assertEquals(1, count("dev@x.com"));
        }

        @Test
        @DisplayName("Já está na squad com outro papel: 409 mandando usar a troca de papel")
        void outroPapelNaoDuplica() {
            pmr.add(ProjectMemberRole.builder().id("r-qa").projectId("SQ1").roleName("QA").roleKey("QA")
                    .email("qa@x.com").displayName("Quita").build());
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> teamService.addMember("SQ1", pl, new SquadTeamService.AddRequest(null, "qa@x.com", "Quita", "Developer")));
            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
            assertTrue(db.isEmpty());
        }

        @Test
        @DisplayName("Corrida que passa pela checagem: o banco (índice V85) barra e vira 409, não 500")
        void corridaViraConflito() {
            db.add(row("outra-chave", "Dani", "dani@x.com"));
            when(memberRepository.findBySquadIdOrderByDisplayNameAsc("SQ1")).thenReturn(List.of());
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> teamService.addMember("SQ1", pl, new SquadTeamService.AddRequest(null, "dani@x.com", "Dani", "QA")));
            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        }
    }

    @Nested
    @DisplayName("Roster: planilha, Sync do Jira e convite (SquadService)")
    class Roster {
        @Test
        @DisplayName("Convite/edição com a conta do Jira para quem já está pelo e-mail: uma só linha, e a chave gerada vira a conta real")
        void saveMemberFundePorEmail() {
            db.add(row("eva@x.com", "Eva Prado", "eva@x.com"));
            squadService.saveMember("SQ1", "5f1c-jira-eva", SquadMember.builder().displayName("Eva Prado").email("EVA@x.com").build());
            assertEquals(1, db.size());
            assertEquals("5f1c-jira-eva", db.get(0).getJiraAccountId());
            assertEquals("SQ1_5f1c-jira-eva", db.get(0).getDbId());
        }

        @Test
        @DisplayName("Duas contas reais do Jira com o mesmo e-mail: funde (uma pessoa) e mantém a chave existente")
        void contaNovaComEmailExistenteFunde() {
            db.add(row("5f1c-antiga", "Fabi Rocha", "fabi@x.com"));
            squadService.batchUpsertMembers("SQ1", new ArrayList<>(List.of(row("5f1c-nova", "Fabi Rocha", "fabi@x.com"))));
            assertEquals(1, db.size());
            assertEquals("5f1c-antiga", db.get(0).getJiraAccountId());
        }

        @Test
        @DisplayName("Reimportar a mesma planilha não duplica e duas linhas do arquivo para a mesma pessoa viram uma")
        void reimportarNaoDuplica() {
            List<SquadMember> file = List.of(row("import.gabi", "Gabi Alves", "GABI@x.com"), row("gabi@x.com", "Gabi Alves", "gabi@x.com"));
            squadService.batchUpsertMembers("SQ1", new ArrayList<>(file));
            squadService.batchUpsertMembers("SQ1", new ArrayList<>(file));
            assertEquals(1, db.size());
            assertEquals("gabi@x.com", db.get(0).getEmail());
        }

        @Test
        @DisplayName("Sync da sprint: responsável sem e-mail casa a linha pré-cadastrada pelo nome (sem fantasma) e herda a conta real")
        void syncSemEmailCasaPorNome() {
            db.add(row("manual-sq1-hugo-reis", "Hugo Reis", null));
            db.get(0).setCapacityHoursPerDay(4.0);
            squadService.batchUpsertMembers("SQ1", new ArrayList<>(List.of(row("5f1c-hugo", "Hugo Reis", null))));
            assertEquals(1, db.size());
            assertEquals("5f1c-hugo", db.get(0).getJiraAccountId());
            assertEquals(4.0, db.get(0).getCapacityHoursPerDay(), "horas da pessoa preservadas");
        }

        @Test
        @DisplayName("Sync da sprint: mesma conta reaproveita a linha; homônimo de outra conta do Jira NÃO funde")
        void syncMesmaContaReaproveitaEHomonimoNaoFunde() {
            db.add(row("5f1c-ines", "Inês Melo", null));
            squadService.batchUpsertMembers("SQ1", new ArrayList<>(List.of(row("5f1c-ines", "Inês Melo", null))));
            assertEquals(1, db.size());
            squadService.batchUpsertMembers("SQ1", new ArrayList<>(List.of(row("5f1c-ines-outra", "Ines Melo", null))));
            assertEquals(2, db.size(), "duas contas reais distintas com o mesmo nome são duas pessoas");
        }

        @Test
        @DisplayName("Homônimos com e-mails diferentes na planilha continuam sendo duas pessoas")
        void homonimosComEmailsDiferentes() {
            squadService.batchUpsertMembers("SQ1", new ArrayList<>(List.of(
                    row("joao1@x.com", "João Silva", "joao1@x.com"), row("joao2@x.com", "João Silva", "joao2@x.com"))));
            assertEquals(2, db.size());
        }

        @Test
        @DisplayName("Conflito de unicidade estourado ao gravar vira 409 com mensagem")
        void conflitoDeUnicidadeVira409() {
            doThrow(new DataIntegrityViolationException("squad_members")).when(memberRepository).save(any());
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> squadService.saveMember("SQ1", "z1", SquadMember.builder().displayName("Zé").email("ze@x.com").build()));
            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        }
    }

    @Nested
    @DisplayName("Importação do Jira (TeamImportPlanner, project_member_roles)")
    class ImportacaoJira {
        private ProjectMemberRole pm(String id, String name, String email, String account, String userId, String source) {
            return ProjectMemberRole.builder().id(id).projectId("SQ1").roleName("Developer").roleKey("DEVELOPER")
                    .displayName(name).email(email).jiraAccountId(account).userId(userId).source(source).build();
        }

        @Test
        @DisplayName("Reimportar com o mesmo accountId reaproveita; conta nova com e-mail já existente funde")
        void mesmaContaOuEmailFunde() {
            var existing = List.of(pm("1", "Ana Lima", "ana@x.com", "acc1", null, MembershipSource.JIRA));
            var same = TeamImportPlanner.plan(existing, List.of(pm(null, "Ana Lima", "ana@x.com", "acc1", null, null)), List.of(), null);
            assertTrue(same.toInsert().isEmpty());
            var newAcc = TeamImportPlanner.plan(existing, List.of(pm(null, "Ana Lima", "ana@x.com", "acc2", null, null)), List.of(), null);
            assertTrue(newAcc.toInsert().isEmpty(), "e-mail igual com accountId novo atualiza no lugar");
        }

        @Test
        @DisplayName("Pessoa sem e-mail no Jira funde com o pré-cadastro só por nome; com identidade própria ou vinculada, não")
        void semEmailCasaPorNomeSemFundirHomonimo() {
            var placeholder = List.of(pm("1", "Bia Souza", null, null, null, MembershipSource.MANUAL));
            var merged = TeamImportPlanner.plan(placeholder, List.of(pm(null, "Bia  souza", null, "acc9", null, null)), List.of(), null);
            assertTrue(merged.toInsert().isEmpty());
            assertEquals("acc9", placeholder.get(0).getJiraAccountId());

            var withIdentity = List.of(pm("2", "Caio Dias", "caio@x.com", null, null, MembershipSource.MANUAL));
            assertEquals(1, TeamImportPlanner.plan(withIdentity, List.of(pm(null, "Caio Dias", null, "acc7", null, null)), List.of(), null).toInsert().size());
            var claimed = List.of(pm("3", "Dani Rossi", null, null, "uid-dani", MembershipSource.CLAIM));
            assertEquals(1, TeamImportPlanner.plan(claimed, List.of(pm(null, "Dani Rossi", null, "acc8", null, null)), List.of(), null).toInsert().size());
        }
    }
}
