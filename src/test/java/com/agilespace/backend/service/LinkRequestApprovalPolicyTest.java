package com.agilespace.backend.service;

import com.agilespace.backend.domain.LinkRequest;
import com.agilespace.backend.domain.ProjectConfig;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.MembershipDto;
import com.agilespace.backend.dto.MembershipDto.RoleDto;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LinkRequestApprovalPolicy - quem decide pedido de vínculo (allowlist fixa, só vínculos com liderança)")
class LinkRequestApprovalPolicyTest {

    @Mock private MembershipService membershipService;
    @Mock private ProjectConfigRepository projectConfigRepository;
    @Mock private ProjectMemberRoleRepository projectMemberRoleRepository;
    @Mock private UserRepository userRepository;

    private LinkRequestApprovalPolicy policy;

    @BeforeEach
    void setup() {
        policy = new LinkRequestApprovalPolicy(membershipService, projectConfigRepository, projectMemberRoleRepository,
                userRepository, "on");
    }

    private static User user(String id, String role) {
        return User.builder().id(id).email(id + "@totvs.com.br").name("Pessoa " + id).role(role).build();
    }

    private static RoleDto role(String key, boolean leadership) {
        return new RoleDto(key, key, leadership, "JIRA", true);
    }

    /** Vínculo direto (scope SQUAD) numa squad da tribo informada. */
    private static MembershipDto direct(String squad, String tribe, RoleDto... roles) {
        RoleDto first = roles[0];
        return new MembershipDto(squad, squad, "Seg", tribe, first.roleName(), first.roleKey(), first.leadership(), true,
                MembershipDto.SCOPE_SQUAD, "JIRA", List.of(roles));
    }

    private void squadTribe(String squad, String tribe) {
        lenient().when(projectConfigRepository.findById(squad))
                .thenReturn(Optional.of(ProjectConfig.builder().id(squad).name(squad).tribeName(tribe).build()));
    }

    private User caller(String id, MembershipDto... memberships) {
        User u = user(id, "MEMBER");
        when(membershipService.membershipsOf(u)).thenReturn(List.of(memberships));
        return u;
    }

    @Test
    @DisplayName("A allowlist é fixa no código: AM/SM/PO/PL aprovam contribuidor; só PL/TL aprovam liderança")
    void allowlistsAreFixed() {
        assertEquals(Set.of("AGILE_MASTER", "SCRUM_MASTER", "PRODUCT_OWNER", "PEOPLE_LEAD"), LinkRequestApprovalPolicy.SQUAD_APPROVER_ROLE_KEYS);
        assertEquals(Set.of("PEOPLE_LEAD", "TRIBE_LEAD"), LinkRequestApprovalPolicy.LEADERSHIP_APPROVER_ROLE_KEYS);
    }

    @Test
    @DisplayName("Agile Master, Scrum Master e Product Owner da squad aprovam contribuidor, não liderança")
    void squadLeadersApproveContributorOnly() {
        squadTribe("SQ", "Tribo A");
        for (String key : List.of("AGILE_MASTER", "SCRUM_MASTER", "PRODUCT_OWNER")) {
            User u = caller("u-" + key, direct("SQ", "Tribo A", role(key, true)));
            LinkRequestApprovalPolicy.Eligibility e = policy.eligibility(u, "SQ");
            assertTrue(e.canApprove(), key);
            assertFalse(e.canApproveLeadership(), key);
            assertTrue(policy.canDecide(u, "SQ", LinkRequest.KIND_CONTRIBUTOR), key);
            assertFalse(policy.canDecide(u, "SQ", LinkRequest.KIND_LEADERSHIP), key);
        }
    }

    @Test
    @DisplayName("People Lead e Tribe Lead aprovam contribuidor e liderança")
    void peopleAndTribeLeadApproveBoth() {
        squadTribe("SQ", "Tribo A");
        for (String key : List.of("PEOPLE_LEAD", "TRIBE_LEAD")) {
            User u = caller("u-" + key, direct("SQ", "Tribo A", role(key, true)));
            LinkRequestApprovalPolicy.Eligibility e = policy.eligibility(u, "SQ");
            assertTrue(e.canApprove(), key);
            assertTrue(e.canApproveLeadership(), key);
        }
    }

    @Test
    @DisplayName("Developer, QA e papel de liderança sem is_leadership (autodeclarado/convite) não aprovam nada")
    void nonLeadershipDoesNotApprove() {
        squadTribe("SQ", "Tribo A");
        User dev = caller("dev", direct("SQ", "Tribo A", role("DEVELOPER", false)));
        assertFalse(policy.eligibility(dev, "SQ").canApprove());
        // roleKey de AM, mas a linha NÃO é de liderança (convite/backfill): sem poder
        User fakeAm = caller("fake", direct("SQ", "Tribo A", role("AGILE_MASTER", false)));
        assertFalse(policy.eligibility(fakeAm, "SQ").canApprove());
        User fakePl = caller("fakepl", direct("SQ", "Tribo A", role("PEOPLE_LEAD", false)));
        assertFalse(policy.eligibility(fakePl, "SQ").canApprove());
    }

    @Test
    @DisplayName("Agile Coach tem acesso transversal mas não está na allowlist: não aprova")
    void agileCoachDoesNotApprove() {
        squadTribe("SQ", "Tribo A");
        User coach = caller("coach", direct("SQ", "Tribo A", role("AGILE_COACH", true)));
        LinkRequestApprovalPolicy.Eligibility e = policy.eligibility(coach, "SQ");
        assertFalse(e.canApprove());
        assertFalse(e.canApproveLeadership());
    }

    @Test
    @DisplayName("AM de outra squad (de outra tribo) não aprova; o papel vale na squad do pedido")
    void otherSquadDoesNotApprove() {
        squadTribe("SQ", "Tribo A");
        User am = caller("am", direct("OUTRA", "Tribo B", role("AGILE_MASTER", true)));
        assertFalse(policy.eligibility(am, "SQ").canApprove());
    }

    @Test
    @DisplayName("AM de outra squad da MESMA tribo também não aprova (só PL/TL expandem pela tribo)")
    void amOfSiblingSquadDoesNotApprove() {
        squadTribe("SQ", "Tribo A");
        User am = caller("am", direct("IRMA", "Tribo A", role("AGILE_MASTER", true)));
        assertFalse(policy.eligibility(am, "SQ").canApprove());
    }

    @Test
    @DisplayName("People Lead / Tribe Lead de outra squad da mesma tribo aprovam (expansão de tribo); de outra tribo, não")
    void tribeExpansion() {
        squadTribe("SQ", "Tribo A");
        for (String key : List.of("PEOPLE_LEAD", "TRIBE_LEAD")) {
            User sameTribe = caller("same-" + key, direct("IRMA", "tribo a", role(key, true)));
            LinkRequestApprovalPolicy.Eligibility e = policy.eligibility(sameTribe, "SQ");
            assertTrue(e.canApprove(), key);
            assertTrue(e.canApproveLeadership(), key);
            User otherTribe = caller("other-" + key, direct("IRMA", "Tribo B", role(key, true)));
            assertFalse(policy.eligibility(otherTribe, "SQ").canApprove(), key);
        }
    }

    @Test
    @DisplayName("Tribo vazia nunca casa por expansão")
    void blankTribeNeverMatches() {
        squadTribe("SQ", "");
        User pl = caller("pl", direct("IRMA", "", role("PEOPLE_LEAD", true)));
        assertFalse(policy.eligibility(pl, "SQ").canApprove());
    }

    @Test
    @DisplayName("Vínculo alcançado só por expansão (direct=false) não é papel: não conta como linha própria")
    void expandedMembershipIsNotARole() {
        squadTribe("SQ", "Tribo A");
        MembershipDto expanded = new MembershipDto("SQ", "SQ", "Seg", "Tribo A", "Liderança Transversal da Tribo",
                "TRIBE_LEADER_ACCESS", true, false, MembershipDto.SCOPE_TRIBE, null,
                List.of(new RoleDto("Liderança Transversal da Tribo", "TRIBE_LEADER_ACCESS", true, null, true)));
        User u = caller("coach", expanded);
        assertFalse(policy.eligibility(u, "SQ").canApprove());
    }

    @Test
    @DisplayName("Alias MISSI e DDWMISSI são a mesma squad")
    void missiAlias() {
        squadTribe("DDWMISSI", "Tribo A");
        User am = caller("am", direct("DDWMISSI", "Tribo A", role("AGILE_MASTER", true)));
        assertTrue(policy.eligibility(am, "MISSI").canApprove());
    }

    @Test
    @DisplayName("ADMIN aprova tudo; com a flag off só ADMIN aprova")
    void adminAndFlagOff() {
        User admin = user("adm", "ADMIN");
        assertEquals(new LinkRequestApprovalPolicy.Eligibility(true, true), policy.eligibility(admin, "SQ"));
        verifyNoInteractions(membershipService);

        LinkRequestApprovalPolicy off = new LinkRequestApprovalPolicy(membershipService, projectConfigRepository,
                projectMemberRoleRepository, userRepository, "off");
        assertFalse(off.squadApprovalEnabled());
        assertEquals(new LinkRequestApprovalPolicy.Eligibility(true, true), off.eligibility(admin, "SQ"));
        User am = user("am", "MEMBER");
        assertEquals(new LinkRequestApprovalPolicy.Eligibility(false, false), off.eligibility(am, "SQ"));
        verifyNoInteractions(membershipService);
        assertTrue(off.approverUserIds("SQ", LinkRequest.KIND_CONTRIBUTOR).isEmpty());
        verifyNoInteractions(projectMemberRoleRepository);
    }

    @Test
    @DisplayName("Valores da flag: vazio/on ligam; off e valores desconhecidos desligam")
    void flagParsing() {
        assertTrue(LinkRequestApprovalPolicy.parseFlag(null));
        assertTrue(LinkRequestApprovalPolicy.parseFlag(" "));
        assertTrue(LinkRequestApprovalPolicy.parseFlag("on"));
        assertTrue(LinkRequestApprovalPolicy.parseFlag(" ON "));
        assertFalse(LinkRequestApprovalPolicy.parseFlag("off"));
        assertFalse(LinkRequestApprovalPolicy.parseFlag("talvez"));
    }

    @Test
    @DisplayName("Sem chamador ou sem squad: ninguém aprova")
    void nullSafe() {
        assertEquals(new LinkRequestApprovalPolicy.Eligibility(false, false), policy.eligibility(null, "SQ"));
        assertEquals(new LinkRequestApprovalPolicy.Eligibility(false, false), policy.eligibility(user("x", "MEMBER"), " "));
    }

    // ---------- destinatários ----------

    private static ProjectMemberRole pmr(String project, String key, boolean leadership, String userId, String email, String jira) {
        return ProjectMemberRole.builder().id(project + key).projectId(project).roleName(key).roleKey(key).displayName("X")
                .isLeadership(leadership).userId(userId).email(email).jiraAccountId(jira).build();
    }

    @Test
    @DisplayName("Aprovadores de contribuidor: AM/SM/PO/PL da squad + PL/TL da tribo; ignora Developer, não-liderança e AM de squad irmã")
    void approversForContributor() {
        squadTribe("SQ", "Tribo A");
        when(projectConfigRepository.findByTribeNameIgnoreCase("Tribo A")).thenReturn(List.of(
                ProjectConfig.builder().id("SQ").build(), ProjectConfig.builder().id("IRMA").build()));
        when(projectMemberRoleRepository.findByProjectIdsUpper(any())).thenReturn(List.of(
                pmr("SQ", "AGILE_MASTER", true, "am", null, null),
                pmr("SQ", "PRODUCT_OWNER", true, null, "po@totvs.com.br", null),
                pmr("SQ", "DEVELOPER", false, "dev", null, null),
                pmr("SQ", "SCRUM_MASTER", false, "falso", null, null),
                pmr("SQ", "AGILE_COACH", true, "coach", null, null),
                pmr("IRMA", "PEOPLE_LEAD", true, null, null, "jira-pl"),
                pmr("IRMA", "AGILE_MASTER", true, "am-irma", null, null)));
        when(userRepository.findActiveByIds(Set.of("am"))).thenReturn(List.of(user("am", "MEMBER")));
        when(userRepository.findActiveByEmails(Set.of("po@totvs.com.br"))).thenReturn(List.of(user("po", "MEMBER")));
        when(userRepository.findActiveByJiraAccountIds(Set.of("jira-pl"))).thenReturn(List.of(user("pl", "MEMBER")));

        assertEquals(Set.of("am", "po", "pl"), policy.approverUserIds("sq", LinkRequest.KIND_CONTRIBUTOR));
    }

    @Test
    @DisplayName("Aprovadores de liderança: só PL/TL; AM e PO da squad não são avisados")
    void approversForLeadership() {
        squadTribe("SQ", "Tribo A");
        when(projectConfigRepository.findByTribeNameIgnoreCase("Tribo A")).thenReturn(List.of(ProjectConfig.builder().id("SQ").build()));
        when(projectMemberRoleRepository.findByProjectIdsUpper(any())).thenReturn(List.of(
                pmr("SQ", "AGILE_MASTER", true, "am", null, null),
                pmr("SQ", "PRODUCT_OWNER", true, "po", null, null),
                pmr("SQ", "PEOPLE_LEAD", true, "pl", null, null)));
        when(userRepository.findActiveByIds(Set.of("pl"))).thenReturn(List.of(user("pl", "MEMBER")));

        assertEquals(Set.of("pl"), policy.approverUserIds("SQ", LinkRequest.KIND_LEADERSHIP));
    }

    @Test
    @DisplayName("Sem nenhuma linha aprovadora: conjunto vazio (o gatilho cai em ADMIN)")
    void noApprovers() {
        squadTribe("SQ", "");
        when(projectMemberRoleRepository.findByProjectIdsUpper(any())).thenReturn(List.of(
                pmr("SQ", "DEVELOPER", false, "dev", null, null)));
        assertTrue(policy.approverUserIds("SQ", LinkRequest.KIND_CONTRIBUTOR).isEmpty());
        verifyNoInteractions(userRepository);
    }
}
