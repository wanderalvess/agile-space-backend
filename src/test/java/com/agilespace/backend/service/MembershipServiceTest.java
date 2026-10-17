package com.agilespace.backend.service;

import com.agilespace.backend.domain.ProjectConfig;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.MembershipDto;
import com.agilespace.backend.dto.MembershipsResponseDto;
import com.agilespace.backend.dto.UserProjectAccessDto;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.SquadMemberRepository;
import com.agilespace.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Cálculo do vínculo (MembershipService) com repositórios simulados. */
class MembershipServiceTest {

    @Mock private ProjectMemberRoleRepository pmrRepository;
    @Mock private ProjectConfigRepository projectConfigRepository;
    @Mock private SquadMemberRepository squadMemberRepository;
    @Mock private UserRepository userRepository;

    private MembershipCache cache;
    private MembershipService service;
    private final List<ProjectConfig> projects = new ArrayList<>();
    private final List<ProjectMemberRole> rows = new ArrayList<>();

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        cache = new MembershipCache(60);
        UserProjectResolverService resolver = new UserProjectResolverService(pmrRepository, projectConfigRepository);
        service = new MembershipService(resolver, projectConfigRepository, squadMemberRepository, userRepository, cache, "new");
        when(pmrRepository.findByEmailIgnoreCase(anyString())).thenAnswer(i -> rows.stream()
                .filter(r -> r.getEmail() != null && r.getEmail().equalsIgnoreCase(i.getArgument(0))).toList());
        when(pmrRepository.findByJiraAccountId(anyString())).thenReturn(List.of());
        when(pmrRepository.findByUserId(anyString())).thenAnswer(i -> rows.stream()
                .filter(r -> i.getArgument(0).equals(r.getUserId())).toList());
        when(projectConfigRepository.findAll()).thenAnswer(i -> List.copyOf(projects));
        when(projectConfigRepository.findAllById(anyIterable())).thenAnswer(i -> {
            List<String> ids = new ArrayList<>();
            ((Iterable<String>) i.getArgument(0)).forEach(ids::add);
            return projects.stream().filter(p -> ids.contains(p.getId())).toList();
        });
        when(squadMemberRepository.findByClaimedByUid(anyString())).thenReturn(List.of());
        when(squadMemberRepository.findByUserIdentifier(anyString())).thenReturn(List.of());
    }

    private User user() {
        return User.builder().id("u1").email("ana@empresa.com.br").name("Ana").build();
    }

    private void project(String id, String name, String segment, String tribe) {
        ProjectConfig p = new ProjectConfig();
        p.setId(id);
        p.setName(name);
        p.setSegmentName(segment);
        p.setTribeName(tribe);
        projects.add(p);
    }

    private ProjectMemberRole row(String id, String project, String key, String name, boolean leadership, String source) {
        ProjectMemberRole r = new ProjectMemberRole();
        r.setId(id);
        r.setProjectId(project);
        r.setRoleKey(key);
        r.setRoleName(name);
        r.setLeadership(leadership);
        r.setEmail("ana@empresa.com.br");
        r.setSource(source);
        rows.add(r);
        return r;
    }

    // ---------- papéis e precedência ----------

    @Test
    void duasLinhasNaMesmaSquad_liderancaPrevaleceETodosOsPapeisVoltam() {
        project("SQ1", "Squad Um", "Seg", "Tribo");
        row("a-dev", "SQ1", "DEVELOPER", "Developer", false, "JIRA");
        row("b-po", "SQ1", "PRODUCT_OWNER", "Product Owner", true, "MANUAL");

        MembershipService.View view = service.forUser(user());

        assertEquals(1, view.memberships().size());
        MembershipDto m = view.memberships().get(0);
        assertEquals("PRODUCT_OWNER", m.roleKey());
        assertTrue(m.leadership());
        assertEquals("MANUAL", m.source());
        assertEquals(MembershipDto.SCOPE_SQUAD, m.scope());
        assertTrue(m.direct());
        assertEquals(2, m.roles().size());
        assertEquals("PRODUCT_OWNER", m.roles().get(0).roleKey());
        assertTrue(m.roles().get(0).primary());
        assertEquals("DEVELOPER", m.roles().get(1).roleKey());
        assertFalse(m.roles().get(1).primary());
    }

    @Test
    void precedenciaEntreLiderancas_segueAOrdemDoPlano() {
        project("SQ1", "Squad Um", "Seg", "Tribo");
        row("1", "SQ1", "SCRUM_MASTER", "Scrum Master", true, null);
        row("2", "SQ1", "AGILE_MASTER", "Agile Master", true, null);
        row("3", "SQ1", "PRODUCT_OWNER", "Product Owner", true, null);
        row("4", "SQ1", "DEVELOPER", "Developer", false, null);

        List<MembershipDto.RoleDto> roles = service.forUser(user()).memberships().get(0).roles();

        assertEquals(List.of("PRODUCT_OWNER", "AGILE_MASTER", "SCRUM_MASTER", "DEVELOPER"),
                roles.stream().map(MembershipDto.RoleDto::roleKey).toList());
        assertNull(roles.get(0).source(), "linha anterior à V75 mantém source nulo: a origem não é presumida");
    }

    @Test
    void ordemEstavel_naoDependeDaOrdemDoBanco() {
        project("SQ1", "Beta", "Seg", "Tribo");
        project("SQ2", "Alfa", "Seg", "Tribo");
        project("SQ3", "Gama", "Seg", "Tribo");
        row("1", "SQ1", "DEVELOPER", "Developer", false, null);
        row("2", "SQ2", "DEVELOPER", "Developer", false, null);
        row("3", "SQ3", "AGILE_MASTER", "Agile Master", true, null);

        List<String> first = service.forUser(user()).memberships().stream().map(MembershipDto::projectId).toList();
        java.util.Collections.reverse(rows);
        cache.evictAll();
        List<String> second = service.forUser(user()).memberships().stream().map(MembershipDto::projectId).toList();

        // liderança primeiro, depois pelo nome da squad
        assertEquals(List.of("SQ3", "SQ2", "SQ1"), first);
        assertEquals(first, second);
    }

    // ---------- expansão por tribo/segmento ----------

    @Test
    void liderancaTransversalExpandeParaATriboEOSegmento_comScope() {
        project("SQ1", "Squad Um", "SegA", "TriboA");
        project("SQ2", "Squad Dois", "SegB", "triboa");     // mesma tribo (sem caixa)
        project("SQ3", "Squad Tres", "SEGA", "TriboZ");     // mesmo segmento
        project("SQ4", "Squad Quatro", "SegX", "TriboX");   // fora
        row("1", "SQ1", "PEOPLE_LEAD", "People Lead", true, "JIRA");

        MembershipService.View view = service.forUser(user());

        assertTrue(view.transversalLeader());
        assertEquals(List.of("SQ1", "SQ2", "SQ3"), view.memberships().stream().map(MembershipDto::projectId).toList());
        MembershipDto tribe = view.memberships().get(1);
        assertEquals(MembershipDto.SCOPE_TRIBE, tribe.scope());
        assertFalse(tribe.direct());
        assertTrue(tribe.leadership());
        assertEquals("TRIBE_LEADER_ACCESS", tribe.roleKey());
        assertNull(tribe.source());
        assertEquals(MembershipDto.SCOPE_SEGMENT, view.memberships().get(2).scope());
        assertEquals(MembershipDto.SCOPE_SQUAD, view.memberships().get(0).scope());
    }

    @Test
    void liderancaQueNaoEhDeVerdade_naoExpande() {
        project("SQ1", "Squad Um", "SegA", "TriboA");
        project("SQ2", "Squad Dois", "SegA", "TriboA");
        // convite/backfill grava o nome do papel com isLeadership=false: não dá acesso à tribo
        row("1", "SQ1", "TRIBE_LEAD", "Tribe Lead", false, "INVITE");

        MembershipService.View view = service.forUser(user());

        assertFalse(view.transversalLeader());
        assertEquals(1, view.memberships().size());
    }

    // ---------- squad ativa ----------

    @Test
    void activeProjectId_preferenciaValida_invalidaEAusente() {
        project("SQ1", "Alfa", "Seg", "Tribo");
        project("SQ2", "Beta", "Seg", "Tribo");
        row("1", "SQ1", "AGILE_MASTER", "Agile Master", true, null);
        row("2", "SQ2", "DEVELOPER", "Developer", false, null);

        User u = user();
        u.setDefaultProjectId("SQ2");
        assertEquals("SQ2", service.forUser(u).activeProjectId(), "preferência válida vale");

        u.setDefaultProjectId("sq2");
        assertEquals("SQ2", service.forUser(u).activeProjectId(), "sem caixa, devolve o id canônico");

        u.setDefaultProjectId("OUTRA");
        assertEquals("SQ1", service.forUser(u).activeProjectId(), "fora dos vínculos cai no primeiro por precedência");

        u.setDefaultProjectId(null);
        assertEquals("SQ1", service.forUser(u).activeProjectId());
        u.setDefaultProjectId("  ");
        assertEquals("SQ1", service.forUser(u).activeProjectId());
    }

    @Test
    void semVinculoNenhum_activeProjectIdEhNulo() {
        User u = user();
        u.setDefaultProjectId("SQ9");

        MembershipService.View view = service.forUser(u);

        assertTrue(view.memberships().isEmpty());
        assertNull(view.activeProjectId());
    }

    // ---------- legacyOnly ----------

    @Test
    void legacyOnly_quandoSoTemSquadIdOuRoster() {
        User comSquadId = user();
        comSquadId.setSquadId("DDWMISSI");
        assertTrue(service.forUser(comSquadId).legacyOnly());

        User comRoster = User.builder().id("u2").email("bia@empresa.com.br").build();
        when(squadMemberRepository.findByUserIdentifier("bia@empresa.com.br")).thenReturn(List.of(new SquadMember()));
        assertTrue(service.forUser(comRoster).legacyOnly());

        User reivindicou = User.builder().id("u3").email("caio@empresa.com.br").build();
        when(squadMemberRepository.findByClaimedByUid("u3")).thenReturn(List.of(new SquadMember()));
        assertTrue(service.forUser(reivindicou).legacyOnly());
    }

    @Test
    void legacyOnly_falsoParaContaNovaSentinelaEParaQuemTemVinculo() {
        assertFalse(service.forUser(user()).legacyOnly(), "conta nova, sem rastro nenhum: vai para o onboarding");

        User sentinela = user();
        sentinela.setSquadId("Sem Time");
        assertFalse(service.forUser(sentinela).legacyOnly());

        project("SQ1", "Squad Um", "Seg", "Tribo");
        row("1", "SQ1", "DEVELOPER", "Developer", false, null);
        cache.evictAll(); // o teste grava pmr por fora do JPA, então faz a evicção que o listener faria
        User comVinculo = user();
        comVinculo.setSquadId("SQ1");
        assertFalse(service.forUser(comVinculo).legacyOnly());
    }

    // ---------- ponte e resposta ----------

    @Test
    void toAccess_mantemOContratoDoAccessibleProjects() {
        project("SQ1", "Squad Um", "SegA", "TriboA");
        row("1", "SQ1", "PRODUCT_OWNER", "Product Owner", true, "JIRA");

        User u = user();
        UserProjectAccessDto access = MembershipService.toAccess(u, service.forUser(u));

        assertEquals("SQ1", access.getPrimaryProjectId());
        UserProjectAccessDto.ProjectAccessItem item = access.getProjects().get(0);
        assertEquals("Squad Um", item.getProjectName());
        assertEquals("SegA", item.getSegmentName());
        assertEquals("TriboA", item.getTribeName());
        assertEquals("Product Owner", item.getRoleName());
        assertTrue(item.isDirectAssignment());
        assertTrue(item.isLeadership());
    }

    @Test
    void responseFor_trazVersaoDoContratoE401ParaContaInexistente() {
        project("SQ1", "Squad Um", "SegA", "TriboA");
        row("1", "SQ1", "DEVELOPER", "Developer", false, "JIRA");
        when(userRepository.findById("u1")).thenReturn(Optional.of(user()));
        when(userRepository.findById("fantasma")).thenReturn(Optional.empty());

        MembershipsResponseDto response = service.responseFor("u1");

        assertEquals("1", response.contractVersion());
        assertEquals("SQ1", response.activeProjectId());
        assertFalse(response.legacyOnly());
        assertEquals(1, response.memberships().size());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.responseFor("fantasma"));
        assertEquals(401, ex.getStatusCode().value());
    }

    @Test
    void modoDesconhecidoCaiEmLegacy_eVazioEhShadow() {
        assertEquals(MembershipMode.SHADOW, MembershipMode.parse(null));
        assertEquals(MembershipMode.SHADOW, MembershipMode.parse(" "));
        assertEquals(MembershipMode.NEW, MembershipMode.parse("NEW"));
        assertEquals(MembershipMode.LEGACY, MembershipMode.parse("nwe"));
    }

    // ---------- cache ----------

    @Test
    void segundaLeituraUsaOCache_eMudancaDeEmailInvalidaAEntrada() {
        project("SQ1", "Squad Um", "SegA", "TriboA");
        row("1", "SQ1", "DEVELOPER", "Developer", false, null);
        User u = user();

        service.forUser(u);
        service.forUser(u);

        verify(pmrRepository, times(1)).findByEmailIgnoreCase(anyString());
        assertEquals(1, cache.hits());

        u.setEmail("outro@empresa.com.br");
        service.forUser(u);
        verify(pmrRepository, times(2)).findByEmailIgnoreCase(anyString());
    }

    @Test
    void computeFresh_naoUsaNemPopulaOCache() {
        project("SQ1", "Squad Um", "SegA", "TriboA");
        row("1", "SQ1", "DEVELOPER", "Developer", false, null);

        service.computeFresh(user());
        service.computeFresh(user());

        verify(pmrRepository, times(2)).findByEmailIgnoreCase(anyString());
        assertEquals(0, cache.size());
        verify(userRepository, never()).save(any());
    }
}
