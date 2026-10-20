package com.agilespace.backend.service;

import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.SquadPersonConfig;
import com.agilespace.backend.repository.SquadMemberRepository;
import com.agilespace.backend.repository.SquadPersonConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Homologação DDWMISSI: a mesma pessoa aparecia em 2 linhas do roster, a chave interna do Jira Server (JIRAUSER97280, que vem
 * nas issues e não tem e-mail) e o nome de usuário (bruna.balves, que vem dos papéis do projeto, com e-mail e vínculo).
 */
@DisplayName("Fusão de pares JIRAUSER x usuário, importação do Jira sem pseudo-pessoas")
class FusaoParesJiraTest {

    private List<SquadMember> db;
    private List<SquadPersonConfig> configs;
    private RosterMergeService merge;

    private SquadMember row(String account, String name, String email, Double hours, String role, String claimed, String override) {
        return SquadMember.builder().dbId("DDWMISSI_" + account).squadId("DDWMISSI").jiraAccountId(account).displayName(name)
                .email(email).role(role).capacityHoursPerDay(hours).claimedByUid(claimed).overrideType(override).build();
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        db = new ArrayList<>();
        configs = new ArrayList<>();
        SquadMemberRepository members = mock(SquadMemberRepository.class);
        SquadPersonConfigRepository cfg = mock(SquadPersonConfigRepository.class);
        when(members.findBySquadIdOrderByDisplayNameAsc(any())).thenAnswer(i -> db.stream()
                .filter(m -> m.getSquadId().equals(i.getArgument(0))).sorted(Comparator.comparing(SquadMember::getDisplayName)).toList());
        doAnswer(i -> db.removeIf(m -> m.getDbId().equals(((SquadMember) i.getArgument(0)).getDbId()))).when(members).delete(any());
        when(members.save(any())).thenAnswer(i -> {
            SquadMember m = i.getArgument(0);
            db.removeIf(x -> x.getDbId().equals(m.getDbId()));
            db.add(m);
            return m;
        });
        when(cfg.findBySquadIdAndJiraAccountId(anyString(), anyString())).thenAnswer(i -> configs.stream()
                .filter(c -> c.getSquadId().equals(i.getArgument(0)) && c.getJiraAccountId().equals(i.getArgument(1))).toList());
        when(cfg.findById(anyString())).thenAnswer(i -> configs.stream().filter(c -> c.getDbId().equals(i.getArgument(0))).findFirst());
        when(cfg.save(any())).thenAnswer(i -> {
            SquadPersonConfig c = i.getArgument(0);
            configs.removeIf(x -> x.getDbId().equals(c.getDbId()));
            configs.add(c);
            return c;
        });
        doAnswer(i -> configs.removeIf(c -> c.getDbId().equals(((SquadPersonConfig) i.getArgument(0)).getDbId()))).when(cfg).delete(any());
        merge = new RosterMergeService(members, cfg);
    }

    private void seedRealCase() {
        db.add(row("JIRAUSER97280", "bruna de brito alves_", null, 6.0, null, null, null));
        db.add(row("bruna.balves", "bruna de brito alves_", "bruna.balves@totvs.com.br", 8.0, "Developer", "361ca1be-uid", null));
        db.add(row("JIRAUSER111710", "Nathan Caldas", null, 6.0, null, null, null));
        db.add(row("nathan.caldas", "Nathan Caldas", "nathan.caldas@ext.totvs.com.br", 8.0, "QA", "uid-nathan", null));
        db.add(row("JIRAUSER98908", "Wanderson Alves", null, 6.0, null, null, null));
        db.add(row("wanderson.alves", "Wanderson Alves", "wanderson.alves@totvs.com.br", 8.0, "Developer", "uid-wan", null));
        db.add(row("taiza.araujo", "Taiza Araujo", "taiza.araujo@totvs.com.br", 8.0, "Developer", null, null));
    }

    @Test
    @DisplayName("os 3 pares reais de DDWMISSI são a mesma pessoa, nos dois sentidos")
    void tresParesReaisCasam() {
        seedRealCase();
        List<SquadMember> roster = List.copyOf(db);
        assertNotNull(PersonIdentity.resolve(roster, "JIRAUSER97280", null, "Bruna de Brito Alves_"));
        assertEquals("bruna.balves", PersonIdentity.resolve(roster.stream().filter(m -> !m.getJiraAccountId().startsWith("JIRAUSER")).toList(),
                "JIRAUSER97280", null, "bruna de brito alves_").getJiraAccountId());
        for (SquadMember a : roster) {
            for (SquadMember b : roster) {
                if (a == b) continue;
                boolean par = PersonIdentity.nameKey(a.getDisplayName()).equals(PersonIdentity.nameKey(b.getDisplayName()));
                assertEquals(par, PersonIdentity.sameRosterPerson(a, b, roster), a.getJiraAccountId() + " x " + b.getJiraAccountId());
            }
        }
    }

    @Test
    @DisplayName("homônimos legítimos NÃO se fundem")
    void homonimosNaoFundem() {
        // dois nomes de usuário com o mesmo nome: pessoas diferentes
        assertNull(PersonIdentity.resolve(List.of(row("ana.lima", "Ana Lima", "ana.lima@x.com", 8.0, null, null, null)),
                "ana.lima2", null, "Ana Lima"));
        // duas chaves internas com o mesmo nome: contas diferentes
        assertNull(PersonIdentity.resolve(List.of(row("JIRAUSER1", "Ana Lima", null, 8.0, null, null, null)),
                "JIRAUSER2", null, "Ana Lima"));
        // chave interna x usuário, mas existem DOIS candidatos com o nome: ambíguo
        assertNull(PersonIdentity.resolve(List.of(row("ana.lima", "Ana Lima", "a1@x.com", 8.0, null, null, null),
                row("ana.lima.2", "Ana Lima", "a2@x.com", 8.0, null, null, null)), "JIRAUSER9", null, "Ana Lima"));
        // chave interna x usuário com e-mails diferentes: pessoas diferentes
        assertNull(PersonIdentity.resolve(List.of(row("ana.lima", "Ana Lima", "a1@x.com", 8.0, null, null, null)),
                "JIRAUSER9", "outra@x.com", "Ana Lima"));
        // a chave interna carrega e-mail próprio: não é "sem e-mail", não funde por nome
        assertNull(PersonIdentity.resolve(List.of(row("JIRAUSER7", "Ana Lima", "ana@x.com", 8.0, null, null, null)),
                "ana.lima", null, "Ana Lima"));
        // nomes diferentes nunca
        assertNull(PersonIdentity.resolve(List.of(row("ana.lima", "Ana Lima", "a@x.com", 8.0, null, null, null)),
                "JIRAUSER3", null, "Ana Souza"));
        // consolidar um roster com homônimos não funde nada
        db.add(row("JIRAUSER1", "Ana Lima", null, 8.0, null, null, null));
        db.add(row("JIRAUSER2", "Ana Lima", null, 8.0, null, null, null));
        db.add(row("ana.lima", "Ana Lima", "a@x.com", 8.0, "Developer", "u", null));
        assertEquals(0, merge.consolidate("DDWMISSI"));
        assertEquals(3, db.size());
    }

    @Test
    @DisplayName("consolida os 3 pares: fica a linha com e-mail/vínculo/cargo, horas calibradas e configurações preservadas")
    void consolidaTresPares() {
        seedRealCase();
        // Nathan: a linha JIRAUSER tem horas calibradas à mão; a que fica está no padrão, então herda as horas.
        db.stream().filter(m -> m.getJiraAccountId().equals("JIRAUSER111710")).forEach(m -> {
            m.setCapacityHoursPerDay(5.0);
            m.setOverrideType("MANUAL_OVERRIDE");
            m.setCalibrationNotes("férias parciais");
        });
        // Wanderson: ambas calibradas; vale a da linha que fica.
        db.stream().filter(m -> m.getJiraAccountId().equals("JIRAUSER98908")).forEach(m -> { m.setCapacityHoursPerDay(4.0); m.setOverrideType("MANUAL_OVERRIDE"); });
        db.stream().filter(m -> m.getJiraAccountId().equals("wanderson.alves")).forEach(m -> { m.setCapacityHoursPerDay(7.0); m.setOverrideType("MANUAL_OVERRIDE"); });
        // Configurações por pessoa: Bruna só na chave antiga; Wanderson nas duas (a da que fica prevalece).
        configs.add(SquadPersonConfig.builder().dbId("DDWMISSI_GLOBAL_JIRAUSER97280").squadId("DDWMISSI").sprintId("GLOBAL")
                .jiraAccountId("JIRAUSER97280").papel("DEV").horasProdutivas(5.5).build());
        configs.add(SquadPersonConfig.builder().dbId("DDWMISSI_GLOBAL_JIRAUSER98908").squadId("DDWMISSI").sprintId("GLOBAL")
                .jiraAccountId("JIRAUSER98908").papel("QA").diasRegressivo(2).build());
        configs.add(SquadPersonConfig.builder().dbId("DDWMISSI_GLOBAL_wanderson.alves").squadId("DDWMISSI").sprintId("GLOBAL")
                .jiraAccountId("wanderson.alves").papel("DEV").build());

        assertEquals(3, merge.consolidate("DDWMISSI"));
        assertEquals(4, db.size());
        assertTrue(db.stream().noneMatch(m -> PersonIdentity.isJiraServerKey(m.getJiraAccountId())));

        SquadMember bruna = db.stream().filter(m -> m.getJiraAccountId().equals("bruna.balves")).findFirst().orElseThrow();
        assertEquals("361ca1be-uid", bruna.getClaimedByUid());
        assertEquals("bruna.balves@totvs.com.br", bruna.getEmail());
        assertEquals("Developer", bruna.getRole());
        assertEquals(8.0, bruna.getCapacityHoursPerDay());

        SquadMember nathan = db.stream().filter(m -> m.getJiraAccountId().equals("nathan.caldas")).findFirst().orElseThrow();
        assertEquals("QA", nathan.getRole());
        assertEquals(5.0, nathan.getCapacityHoursPerDay());
        assertEquals("MANUAL_OVERRIDE", nathan.getOverrideType());
        assertEquals("férias parciais", nathan.getCalibrationNotes());

        SquadMember wan = db.stream().filter(m -> m.getJiraAccountId().equals("wanderson.alves")).findFirst().orElseThrow();
        assertEquals(7.0, wan.getCapacityHoursPerDay());

        assertTrue(configs.stream().noneMatch(c -> c.getJiraAccountId().startsWith("JIRAUSER")));
        SquadPersonConfig movida = configs.stream().filter(c -> c.getDbId().equals("DDWMISSI_GLOBAL_bruna.balves")).findFirst().orElseThrow();
        assertEquals("DEV", movida.getPapel());
        assertEquals(5.5, movida.getHorasProdutivas());
        SquadPersonConfig wanCfg = configs.stream().filter(c -> c.getDbId().equals("DDWMISSI_GLOBAL_wanderson.alves")).findFirst().orElseThrow();
        assertEquals("DEV", wanCfg.getPapel()); // a da chave que fica vale
        assertEquals(2, wanCfg.getDiasRegressivo()); // o que estava vazio é preenchido
        assertEquals(2, configs.size());
    }

    @Test
    @DisplayName("par já fundido: consolidar de novo não faz nada (idempotente)")
    void idempotente() {
        seedRealCase();
        assertEquals(3, merge.consolidate("DDWMISSI"));
        List<SquadMember> depois = List.copyOf(db);
        assertEquals(0, merge.consolidate("DDWMISSI"));
        assertEquals(depois.size(), db.size());
    }

    @Test
    @DisplayName("troca de chave (usuário chega, JIRAUSER é adotado) só ocorre de JIRAUSER para usuário/gerado para real")
    void adocaoDeChave() {
        assertTrue(PersonIdentity.shouldAdoptIncomingId("JIRAUSER1", "bruna.balves"));
        assertTrue(PersonIdentity.shouldAdoptIncomingId("manual-sq-ana", "JIRAUSER1"));
        assertFalse(PersonIdentity.shouldAdoptIncomingId("bruna.balves", "JIRAUSER1"));
        assertFalse(PersonIdentity.shouldAdoptIncomingId("JIRAUSER1", "JIRAUSER2"));
        assertFalse(PersonIdentity.shouldAdoptIncomingId("a.b", "a.b"));
    }

    // ---------- Importação do Jira: o que não é pessoa ----------

    private ProjectMemberRole pm(String account, String name, String email, boolean lead) {
        return ProjectMemberRole.builder().projectId("DDWMISSI").roleName("Developer").roleKey("DEVELOPER").jiraAccountId(account)
                .displayName(name).email(email).isLeadership(lead).build();
    }

    @Test
    @DisplayName("nome da squad sem e-mail e caixa compartilhada ficam de fora; pessoa real entra")
    void filtroDeNaoPessoas() {
        List<ProjectMemberRole> in = List.of(
                pm("Projeto Mississauga - Winthor", "Projeto Mississauga - Winthor", null, false),
                pm("DDWMISSI", "DDWMISSI", null, false),
                pm("engenharia.vd", "Engenharia VD", "engenharia.vd@totvs.com.br", false),
                pm("taiza.araujo", "Taiza Araujo", "taiza.araujo@totvs.com.br", false),
                pm("rodrigo.engenharia", "Rodrigo Engenharia", "rodrigo.engenharia@totvs.com.br", false),
                pm("sem.email", "Pessoa Sem Email", null, false));
        List<ProjectMemberRole> out = JiraProfieldsService.dropNonPeople(in, "DDWMISSI", "Projeto Mississauga - Winthor");
        assertEquals(List.of("taiza.araujo", "rodrigo.engenharia", "sem.email"), out.stream().map(ProjectMemberRole::getJiraAccountId).toList());
    }

    @Test
    @DisplayName("objeto de projeto/componente do Jira e conta de aplicativo não são usuário")
    void objetoDeProjetoNaoEUsuario() throws Exception {
        ObjectMapper om = new ObjectMapper();
        JiraAdminService admin = new JiraAdminService();
        boolean projeto = ReflectionTestUtils.invokeMethod(admin, "isUserObject",
                om.readTree("{\"self\":\"https://j/rest/api/2/project/10010\",\"key\":\"DDWMISSI\",\"name\":\"Projeto Mississauga - Winthor\"}"));
        boolean semSelf = ReflectionTestUtils.invokeMethod(admin, "isUserObject",
                om.readTree("{\"key\":\"JIRAUSER1\",\"name\":\"ana.lima\",\"displayName\":\"Ana Lima\",\"emailAddress\":\"ana@x.com\"}"));
        boolean usuario = ReflectionTestUtils.invokeMethod(admin, "isUserObject",
                om.readTree("{\"self\":\"https://j/rest/api/2/user?username=ana\",\"name\":\"ana\",\"displayName\":\"Ana\"}"));
        boolean app = ReflectionTestUtils.invokeMethod(admin, "isUserObject",
                om.readTree("{\"accountId\":\"557058:abc\",\"accountType\":\"app\",\"displayName\":\"Bot\"}"));
        assertFalse(projeto);
        assertTrue(semSelf);
        assertTrue(usuario);
        assertFalse(app);
        assertTrue(JiraAdminService.looksLikeSharedMailbox("engenharia.vd@totvs.com.br", null));
        assertFalse(JiraAdminService.looksLikeSharedMailbox("bruna.balves@totvs.com.br", "bruna.balves"));
    }
}
