package com.agilespace.backend.service;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.domain.UserJiraConfig;
import com.agilespace.backend.domain.UserTdnConfig;
import com.agilespace.backend.repository.UserJiraConfigRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.repository.UserTdnConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService - Gestão de Usuários, Permissões e Configurações de Integração (Jira/TDN)")
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserJiraConfigRepository jiraConfigRepository;

    @Mock
    private UserTdnConfigRepository tdnConfigRepository;

    @InjectMocks
    private UserService service;

    private User sampleUser;

    @BeforeEach
    void setUp() {
        sampleUser = User.builder()
                .id("user-123")
                .name("Wanderson Alves")
                .email("wanderson@example.com")
                .role("MEMBER")
                .active(true)
                .build();
    }

    @Nested
    @DisplayName("Consulta de Usuários")
    class GetUserTests {

        @Test
        @DisplayName("Deve retornar usuário existente quando ID for encontrado")
        void shouldReturnUserWhenExists() {
            when(userRepository.findById("user-123")).thenReturn(Optional.of(sampleUser));

            User result = service.getUser("user-123");

            assertNotNull(result);
            assertEquals("user-123", result.getId());
            assertEquals("Wanderson Alves", result.getName());
            verify(userRepository).findById("user-123");
        }

        @Test
        @DisplayName("Deve retornar null quando usuário não for encontrado")
        void shouldReturnNullWhenNotFound() {
            when(userRepository.findById("inexistente")).thenReturn(Optional.empty());

            User result = service.getUser("inexistente");

            assertNull(result);
        }
    }

    @Nested
    @DisplayName("Edição e Segurança de Papéis (Role Escalation Protection)")
    class SaveUserSecurityTests {

        @Test
        @DisplayName("Deve atualizar data de modificação e preservar papel MEMBER se o solicitante não for ADMIN")
        void shouldBlockRoleEscalationForNonAdminCaller() {
            User incoming = User.builder()
                    .id("user-123")
                    .name("Wanderson Alves Atualizado")
                    .role("ADMIN")
                    .build();

            when(userRepository.findById("user-123")).thenReturn(Optional.of(sampleUser));
            when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

            User saved = service.saveUser(incoming, false);

            assertEquals("MEMBER", saved.getRole(), "Papel não deve ser elevado para ADMIN por usuário comum");
            assertEquals("Wanderson Alves Atualizado", saved.getName());
            assertNotNull(saved.getUpdatedAt());
        }

        @Test
        @DisplayName("Deve permitir alteração de papel quando solicitada por um ADMIN")
        void shouldAllowRoleChangeByAdmin() {
            User incoming = User.builder()
                    .id("user-123")
                    .name("Wanderson Alves")
                    .role("ADMIN")
                    .build();

            when(userRepository.findById("user-123")).thenReturn(Optional.of(sampleUser));
            when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

            User saved = service.saveUser(incoming, true);

            assertEquals("ADMIN", saved.getRole());
        }

        @Test
        @DisplayName("Deve permitir ao usuário alterar seu próprio squadId e cargo (jobTitle)")
        void shouldAllowSelfServiceSquadAndJobTitle() {
            User incoming = User.builder()
                    .id("user-123")
                    .name("Wanderson Alves")
                    .squadId("SQUAD-NOVA")
                    .jobTitle("Tech Lead")
                    .build();

            when(userRepository.findById("user-123")).thenReturn(Optional.of(sampleUser));
            when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

            User saved = service.saveUser(incoming, false);

            assertEquals("SQUAD-NOVA", saved.getSquadId());
            assertEquals("Tech Lead", saved.getJobTitle());
            assertEquals("MEMBER", saved.getRole());
        }

        @Test
        @DisplayName("Deve preservar campos existentes omitidos na requisição parcial")
        void shouldPreserveOmittedFieldsOnPartialUpdate() {
            sampleUser.setJiraAccountId("acc-jira-123");
            sampleUser.setDailyHours(8);

            User incoming = User.builder().id("user-123").name("Wanderson Novo").build();

            when(userRepository.findById("user-123")).thenReturn(Optional.of(sampleUser));
            when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

            User saved = service.saveUser(incoming, false);

            assertEquals("Wanderson Novo", saved.getName());
            assertEquals("acc-jira-123", saved.getJiraAccountId());
            assertEquals(8, saved.getDailyHours());
        }

        @Test
        @DisplayName("Deve rejeitar papel inválido quando fornecido por ADMIN")
        void shouldRejectInvalidRoleFromAdmin() {
            User incoming = User.builder().id("user-123").name("Wanderson").role("SUPER_GOD").build();
            when(userRepository.findById("user-123")).thenReturn(Optional.of(sampleUser));

            assertThrows(ResponseStatusException.class, () -> service.saveUser(incoming, true));
        }
    }

    @Nested
    @DisplayName("Configuração de Integração Jira e TDN")
    class IntegrationConfigTests {

        @Test
        @DisplayName("Deve buscar e salvar configuração do Jira para o usuário")
        void shouldManageJiraConfig() {
            UserJiraConfig config = UserJiraConfig.builder()
                    .userId("user-123")
                    .domain("meujira.atlassian.net")
                    .token("pat-token")
                    .build();

            when(jiraConfigRepository.findById("user-123")).thenReturn(Optional.of(config));
            when(jiraConfigRepository.save(config)).thenReturn(config);

            UserJiraConfig retrieved = service.getJiraConfig("user-123");
            assertNotNull(retrieved);
            assertEquals("meujira.atlassian.net", retrieved.getDomain());

            UserJiraConfig saved = service.saveJiraConfig(config);
            assertEquals("user-123", saved.getUserId());

            service.deleteJiraConfig("user-123");
            verify(jiraConfigRepository).deleteById("user-123");
        }

        @Test
        @DisplayName("Deve buscar e salvar configuração do TDN para o usuário")
        void shouldManageTdnConfig() {
            UserTdnConfig config = UserTdnConfig.builder()
                    .userId("user-123")
                    .baseUrl("tdn.totvs.com")
                    .token("tdn-token")
                    .space("PRO")
                    .build();

            when(tdnConfigRepository.findById("user-123")).thenReturn(Optional.of(config));
            when(tdnConfigRepository.save(config)).thenReturn(config);

            UserTdnConfig retrieved = service.getTdnConfig("user-123");
            assertNotNull(retrieved);
            assertEquals("tdn.totvs.com", retrieved.getBaseUrl());

            UserTdnConfig saved = service.saveTdnConfig(config);
            assertEquals("user-123", saved.getUserId());

            service.deleteTdnConfig("user-123");
            verify(tdnConfigRepository).deleteById("user-123");
        }
    }
}
