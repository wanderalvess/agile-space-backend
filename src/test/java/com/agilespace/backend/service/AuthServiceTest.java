package com.agilespace.backend.service;

import com.agilespace.backend.domain.ProjectConfig;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.AuthResponseDto;
import com.agilespace.backend.dto.LoginRequestDto;
import com.agilespace.backend.dto.RegisterRequestDto;
import com.agilespace.backend.dto.UserProjectAccessDto;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.SquadMemberRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtTokenUtil;
import com.agilespace.backend.security.PasswordUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProjectConfigRepository projectConfigRepository;

    @Mock
    private ProjectMemberRoleRepository projectMemberRoleRepository;

    @Mock
    private SquadMemberRepository squadMemberRepository;

    @Mock
    private UserProjectResolverService userProjectResolverService;

    @Mock
    private JwtTokenUtil jwtTokenUtil;

    @InjectMocks
    private AuthService service;

    private static final String RAW_PASSWORD = "SenhaForte123";
    private static String passwordHash;

    @BeforeAll
    public static void hashOnce() {
        // PBKDF2 com 310k iteracoes e caro: gera o hash uma unica vez para toda a classe.
        passwordHash = PasswordUtil.hashPassword(RAW_PASSWORD);
    }

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        lenient().when(jwtTokenUtil.generateToken(anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn("jwt-token");
    }

    private User activeUser() {
        return User.builder()
                .id("u1")
                .email("joao.silva@empresa.com.br")
                .name("Joao Silva")
                .passwordHash(passwordHash)
                .role("MEMBER")
                .active(true)
                .build();
    }

    private UserProjectAccessDto accessWith(String... projectIds) {
        List<UserProjectAccessDto.ProjectAccessItem> items = Arrays.stream(projectIds)
                .map(id -> UserProjectAccessDto.ProjectAccessItem.builder()
                        .projectId(id)
                        .projectName("Projeto " + id)
                        .segmentName("Segmento")
                        .tribeName("Tribo")
                        .roleName("Product Owner")
                        .roleKey("PO")
                        .isLeadership(true)
                        .build())
                .toList();
        return UserProjectAccessDto.builder()
                .userId("u1")
                .primaryProjectId(items.isEmpty() ? null : items.get(0).getProjectId())
                .projects(new ArrayList<>(items))
                .build();
    }

    // ---------- login ----------

    @Test
    public void testLoginSuccessNormalizesEmailAndReturnsToken() {
        User user = activeUser();
        user.setDefaultProjectId("DDWMISSI");
        when(userRepository.findByEmail("joao.silva@empresa.com.br")).thenReturn(Optional.of(user));
        when(userProjectResolverService.resolveUserAccess(user)).thenReturn(accessWith("DDWMISSI"));

        AuthResponseDto response = service.login(
                LoginRequestDto.builder().email("  Joao.Silva@Empresa.com.BR ").password(RAW_PASSWORD).build());

        assertEquals("jwt-token", response.getToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals("DDWMISSI", response.getActiveProjectId());
        assertEquals("Product Owner", response.getActiveProjectRole());
        verify(userRepository).findByEmail("joao.silva@empresa.com.br");
    }

    @Test
    public void testLoginUnknownEmailReturnsUnauthorized() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.login(
                LoginRequestDto.builder().email("nao.existe@empresa.com.br").password(RAW_PASSWORD).build()));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    public void testLoginWrongPasswordReturnsUnauthorized() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(activeUser()));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.login(
                LoginRequestDto.builder().email("joao.silva@empresa.com.br").password("senhaErrada").build()));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
        verify(jwtTokenUtil, never()).generateToken(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    public void testLoginWithoutPasswordHashReturnsUnauthorized() {
        User user = activeUser();
        user.setPasswordHash(null);
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(user));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.login(
                LoginRequestDto.builder().email("joao.silva@empresa.com.br").password(RAW_PASSWORD).build()));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    public void testLoginInactiveUserReturnsForbidden() {
        User user = activeUser();
        user.setActive(false);
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(user));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.login(
                LoginRequestDto.builder().email("joao.silva@empresa.com.br").password(RAW_PASSWORD).build()));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    public void testLoginAssignsFirstProjectWhenNoDefaultProject() {
        User user = activeUser();
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(user));
        when(userProjectResolverService.resolveUserAccess(user)).thenReturn(accessWith("DDWMISSI", "OUTRO"));

        AuthResponseDto response = service.login(
                LoginRequestDto.builder().email("joao.silva@empresa.com.br").password(RAW_PASSWORD).build());

        assertEquals("DDWMISSI", user.getDefaultProjectId());
        assertEquals("DDWMISSI", response.getActiveProjectId());
        verify(userRepository).save(user);
    }

    @Test
    public void testLoginWithoutProjectsDoesNotPersistDefaultProject() {
        User user = activeUser();
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(user));
        when(userProjectResolverService.resolveUserAccess(user)).thenReturn(accessWith());

        AuthResponseDto response = service.login(
                LoginRequestDto.builder().email("joao.silva@empresa.com.br").password(RAW_PASSWORD).build());

        assertNull(response.getActiveProjectId());
        verify(userRepository, never()).save(any());
    }

    // ---------- register ----------

    @Test
    public void testRegisterDuplicateEmailReturnsConflict() {
        when(userRepository.findByEmail("joao.silva@empresa.com.br")).thenReturn(Optional.of(activeUser()));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.register(
                RegisterRequestDto.builder().email("Joao.Silva@Empresa.com.br").name("Joao").password(RAW_PASSWORD).build()));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(userRepository, never()).save(any());
    }

    @Test
    public void testRegisterCreatesLocalMemberWithHashedPassword() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(projectMemberRoleRepository.findByEmailIgnoreCase(anyString())).thenReturn(List.of());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userProjectResolverService.resolveUserAccess(any(User.class))).thenReturn(accessWith());

        service.register(RegisterRequestDto.builder()
                .email("  Novo.User@Empresa.com.BR ")
                .name("  Novo User  ")
                .password(RAW_PASSWORD)
                .build());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();

        assertEquals("novo.user@empresa.com.br", saved.getEmail());
        assertEquals("Novo User", saved.getName());
        assertEquals("MEMBER", saved.getRole());
        assertEquals("LOCAL", saved.getAuthProvider());
        assertTrue(saved.isActive());
        assertFalse(saved.isGuest());
        assertNotEquals(RAW_PASSWORD, saved.getPasswordHash());
        assertTrue(saved.getPasswordHash().startsWith("pbkdf2:sha256:"));
        assertTrue(PasswordUtil.verifyPassword(RAW_PASSWORD, saved.getPasswordHash()));
    }

    @Test
    public void testRegisterNeverAcceptsSelfDeclaredAdminRole() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(projectMemberRoleRepository.findByEmailIgnoreCase(anyString())).thenReturn(List.of());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userProjectResolverService.resolveUserAccess(any(User.class))).thenReturn(accessWith());

        AuthResponseDto response = service.register(RegisterRequestDto.builder()
                .email("novo@empresa.com.br").name("Novo").password(RAW_PASSWORD).build());

        assertEquals("MEMBER", response.getRole());
    }

    @Test
    public void testRegisterAutoLinksProfieldsRolesAndInheritsProjectContext() {
        ProjectMemberRole role = new ProjectMemberRole();
        role.setId("r1");
        role.setProjectId("DDWMISSI");
        ProjectConfig project = new ProjectConfig();
        project.setId("DDWMISSI");
        project.setSegmentName("Segmento X");
        project.setTribeName("Tribo Y");

        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(projectMemberRoleRepository.findByEmailIgnoreCase("novo@empresa.com.br")).thenReturn(List.of(role));
        when(projectConfigRepository.findById("DDWMISSI")).thenReturn(Optional.of(project));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userProjectResolverService.resolveUserAccess(any(User.class))).thenReturn(accessWith("DDWMISSI"));

        service.register(RegisterRequestDto.builder()
                .email("novo@empresa.com.br").name("Novo").password(RAW_PASSWORD).build());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();

        assertEquals("DDWMISSI", saved.getDefaultProjectId());
        assertEquals("Segmento X", saved.getSegmentName());
        assertEquals("Tribo Y", saved.getTribeName());
        // FK do papel do Profields passa a apontar para o novo usuario
        assertEquals(saved.getId(), role.getUserId());
        verify(projectMemberRoleRepository).save(role);
    }

    @Test
    public void testRegisterKeepsExplicitProjectContextOverProfields() {
        ProjectMemberRole role = new ProjectMemberRole();
        role.setId("r1");
        role.setProjectId("DDWMISSI");

        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(projectMemberRoleRepository.findByEmailIgnoreCase(anyString())).thenReturn(List.of(role));
        when(projectConfigRepository.findById(anyString())).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userProjectResolverService.resolveUserAccess(any(User.class))).thenReturn(accessWith());

        service.register(RegisterRequestDto.builder()
                .email("novo@empresa.com.br").name("Novo").password(RAW_PASSWORD)
                .defaultProjectId("ESCOLHIDO").segmentName("Seg").tribeName("Tri").build());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals("ESCOLHIDO", captor.getValue().getDefaultProjectId());
        assertEquals("Seg", captor.getValue().getSegmentName());
    }

    // ---------- getMe ----------

    @Test
    public void testGetMeReturnsSessionData() {
        User user = activeUser();
        user.setDefaultProjectId("DDWMISSI");
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));
        when(userProjectResolverService.resolveUserAccess(user)).thenReturn(accessWith("DDWMISSI"));

        AuthResponseDto response = service.getMe("u1");

        assertEquals("u1", response.getId());
        assertEquals("DDWMISSI", response.getActiveProjectId());
    }

    @Test
    public void testGetMeUnknownUserReturnsUnauthorized() {
        when(userRepository.findById("ghost")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.getMe("ghost"));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    // ---------- switchActiveProject ----------

    @Test
    public void testSwitchActiveProjectPersistsWhenUserHasAccess() {
        User user = activeUser();
        user.setDefaultProjectId("DDWMISSI");
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);
        when(userProjectResolverService.resolveUserAccess(user)).thenReturn(accessWith("DDWMISSI", "OUTRO"));

        AuthResponseDto response = service.switchActiveProject("u1", "OUTRO");

        assertEquals("OUTRO", user.getDefaultProjectId());
        assertEquals("OUTRO", response.getActiveProjectId());
        verify(userRepository).save(user);
    }

    @Test
    public void testSwitchActiveProjectRejectsProjectOutsideUserAccess() {
        User user = activeUser();
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));
        when(userProjectResolverService.resolveUserAccess(user)).thenReturn(accessWith("DDWMISSI"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.switchActiveProject("u1", "PROJETO-ALHEIO"));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(userRepository, never()).save(any());
    }

    @Test
    public void testSwitchActiveProjectUnknownUserReturnsNotFound() {
        when(userRepository.findById("ghost")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.switchActiveProject("ghost", "DDWMISSI"));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }
}
