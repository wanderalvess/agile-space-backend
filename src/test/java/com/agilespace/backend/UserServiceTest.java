package com.agilespace.backend;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.domain.UserJiraConfig;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.repository.UserJiraConfigRepository;
import com.agilespace.backend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserJiraConfigRepository jiraConfigRepository;

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
        User user = User.builder().id("user-123").name("Francisco").role("Developer").build();
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User saved = service.saveUser(user);

        assertNotNull(saved.getUpdatedAt());
        verify(userRepository, times(1)).save(user);
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
}
