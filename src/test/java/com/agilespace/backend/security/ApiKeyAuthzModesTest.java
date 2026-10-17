package com.agilespace.backend.security;

import com.agilespace.backend.controller.ApiKeyController;
import com.agilespace.backend.domain.ApiKey;
import com.agilespace.backend.domain.ApiKeyScope;
import com.agilespace.backend.domain.KnowledgeDocument;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.ApiKeyRepository;
import com.agilespace.backend.repository.KnowledgeConversationRepository;
import com.agilespace.backend.repository.KnowledgeDocumentVersionRepository;
import com.agilespace.backend.repository.KnowledgeRepository;
import com.agilespace.backend.repository.KnowledgeTokenUsageRepository;
import com.agilespace.backend.repository.KnowledgeUserAiSettingsRepository;
import com.agilespace.backend.service.AuthzMode;
import com.agilespace.backend.service.AuthzWorld;
import com.agilespace.backend.service.KnowledgeService;
import com.agilespace.backend.service.SquadAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Etapa 2 do vínculo: squad da chave de API e squad de autoria do Conhecimento vêm de {@code users.squad_id} (legacy,
 * shadow) ou da squad ATIVA dos vínculos (new). A base de conhecimento continua compartilhada: só muda de onde vem a
 * squad gravada no documento (que decide quem edita), nunca quem lê.
 */
@DisplayName("Chave de API e Conhecimento - squad do usuário nos modos legacy | shadow | new")
class ApiKeyAuthzModesTest {

    private AuthzWorld world;
    private ApiKeyRepository keys;

    @BeforeEach
    void setUp() {
        world = new AuthzWorld();
        world.project("SQ1", "Alfa", "SegA", "TriboA").project("SQ2", "Beta", "SegA", "TriboA");
        world.user("dev", "MEMBER", "SQ1");
        world.link("r-dev", "dev", "SQ1", "DEVELOPER", "Developer", false);
        world.user("session", "MEMBER", "SQ1"); // users.squad_id solto, sem vínculo
        world.user("outsider", "MEMBER", null);
        keys = mock(ApiKeyRepository.class);
        when(keys.save(any(ApiKey.class))).thenAnswer(i -> {
            ApiKey k = i.getArgument(0);
            if (k.getId() == null) k.setId(UUID.randomUUID());
            if (k.getCreatedAt() == null) k.setCreatedAt(LocalDateTime.now());
            return k;
        });
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private static <T> T pick(AuthzMode mode, T legacy, T neu) {
        return mode == AuthzMode.NEW ? neu : legacy;
    }

    private static MockHttpServletRequest as(String userId, String role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtAuthenticationFilter.ATTR_USER_ID, userId);
        request.setAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE, role);
        return request;
    }

    private ResponseEntity<Map<String, Object>> create(AuthzMode mode, String userId) {
        SquadAccessService access = world.access(mode);
        ApiKeyLifecycle lifecycle = new ApiKeyLifecycle(keys, world.userRepository);
        ReflectionTestUtils.setField(lifecycle, "squadAccessService", access);
        ApiKeyController controller = new ApiKeyController(keys, world.userRepository, lifecycle, access);
        return controller.create(Map.of("name", "minha chave", "scopes", List.of("SQUAD_READ")), as(userId, "MEMBER"));
    }

    @ParameterizedTest
    @EnumSource(AuthzMode.class)
    @DisplayName("Criar chave SQUAD_READ: a squad travada é a de users.squad_id (legado) ou a ativa dos vínculos (new)")
    void createKeyLocksActiveSquad(AuthzMode mode) {
        ResponseEntity<Map<String, Object>> withLink = create(mode, "dev");
        assertEquals(201, withLink.getStatusCode().value());
        assertEquals("SQ1", withLink.getBody().get("squadId"));

        ResponseEntity<Map<String, Object>> soltoNoCadastro = create(mode, "session");
        assertEquals(pick(mode, 201, 400), soltoNoCadastro.getStatusCode().value(), "só users.squad_id: new pede vínculo");
        if (mode == AuthzMode.NEW) {
            assertTrue(String.valueOf(soltoNoCadastro.getBody().get("error")).contains("Vincule-se"));
        } else {
            assertEquals("SQ1", soltoNoCadastro.getBody().get("squadId"));
        }

        assertEquals(400, create(mode, "outsider").getStatusCode().value(), "sem squad nenhuma: recusa em todos os modos");
    }

    @ParameterizedTest
    @EnumSource(AuthzMode.class)
    @DisplayName("Renovar chave sem squad travada: legado trava em users.squad_id; new trava na ativa e, sem vínculo, tira SQUAD_READ")
    void renewLocksSquadOrDropsScope(AuthzMode mode) {
        SquadAccessService access = world.access(mode);
        ApiKeyLifecycle lifecycle = new ApiKeyLifecycle(keys, world.userRepository);
        ReflectionTestUtils.setField(lifecycle, "squadAccessService", access);

        ApiKey ofDev = ApiKey.builder().id(UUID.randomUUID()).name("k").keyHash("h").ownerUserId("dev").ownerRole("MEMBER")
                .scopes(new HashSet<>(Set.of(ApiKeyScope.SQUAD_READ.name()))).build();
        assertEquals("SQ1", lifecycle.renew(ofDev, 90, false).getSquadId());

        ApiKey ofSession = ApiKey.builder().id(UUID.randomUUID()).name("k").keyHash("h").ownerUserId("session").ownerRole("MEMBER")
                .scopes(new HashSet<>(Set.of(ApiKeyScope.SQUAD_READ.name()))).build();
        ApiKey renewed = lifecycle.renew(ofSession, 90, false);
        if (mode == AuthzMode.NEW) {
            assertNull(renewed.getSquadId());
            assertFalse(renewed.getScopes().contains(ApiKeyScope.SQUAD_READ.name()), "sem squad para travar, a chave não fica irrestrita");
        } else {
            assertEquals("SQ1", renewed.getSquadId());
            assertTrue(renewed.getScopes().contains(ApiKeyScope.SQUAD_READ.name()));
        }
    }

    @ParameterizedTest
    @EnumSource(AuthzMode.class)
    @DisplayName("Conhecimento: a squad gravada no documento novo vem de users.squad_id (legado) ou da squad ativa (new)")
    void knowledgeDocumentSquad(AuthzMode mode) {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        when(repository.save(any(KnowledgeDocument.class))).thenAnswer(i -> {
            KnowledgeDocument saved = i.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        KnowledgeService service = new KnowledgeService(repository, mock(KnowledgeConversationRepository.class),
                mock(KnowledgeUserAiSettingsRepository.class), mock(KnowledgeTokenUsageRepository.class), new ObjectMapper(),
                mock(KnowledgeDocumentVersionRepository.class), world.access(mode), world.userRepository);

        KnowledgeDocument byDev = service.saveOrUpdateDocument(
                KnowledgeDocument.builder().title("A").content("x").authorId("dev").squadId("FORJADA").build());
        assertEquals("SQ1", byDev.getSquadId());

        KnowledgeDocument bySession = service.saveOrUpdateDocument(
                KnowledgeDocument.builder().title("B").content("x").authorId("session").squadId("FORJADA").build());
        assertEquals(pick(mode, "SQ1", null), bySession.getSquadId(), "só users.squad_id: em new o documento nasce sem squad");
    }
}
