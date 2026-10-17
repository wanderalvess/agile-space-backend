package com.agilespace.backend.service;

import com.agilespace.backend.controller.ApiKeyController;
import com.agilespace.backend.controller.InviteController;
import com.agilespace.backend.controller.SquadController;
import com.agilespace.backend.domain.ApiKey;
import com.agilespace.backend.domain.Invite;
import com.agilespace.backend.domain.PokerRoom;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.domain.UserRole;
import com.agilespace.backend.repository.ApiKeyRepository;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.repository.InviteRepository;
import com.agilespace.backend.repository.PokerChatMessageRepository;
import com.agilespace.backend.repository.PokerParticipantRepository;
import com.agilespace.backend.repository.PokerRoomRepository;
import com.agilespace.backend.repository.PokerRoundRepository;
import com.agilespace.backend.repository.PokerVoteRepository;
import com.agilespace.backend.security.ApiKeyLifecycle;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.security.LeadLevel;
import com.agilespace.backend.security.LeadPolicy;
import com.agilespace.backend.websocket.PokerWebSocketHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * Etapa 4 do vínculo: {@code app.membership.lead-level}. Com {@code off} o nível de conta LEAD não concede NADA (é um
 * MEMBER): sem vínculo não acessa squad alheia, não convida, não gerencia, não facilita o Poker e não emite chave com
 * escopo amplo; com vínculo de liderança o poder vem do vínculo. Com {@code legacy} tudo é idêntico ao de antes.
 * Fixture: {@code lead} (LEAD sem vínculo), {@code leadlink} (LEAD com liderança em SQ1), {@code am} (MEMBER com a mesma
 * liderança), {@code dev} (contribuidor de SQ1), {@code admin}.
 */
@DisplayName("Etapa 4 - lead-level off x legacy nos pontos que o LEAD concedia")
class LeadLevelPolicyTest {

    private AuthzWorld world;

    @BeforeEach
    void setUp() {
        world = new AuthzWorld();
        world.project("SQ1", "Alfa", "SegA", "TriboA")
                .project("SQ2", "Beta", "SegA", "TriboA")
                .project("SQ3", "Gama", "SegZ", "TriboZ");
        world.user("admin", "ADMIN", null);
        world.user("lead", "LEAD", null);
        world.user("leadlink", "LEAD", null);
        world.link("r-ll", "leadlink", "SQ1", "AGILE_MASTER", "Agile Master", true);
        world.user("am", "MEMBER", "SQ1");
        world.link("r-am", "am", "SQ1", "AGILE_MASTER", "Agile Master", true);
        world.user("dev", "MEMBER", "SQ1");
        world.link("r-dev", "dev", "SQ1", "DEVELOPER", "Developer", false);
        world.user("outsider", "MEMBER", null);
        when(world.pmrRepository.findLeadershipRows()).thenAnswer(i -> world.pmr.stream().filter(ProjectMemberRole::isLeadership).toList());
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    // ---------- chave e leitura do nível ----------

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "off", "OFF", " Off "})
    @DisplayName("lead-level: vazio ou off = OFF (o padrão do application.yml)")
    void parseOff(String raw) {
        assertEquals(LeadLevel.OFF, LeadLevel.parse(raw));
        assertFalse(LeadLevel.isUnknown(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"legacy", "LEGACY", " legacy "})
    @DisplayName("lead-level: legacy = LEGACY")
    void parseLegacy(String raw) {
        assertEquals(LeadLevel.LEGACY, LeadLevel.parse(raw));
        assertFalse(LeadLevel.isUnknown(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"of", "false", "0", "desligado", "legacy2", "on"})
    @DisplayName("lead-level: valor desconhecido cai em LEGACY (errar a digitação nunca remove acesso) e é sinalizado")
    void parseUnknownIsSafe(String raw) {
        assertEquals(LeadLevel.LEGACY, LeadLevel.parse(raw));
        assertTrue(LeadLevel.isUnknown(raw));
        assertTrue(new LeadPolicy(raw).isLegacy());
        assertTrue(new LeadPolicy(raw).isPrivileged("LEAD"));
    }

    @Test
    @DisplayName("LeadPolicy: ADMIN sempre privilegiado; LEAD só em legacy; MEMBER e nulo nunca")
    void policy() {
        for (LeadPolicy p : List.of(LeadPolicy.LEGACY, LeadPolicy.OFF)) {
            assertTrue(p.isPrivileged("ADMIN"));
            assertTrue(p.isPrivileged("admin"));
            assertFalse(p.isPrivileged("MEMBER"));
            assertFalse(p.isPrivileged(null));
        }
        assertTrue(LeadPolicy.LEGACY.isPrivileged("LEAD"));
        assertTrue(LeadPolicy.LEGACY.isPrivileged("lead"));
        assertFalse(LeadPolicy.OFF.isPrivileged("LEAD"));
        assertFalse(LeadPolicy.OFF.leadGrants("LEAD"));
        assertTrue(LeadPolicy.LEGACY.leadGrants("LEAD"));
        assertEquals(LeadLevel.OFF, new LeadPolicy("").level(), "vazio = padrão");
    }

    @Test
    @DisplayName("UserRole: ADMIN|MEMBER; LEAD legado é lido como MEMBER e não é atribuível")
    void userRole() {
        assertEquals(UserRole.ADMIN, UserRole.parse("admin"));
        assertEquals(UserRole.MEMBER, UserRole.parse("MEMBER"));
        assertEquals(UserRole.MEMBER, UserRole.parse("LEAD"), "LEAD legado = MEMBER");
        assertEquals(UserRole.MEMBER, UserRole.parse("lead"));
        assertEquals(UserRole.MEMBER, UserRole.parse(null));
        assertEquals(UserRole.MEMBER, UserRole.parse("qualquer-coisa"), "nunca concede privilégio por engano");
        assertTrue(UserRole.isValid("ADMIN"));
        assertTrue(UserRole.isValid("member"));
        assertFalse(UserRole.isValid("LEAD"));
        assertTrue(UserRole.isLegacyLead(" lead "));
        assertEquals(2, UserRole.values().length);
    }

    // ---------- SquadAccessService ----------

    @ParameterizedTest
    @EnumSource(AuthzMode.class)
    @DisplayName("off: LEAD sem vínculo não lê, não gerencia e não é liderança de squad alguma (qualquer authz)")
    void offLeadWithoutLinkHasNothing(AuthzMode mode) {
        SquadAccessService access = world.access(mode, LeadPolicy.OFF);

        for (String squad : List.of("SQ1", "SQ2", "SQ3")) {
            assertFalse(access.matchesSquad(squad, "lead", "LEAD"), "token LEAD, " + squad);
            assertFalse(access.matchesSquad(squad, "lead", "MEMBER"), "conta LEAD com token velho de MEMBER, " + squad);
            assertFalse(access.canManageSquad(squad, "lead", "LEAD"), "gerenciar " + squad);
            assertFalse(access.isSquadLeadership(squad, "lead", "LEAD"), "liderança " + squad);
        }
        assertTrue(access.matchesSquad("SQ3", "admin", "ADMIN"));
        assertTrue(access.canManageSquad("SQ3", "admin", "ADMIN"));
    }

    @ParameterizedTest
    @EnumSource(AuthzMode.class)
    @DisplayName("off: LEAD com vínculo de liderança tem o poder só onde tem o vínculo (e a expansão de tribo continua valendo)")
    void offLeadWithLinkHasPowerWhereLinked(AuthzMode mode) {
        SquadAccessService access = world.access(mode, LeadPolicy.OFF);

        assertTrue(access.matchesSquad("SQ1", "leadlink", "LEAD"));
        assertTrue(access.canManageSquad("SQ1", "leadlink", "LEAD"));
        assertTrue(access.isSquadLeadership("SQ1", "leadlink", "LEAD"));
        assertFalse(access.matchesSquad("SQ3", "leadlink", "LEAD"), "outra tribo: sem acesso");
        assertFalse(access.canManageSquad("SQ3", "leadlink", "LEAD"));
        // o MEMBER com a mesma liderança decide igual: o nível de conta deixou de importar
        assertEquals(access.matchesSquad("SQ1", "am", "MEMBER"), access.matchesSquad("SQ1", "leadlink", "LEAD"));
        assertEquals(access.canManageSquad("SQ1", "am", "MEMBER"), access.canManageSquad("SQ1", "leadlink", "LEAD"));
    }

    @ParameterizedTest
    @EnumSource(AuthzMode.class)
    @DisplayName("legacy: LEAD acessa e gerencia qualquer squad, como antes")
    void legacyLeadKeepsEverything(AuthzMode mode) {
        SquadAccessService access = world.access(mode, LeadPolicy.LEGACY);

        for (String squad : List.of("SQ1", "SQ3")) {
            assertTrue(access.matchesSquad(squad, "lead", "LEAD"));
            assertTrue(access.matchesSquad(squad, "lead", "MEMBER"), "conta LEAD, token desatualizado");
            assertTrue(access.canManageSquad(squad, "lead", "LEAD"));
        }
        assertFalse(access.isSquadLeadership("SQ1", "lead", "LEAD"), "liderança ESTRITA nunca foi do LEAD");
    }

    @Test
    @DisplayName("Sem a política montada (testes antigos), SquadAccessService é legacy")
    void defaultIsLegacy() {
        SquadAccessService access = world.access(AuthzMode.LEGACY);
        assertSame(LeadPolicy.LEGACY, access.leadPolicy());
        assertTrue(access.matchesSquad("SQ3", "lead", "LEAD"));
    }

    // ---------- SquadAudienceService ----------

    @ParameterizedTest
    @EnumSource(AuthzMode.class)
    @DisplayName("off: contextFor trata LEAD como MEMBER (sem LEAD/LEADERSHIP nas chaves, sem liderança); o vínculo vira LEADERSHIP")
    void offAudienceContext(AuthzMode mode) {
        SquadAudienceService audience = world.audience(mode, LeadPolicy.OFF);

        SquadAudienceService.UserContext lead = audience.contextFor("lead", "LEAD").orElseThrow();
        assertTrue(lead.roleKeys().contains("MEMBER"));
        assertFalse(lead.roleKeys().contains("LEAD"));
        assertFalse(lead.roleKeys().contains("LEADERSHIP"));
        assertFalse(lead.leadership());
        assertFalse(lead.multiSquad());

        SquadAudienceService.UserContext withLink = audience.contextFor("leadlink", "LEAD").orElseThrow();
        assertTrue(withLink.leadership());
        assertTrue(withLink.roleKeys().contains("LEADERSHIP"), "liderança = alguma linha is_leadership");
        assertFalse(withLink.roleKeys().contains("LEAD"));
        assertTrue(audience.contextFor("am", "MEMBER").orElseThrow().roleKeys().contains("LEADERSHIP"));
        assertFalse(audience.contextFor("dev", "MEMBER").orElseThrow().roleKeys().contains("LEADERSHIP"));
        assertTrue(audience.contextFor("outsider", "ADMIN").orElseThrow().leadership(), "ADMIN do token segue com liderança");
    }

    @ParameterizedTest
    @EnumSource(AuthzMode.class)
    @DisplayName("legacy: LEAD mantém LEAD e ganha LEADERSHIP (os avisos migrados continuam alcançando-o)")
    void legacyAudienceContext(AuthzMode mode) {
        SquadAudienceService.UserContext lead = world.audience(mode, LeadPolicy.LEGACY).contextFor("lead", "LEAD").orElseThrow();

        assertTrue(lead.roleKeys().contains("LEAD"));
        assertTrue(lead.roleKeys().contains("LEADERSHIP"));
        assertTrue(lead.leadership());
    }

    private void stubRoleQuery() {
        // findActiveIdsByRoles(roles): usuários ativos cujo nível de conta está entre as chaves informadas
        when(world.userRepository.findActiveIdsByRoles(anyCollection())).thenAnswer(i -> {
            @SuppressWarnings("unchecked") java.util.Collection<String> roles = (java.util.Collection<String>) i.getArgument(0);
            return world.users.stream().filter(User::isActive).filter(u -> roles.contains(u.getRole().toUpperCase())).map(User::getId).toList();
        });
        when(world.pmrRepository.findByRoleKeysUpper(anyCollection())).thenAnswer(i -> {
            @SuppressWarnings("unchecked") java.util.Collection<String> keys = (java.util.Collection<String>) i.getArgument(0);
            return world.pmr.stream().filter(r -> keys.contains(r.getRoleKey().toUpperCase())).toList();
        });
    }

    @Test
    @DisplayName("userIdsWithRoles: a chave derivada LEADERSHIP é quem tem alguma linha de liderança; com off o rótulo LEAD não casa ninguém")
    void leadershipDerivedKey() {
        stubRoleQuery();

        Set<String> off = world.audience(AuthzMode.LEGACY, LeadPolicy.OFF).userIdsWithRoles(List.of("LEADERSHIP"));
        assertEquals(Set.of("leadlink", "am"), off, "só quem tem vínculo de liderança (o LEAD sem vínculo fica de fora)");

        assertEquals(Set.of(), world.audience(AuthzMode.LEGACY, LeadPolicy.OFF).userIdsWithRoles(List.of("LEAD")),
                "off: LEAD é MEMBER, não é público de LEAD");
        Set<String> legacy = world.audience(AuthzMode.LEGACY, LeadPolicy.LEGACY).userIdsWithRoles(List.of("LEADERSHIP"));
        assertEquals(Set.of("lead", "leadlink", "am"), legacy, "legacy: o LEAD ainda vale 'liderança' (aviso migrado de LEAD)");
    }

    // ---------- AuthzDiffService ----------

    @Test
    @DisplayName("authz-diff: com off o LEAD entra na comparação (é um MEMBER); em legacy continua de fora")
    void diffTreatsLeadAsMemberWhenOff() {
        AuthzDiffService.Computation legacy = world.diff(LeadPolicy.LEGACY).compute();
        AuthzDiffService.Computation off = world.diff(LeadPolicy.OFF).compute();

        assertEquals(3, legacy.usersSkippedPrivileged(), "admin + lead + leadlink");
        assertEquals(1, off.usersSkippedPrivileged(), "só o admin");
        assertEquals(legacy.usersChecked() + 2, off.usersChecked());
    }

    // ---------- convites ----------

    private InviteController inviteController(AuthzMode mode, LeadPolicy policy, InviteService inviteService) {
        InviteController controller = new InviteController(inviteService, world.userRepository, world.pmrRepository,
                mock(AuthService.class), world.access(mode, policy), world.shadow(mode));
        controller.setLeadPolicy(policy);
        return controller;
    }

    private static MockHttpServletRequest as(String userId, String role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtAuthenticationFilter.ATTR_USER_ID, userId);
        request.setAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE, role);
        return request;
    }

    private int createInviteStatus(InviteController controller, String squad, String userId, String role) {
        try {
            ResponseEntity<Invite> response = controller.createInvite(squad, Map.of("roleName", "Developer"), as(userId, role));
            return response.getStatusCode().value();
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        }
    }

    @ParameterizedTest
    @EnumSource(AuthzMode.class)
    @DisplayName("Convite: off, LEAD sem vínculo não convida (403) e com vínculo só na squad dele; legacy convida em qualquer squad")
    void inviteAccess(AuthzMode mode) {
        InviteService inviteService = mock(InviteService.class);
        when(inviteService.createInvite(any(), any(), any(), any())).thenReturn(Invite.builder().id("novo").build());
        InviteController off = inviteController(mode, LeadPolicy.OFF, inviteService);
        InviteController legacy = inviteController(mode, LeadPolicy.LEGACY, inviteService);

        assertEquals(403, createInviteStatus(off, "SQ1", "lead", "LEAD"));
        assertEquals(403, createInviteStatus(off, "SQ3", "lead", "MEMBER"), "conta LEAD, token velho de MEMBER");
        assertEquals(201, createInviteStatus(off, "SQ1", "leadlink", "LEAD"), "liderança por vínculo");
        assertEquals(403, createInviteStatus(off, "SQ3", "leadlink", "LEAD"), "vínculo em SQ1 não convida em SQ3");
        assertEquals(201, createInviteStatus(off, "SQ3", "admin", "ADMIN"));
        assertEquals(403, createInviteStatus(off, "SQ1", "dev", "MEMBER"));

        assertEquals(201, createInviteStatus(legacy, "SQ3", "lead", "LEAD"), "legacy: idêntico ao de hoje");
        assertEquals(201, createInviteStatus(legacy, "SQ3", "lead", "MEMBER"));
    }

    @Test
    @DisplayName("Convite endereçado a outro e-mail: o LEAD só vê o e-mail do destinatário em legacy")
    void inviteDisplayPrivilege() {
        InviteRepository inviteRepository = mock(InviteRepository.class);
        Invite addressed = Invite.builder().id("i1").token("tok").squadId("SQ3").roleName("Developer")
                .email("alguem@outra.com").invitedBy("am").status("PENDING").build();
        when(inviteRepository.findByToken("tok")).thenReturn(Optional.of(addressed));
        for (AuthzMode mode : AuthzMode.values()) {
            InviteService service = new InviteService(inviteRepository, mock(AuditLogRepository.class), world.squadService,
                    world.projectRepository, world.pmrRepository, world.userRepository);
            service.setSquadAuthz(world.access(mode), world.shadow(mode));
            User lead = world.find("lead").orElseThrow();

            service.setLeadPolicy(LeadPolicy.LEGACY);
            assertEquals("alguem@outra.com", service.getForDisplay("tok", lead).getEmail(), mode + " legacy");

            service.setLeadPolicy(LeadPolicy.OFF);
            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.getForDisplay("tok", lead), mode + " off");
            assertEquals(404, ex.getStatusCode().value());
        }
    }

    // ---------- SquadController.by-user ----------

    @Test
    @DisplayName("by-user: LEAD consulta a squad de outra pessoa só em legacy; off = só a si mesmo (ou admin)")
    void byUser() {
        SquadService squadService = mock(SquadService.class);
        when(squadService.getSquadMembersForUser(any())).thenReturn(List.of());
        SquadController controller = new SquadController(squadService, null, null, null, world.userRepository, world.resolver,
                world.access(AuthzMode.LEGACY, LeadPolicy.OFF), null);

        controller.setLeadPolicy(LeadPolicy.LEGACY);
        assertEquals(200, controller.getSquadMembersForUser("qualquer@x.com", as("lead", "LEAD")).getStatusCode().value());

        controller.setLeadPolicy(LeadPolicy.OFF);
        assertThrows(ResponseStatusException.class, () -> controller.getSquadMembersForUser("qualquer@x.com", as("lead", "LEAD")));
        assertEquals(200, controller.getSquadMembersForUser("lead@totvs.com.br", as("lead", "LEAD")).getStatusCode().value(), "o próprio");
        assertEquals(200, controller.getSquadMembersForUser("qualquer@x.com", as("admin", "ADMIN")).getStatusCode().value());
    }

    // ---------- Poker: facilitador ----------

    private PokerService poker(LeadPolicy policy, PokerRoom room) {
        PokerRoomRepository rooms = mock(PokerRoomRepository.class);
        PokerParticipantRepository participants = mock(PokerParticipantRepository.class);
        when(rooms.findById(room.getId())).thenReturn(Optional.of(room));
        when(participants.findById(any())).thenReturn(Optional.empty());
        PokerService service = new PokerService(rooms, participants, mock(PokerVoteRepository.class), mock(PokerRoundRepository.class),
                mock(PokerChatMessageRepository.class), mock(PokerWebSocketHandler.class));
        service.setLeadPolicy(policy);
        service.setSquadAccessService(world.access(AuthzMode.LEGACY, policy));
        return service;
    }

    private static boolean canFacilitate(PokerService service, String roomId, String callerId, String role) {
        try {
            service.clearRounds(roomId, callerId, role);
            return true;
        } catch (ResponseStatusException e) {
            assertEquals(403, e.getStatusCode().value());
            return false;
        }
    }

    private static boolean canDelete(PokerService service, String roomId, String callerId, String role) {
        try {
            service.deleteRoom(roomId, callerId, role);
            return true;
        } catch (ResponseStatusException e) {
            assertEquals(403, e.getStatusCode().value());
            return false;
        }
    }

    private static PokerRoom room() {
        return PokerRoom.builder().id("room-1").title("Sala").creatorId("criador").team("SQ1").deckType("fibonacci").build();
    }

    @Test
    @DisplayName("Poker (off): LEAD sem vínculo não é facilitador; facilitador = criador, ADMIN ou liderança da squad da sala por vínculo")
    void pokerFacilitatorOff() {
        PokerService off = poker(LeadPolicy.OFF, room());

        assertFalse(canFacilitate(off, "room-1", "lead", "LEAD"), "LEAD sem vínculo");
        assertFalse(canFacilitate(off, "room-1", "dev", "MEMBER"), "contribuidor");
        assertFalse(canFacilitate(off, "room-1", "outsider", "MEMBER"));
        assertTrue(canFacilitate(off, "room-1", "criador", "MEMBER"), "o criador sempre");
        assertTrue(canFacilitate(off, "room-1", "admin", "ADMIN"));
        assertTrue(canFacilitate(off, "room-1", "leadlink", "LEAD"), "liderança de SQ1 por vínculo, sala de SQ1");
        assertTrue(canFacilitate(off, "room-1", "am", "MEMBER"), "o nível de conta não importa: o vínculo decide");
    }

    @Test
    @DisplayName("Poker (off): a liderança de OUTRA squad não facilita a sala; excluir a sala segue só do criador e do ADMIN")
    void pokerFacilitatorOtherSquadAndDelete() {
        world.user("leader3", "MEMBER", null);
        world.link("r-l3", "leader3", "SQ3", "AGILE_MASTER", "Agile Master", true);
        PokerService off = poker(LeadPolicy.OFF, room());

        assertFalse(canFacilitate(off, "room-1", "leader3", "MEMBER"), "liderança de SQ3, sala de SQ1");
        assertFalse(canDelete(poker(LeadPolicy.OFF, room()), "room-1", "lead", "LEAD"));
        assertFalse(canDelete(poker(LeadPolicy.OFF, room()), "room-1", "leadlink", "LEAD"), "excluir não é facilitar");
        assertTrue(canDelete(poker(LeadPolicy.OFF, room()), "room-1", "criador", "MEMBER"));
        assertTrue(canDelete(poker(LeadPolicy.OFF, room()), "room-1", "admin", "ADMIN"));
    }

    @Test
    @DisplayName("Poker (legacy): idêntico ao de hoje - LEAD facilita e exclui qualquer sala; liderança por vínculo NÃO é facilitadora")
    void pokerFacilitatorLegacy() {
        PokerService legacy = poker(LeadPolicy.LEGACY, room());

        assertTrue(canFacilitate(legacy, "room-1", "lead", "LEAD"));
        assertTrue(canDelete(poker(LeadPolicy.LEGACY, room()), "room-1", "lead", "LEAD"));
        assertFalse(canFacilitate(legacy, "room-1", "am", "MEMBER"), "como antes: sem LEAD nem criador, não facilita");
        assertFalse(canFacilitate(legacy, "room-1", "dev", "MEMBER"));
    }

    // ---------- Chaves de API ----------

    private ApiKeyRepository keys() {
        ApiKeyRepository keys = mock(ApiKeyRepository.class);
        when(keys.save(any(ApiKey.class))).thenAnswer(i -> {
            ApiKey k = i.getArgument(0);
            if (k.getId() == null) k.setId(UUID.randomUUID());
            if (k.getCreatedAt() == null) k.setCreatedAt(LocalDateTime.now());
            return k;
        });
        return keys;
    }

    private ApiKeyController keyController(ApiKeyRepository keys, LeadPolicy policy, ApiKeyLifecycle lifecycle) {
        SquadAccessService access = world.access(AuthzMode.LEGACY, policy);
        ApiKeyController controller = new ApiKeyController(keys, world.userRepository, lifecycle, access);
        controller.setLeadPolicy(policy);
        return controller;
    }

    private ApiKeyLifecycle lifecycle(ApiKeyRepository keys, LeadPolicy policy) {
        ApiKeyLifecycle lifecycle = new ApiKeyLifecycle(keys, world.userRepository);
        lifecycle.setLeadPolicy(policy);
        return lifecycle;
    }

    @Test
    @DisplayName("Chave de API: off, LEAD só emite escopos de leitura (e SQUAD_READ travada na própria squad); legacy emite tudo")
    void apiKeyCreate() {
        ApiKeyRepository keys = keys();
        ApiKeyController off = keyController(keys, LeadPolicy.OFF, lifecycle(keys, LeadPolicy.OFF));
        ApiKeyController legacy = keyController(keys, LeadPolicy.LEGACY, lifecycle(keys, LeadPolicy.LEGACY));
        Map<String, Object> wide = Map.of("name", "k", "scopes", List.of("POKER_WRITE", "KNOWLEDGE_WRITE"));

        assertEquals(403, off.create(wide, as("lead", "LEAD")).getStatusCode().value(), "off: escopo de escrita é de ADMIN");
        assertEquals(201, off.create(Map.of("name", "k", "scopes", List.of("KNOWLEDGE_READ")), as("lead", "LEAD")).getStatusCode().value());
        assertEquals(400, off.create(Map.of("name", "k", "scopes", List.of("SQUAD_READ")), as("lead", "LEAD")).getStatusCode().value(),
                "sem squad própria para travar");
        assertEquals(201, off.create(wide, as("admin", "ADMIN")).getStatusCode().value());

        assertEquals(201, legacy.create(wide, as("lead", "LEAD")).getStatusCode().value(), "legacy: idêntico ao de hoje");
        assertEquals(201, legacy.create(Map.of("name", "k", "scopes", List.of("SQUAD_READ"), "squadId", "SQ3"), as("lead", "LEAD"))
                .getStatusCode().value());
    }

    @Test
    @DisplayName("Chave de API: a renovação de um ex-LEAD corta os escopos de liderança (off); em legacy mantém tudo")
    void apiKeyRenewCutsScopes() {
        ApiKeyRepository keys = keys();
        Set<String> all = new HashSet<>(Set.of("KNOWLEDGE_READ", "POKER_WRITE", "SQUAD_READ"));

        ApiKey legacyKey = ApiKey.builder().id(UUID.randomUUID()).name("k").keyHash("h").ownerUserId("lead").ownerRole("LEAD")
                .scopes(new HashSet<>(all)).build();
        assertEquals(all, lifecycle(keys, LeadPolicy.LEGACY).renew(legacyKey, 90, false).getScopes());

        ApiKey offKey = ApiKey.builder().id(UUID.randomUUID()).name("k").keyHash("h").ownerUserId("lead").ownerRole("LEAD")
                .scopes(new HashSet<>(all)).squadId(null).build();
        ApiKey renewed = lifecycle(keys, LeadPolicy.OFF).renew(offKey, 90, false);
        assertFalse(renewed.getScopes().contains("POKER_WRITE"), "escopo de liderança cortado");
        assertTrue(renewed.getScopes().contains("KNOWLEDGE_READ"));
        assertFalse(renewed.getScopes().contains("SQUAD_READ"), "sem squad para travar, a chave não fica irrestrita");
    }

    // ---------- Sem regressão para ADMIN ----------

    @Test
    @DisplayName("ADMIN não muda com a chave: continua com tudo em off e em legacy")
    void adminUnchanged() {
        for (LeadPolicy p : List.of(LeadPolicy.OFF, LeadPolicy.LEGACY)) {
            SquadAccessService access = world.access(AuthzMode.NEW, p);
            assertTrue(access.matchesSquad("SQ3", "admin", "ADMIN"));
            assertTrue(access.canManageSquad("SQ3", "admin", "ADMIN"));
            assertTrue(access.isSquadLeadership("SQ3", "admin", "ADMIN"));
        }
    }
}
