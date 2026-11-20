package com.agilespace.backend.service;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.MembershipDto;
import com.agilespace.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Quem decide e quem conduz a Review: tudo por vínculo (MembershipService), nunca por cargo autodeclarado. */
@ExtendWith(MockitoExtension.class)
class ShowcaseAccessPolicyTest {

    @Mock private UserRepository userRepository;
    @Mock private MembershipService membershipService;

    private ShowcaseAccessPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new ShowcaseAccessPolicy(userRepository, membershipService, "enforce");
    }

    private User user(String id, String role) {
        User u = User.builder().id(id).name("Pessoa " + id).role(role).jobTitle("Product Owner").build();
        lenient().when(userRepository.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    private static MembershipDto.RoleDto role(String name, String key, boolean leadership) {
        return new MembershipDto.RoleDto(name, key, leadership, "MANUAL", false);
    }

    private void membership(User u, String squad, MembershipDto.RoleDto... roles) {
        MembershipDto.RoleDto primary = roles[0];
        MembershipDto m = new MembershipDto(squad, squad, "Seg", "Tribo", primary.roleName(), primary.roleKey(), primary.leadership(),
                true, MembershipDto.SCOPE_SQUAD, "MANUAL", List.of(roles));
        when(membershipService.membershipIn(u, squad)).thenReturn(m);
    }

    @Test
    void adminCanAlways() {
        user("adm", "ADMIN");

        assertTrue(policy.canDecide("SQ1", "criador", "adm"));
        assertTrue(policy.canDecide(null, "criador", "adm"));
        assertTrue(policy.isAdminOrSquadLeader("SQ1", "adm"));
        verifyNoInteractions(membershipService);
    }

    @Test
    void productOwnerAndSmeByLinkCanDecide() {
        User po = user("po", "MEMBER");
        membership(po, "SQ1", role("Product Owner", "PRODUCT_OWNER", true));
        User sme = user("sme", "MEMBER");
        membership(sme, "SQ1", role("SME", "SME", false));

        assertTrue(policy.canDecide("SQ1", "criador", "po"));
        assertTrue(policy.canDecide("SQ1", "criador", "sme"));
    }

    @Test
    void smeAsSecondaryRoleStillCounts() {
        User u = user("dupla", "MEMBER");
        membership(u, "SQ1", role("Developer", "DEVELOPER", false), role("SME", "SME", false));

        assertTrue(policy.canDecide("SQ1", "criador", "dupla"));
        assertFalse(policy.isAdminOrSquadLeader("SQ1", "dupla"), "SME não é liderança");
    }

    @Test
    void roleNameVariantsTheClientAcceptsAreAccepted() {
        User u = user("po2", "MEMBER");
        membership(u, "SQ1", role("Product Owner (PO)", "OUTRA_CHAVE", false));

        assertTrue(policy.canDecide("SQ1", "criador", "po2"));
    }

    @Test
    void squadLeadershipByLinkCanDecideAndConduct() {
        User am = user("am", "MEMBER");
        membership(am, "SQ1", role("Agile Master", "AGILE_MASTER", true));

        assertTrue(policy.canDecide("SQ1", "criador", "am"));
        assertTrue(policy.isAdminOrSquadLeader("SQ1", "am"));
    }

    @Test
    void developerCannotDecideNorConduct() {
        User dev = user("dev", "MEMBER");
        membership(dev, "SQ1", role("Developer", "DEVELOPER", false));

        assertFalse(policy.canDecide("SQ1", "criador", "dev"));
        assertFalse(policy.isAdminOrSquadLeader("SQ1", "dev"));
    }

    @Test
    void jobTitleSaysNothing() {
        // user() já grava jobTitle "Product Owner": sem vínculo na squad não vale.
        User autodeclarado = user("fake", "MEMBER");
        when(membershipService.membershipIn(autodeclarado, "SQ1")).thenReturn(null);

        assertFalse(policy.canDecide("SQ1", "criador", "fake"));
        assertFalse(policy.isAdminOrSquadLeader("SQ1", "fake"));
    }

    @Test
    void linkInAnotherSquadDoesNotCount() {
        User po = user("po-sq2", "MEMBER");
        when(membershipService.membershipIn(po, "SQ1")).thenReturn(null);

        assertFalse(policy.canDecide("SQ1", "criador", "po-sq2"));
    }

    @Test
    void reviewWithoutSquadOnlyCreatorAndAdmin() {
        user("criador", "MEMBER");
        user("outro", "MEMBER");

        assertTrue(policy.canDecide(null, "criador", "criador"));
        assertTrue(policy.canDecide("  ", "criador", "criador"));
        assertFalse(policy.canDecide(null, "criador", "outro"));
        verifyNoInteractions(membershipService);
    }

    @Test
    void unknownOrAnonymousCaller() {
        when(userRepository.findById("fantasma")).thenReturn(Optional.empty());

        assertFalse(policy.canDecide("SQ1", "criador", "fantasma"));
        assertFalse(policy.canDecide("SQ1", "criador", null));
        assertFalse(policy.isAdminOrSquadLeader("SQ1", null));
        assertFalse(policy.isAdminOrSquadLeader("SQ1", "fantasma"));
    }

    @Test
    void lockOffAllowsEveryone() {
        ShowcaseAccessPolicy off = new ShowcaseAccessPolicy(userRepository, membershipService, " OFF ");

        assertFalse(off.decisionLockEnforced());
        assertTrue(off.canDecide("SQ1", "criador", "qualquer"));
        verifyNoInteractions(userRepository, membershipService);
    }

    @Test
    void anyOtherValueKeepsTheLockOn() {
        assertTrue(new ShowcaseAccessPolicy(userRepository, membershipService, "").decisionLockEnforced());
        assertTrue(new ShowcaseAccessPolicy(userRepository, membershipService, null).decisionLockEnforced());
        assertTrue(new ShowcaseAccessPolicy(userRepository, membershipService, "shadow").decisionLockEnforced());
    }
}
