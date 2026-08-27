package com.agilespace.backend;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.domain.UserJiraConfig;
import com.agilespace.backend.domain.UserTdnConfig;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.repository.UserJiraConfigRepository;
import com.agilespace.backend.repository.UserTdnConfigRepository;
import com.agilespace.backend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserJiraConfigRepository jiraConfigRepository;

    @Mock
    private UserTdnConfigRepository tdnConfigRepository;

    @InjectMocks
    private UserService service;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testGetUserById() {
        User user = User.builder().id("user-123").name("Francisco").role("Developer").build();
        when(userRepository.findById("user-123")).thenReturn(Optional.of(user));

        User result = service.getUser("user-123");

        assertNotNull(result);
        assertEquals("Francisco", result.getName());
        verify(userRepository, times(1)).findById("user-123");
    }

    @Test
    public void testGetUserByIdNotFound() {
        when(userRepository.findById("u1")).thenReturn(Optional.empty());
        User result = service.getUser("u1");
        assertNull(result);
    }

    @Test
    public void testSaveUserSetsUpdatedAt() {
        User existing = User.builder().id("user-123").name("Old Name").role("MEMBER").build();
        User incoming = User.builder().id("user-123").name("Francisco").role("ADMIN").build();
        when(userRepository.findById("user-123")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User saved = service.saveUser(incoming, false);

        assertNotNull(saved.getUpdatedAt());
        assertEquals("Francisco", saved.getName());
        assertEquals("MEMBER", saved.getRole());
        verify(userRepository, times(1)).save(existing);
    }

    @Test
    public void testSaveUserIgnoresRoleEscalationForNonAdmin() {
        User existing = User.builder().id("user-123").name("Francisco").role("MEMBER").active(true).build();
        User incoming = User.builder().id("user-123").name("Francisco").role("ADMIN").active(true).build();
        when(userRepository.findById("user-123")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User saved = service.saveUser(incoming, false);

        assertEquals("MEMBER", saved.getRole());
    }

    @Test
    public void testSaveUserAppliesRoleChangeForAdmin() {
        User existing = User.builder().id("user-123").name("Francisco").role("MEMBER").active(true).build();
        User incoming = User.builder().id("user-123").name("Francisco").role("ADMIN").active(true).build();
        when(userRepository.findById("user-123")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User saved = service.saveUser(incoming, true);

        assertEquals("ADMIN", saved.getRole());
    }

    @Test
    public void testSaveUserAllowsSquadIdSelfServiceForNonAdmin() {
        User existing = User.builder().id("user-123").name("Francisco").role("MEMBER").squadId(null).build();
        User incoming = User.builder().id("user-123").name("Francisco").squadId("DDWMISSI").build();
        when(userRepository.findById("user-123")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User saved = service.saveUser(incoming, false);

        assertEquals("DDWMISSI", saved.getSquadId());
    }

    @Test
    public void testSaveUserPreservesOmittedFieldsNotSentByClient() {
        User existing = User.builder().id("user-123").name("Francisco")
                .jiraAccountId("acc-1").dailyHours(8).build();
        // Simula o payload real do modal de perfil, que não envia jiraAccountId/dailyHours
        User incoming = User.builder().id("user-123").name("Francisco Alterado").build();
        when(userRepository.findById("user-123")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User saved = service.saveUser(incoming, false);

        assertEquals("Francisco Alterado", saved.getName());
        assertEquals("acc-1", saved.getJiraAccountId());
        assertEquals(8, saved.getDailyHours());
    }

    @Test
    public void testSaveUserAllowsJobTitleSelfServiceForNonAdmin() {
        User existing = User.builder().id("user-123").name("Francisco").role("MEMBER").jobTitle(null).build();
        User incoming = User.builder().id("user-123").name("Francisco").jobTitle("Tech Lead").build();
        when(userRepository.findById("user-123")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User saved = service.saveUser(incoming, false);

        assertEquals("Tech Lead", saved.getJobTitle());
        assertEquals("MEMBER", saved.getRole());
    }

    @Test
    public void testSaveUserRejectsInvalidRoleForAdmin() {
        User existing = User.builder().id("user-123").name("Francisco").role("MEMBER").build();
        User incoming = User.builder().id("user-123").name("Francisco").role("Tech Lead").build();
        when(userRepository.findById("user-123")).thenReturn(Optional.of(existing));

        assertThrows(ResponseStatusException.class, () -> service.saveUser(incoming, true));
    }

    @Test
    public void testSaveUserNormalizesRoleCaseForAdmin() {
        User existing = User.builder().id("user-123").name("Francisco").role("MEMBER").build();
        User incoming = User.builder().id("user-123").name("Francisco").role("admin").build();
        when(userRepository.findById("user-123")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User saved = service.saveUser(incoming, true);

        assertEquals("ADMIN", saved.getRole());
    }

    @Test
    public void testSaveUserReturnsNullWhenNotFound() {
        User incoming = User.builder().id("missing").name("Ghost").build();
        when(userRepository.findById("missing")).thenReturn(Optional.empty());

        User saved = service.saveUser(incoming, false);

        assertNull(saved);
    }

    @Test
    public void testGetJiraConfig() {
        UserJiraConfig config = UserJiraConfig.builder().userId("user-123").token("pat-token").domain("jira.com").build();
        when(jiraConfigRepository.findById("user-123")).thenReturn(Optional.of(config));

        UserJiraConfig result = service.getJiraConfig("user-123");

        assertNotNull(result);
        assertEquals("pat-token", result.getToken());
        assertEquals("jira.com", result.getDomain());
        verify(jiraConfigRepository, times(1)).findById("user-123");
    }

    @Test
    public void testGetJiraConfigNotFound() {
        when(jiraConfigRepository.findById("u1")).thenReturn(Optional.empty());
        UserJiraConfig result = service.getJiraConfig("u1");
        assertNull(result);
    }

    @Test
    public void testSaveJiraConfig() {
        UserJiraConfig config = UserJiraConfig.builder().userId("u1").build();
        when(jiraConfigRepository.save(config)).thenReturn(config);

        UserJiraConfig saved = service.saveJiraConfig(config);
        assertEquals("u1", saved.getUserId());
    }

    @Test
    public void testDeleteJiraConfig() {
        doNothing().when(jiraConfigRepository).deleteById("user-123");

        service.deleteJiraConfig("user-123");

        verify(jiraConfigRepository, times(1)).deleteById("user-123");
    }

    @Test
    public void testGetTdnConfig() {
        UserTdnConfig config = UserTdnConfig.builder().userId("user-123").baseUrl("tdn.totvs.com").token("tdn-token").space("PRO").label("test").build();
        when(tdnConfigRepository.findById("user-123")).thenReturn(Optional.of(config));

        UserTdnConfig result = service.getTdnConfig("user-123");

        assertNotNull(result);
        assertEquals("tdn-token", result.getToken());
        assertEquals("tdn.totvs.com", result.getBaseUrl());
        assertEquals("PRO", result.getSpace());
        verify(tdnConfigRepository, times(1)).findById("user-123");
    }

    @Test
    public void testSaveTdnConfig() {
        UserTdnConfig config = UserTdnConfig.builder().userId("u1").baseUrl("tdn.totvs.com").token("token").build();
        when(tdnConfigRepository.save(config)).thenReturn(config);

        UserTdnConfig saved = service.saveTdnConfig(config);
        assertEquals("u1", saved.getUserId());
        assertEquals("tdn.totvs.com", saved.getBaseUrl());
    }

    @Test
    public void testDeleteTdnConfig() {
        doNothing().when(tdnConfigRepository).deleteById("user-123");

        service.deleteTdnConfig("user-123");

        verify(tdnConfigRepository, times(1)).deleteById("user-123");
    }
}

