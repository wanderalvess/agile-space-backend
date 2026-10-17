package com.agilespace.backend.service;

import com.agilespace.backend.domain.ShowcaseJqlQuery;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.ShowcaseJqlQueryDto;
import com.agilespace.backend.repository.ShowcaseJqlQueryRepository;
import com.agilespace.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShowcaseJqlServiceTest {

    @Mock private ShowcaseJqlQueryRepository repository;
    @Mock private UserRepository userRepository;
    @Mock private ShowcaseAccessPolicy accessPolicy;
    @InjectMocks private ShowcaseJqlService service;

    private static ShowcaseJqlQuery row(String id, String squad, String visibility, String owner) {
        return ShowcaseJqlQuery.builder().id(id).squadId(squad).name("Consulta " + id).jql("project = X").visibility(visibility)
                .createdBy(owner).createdAt(LocalDateTime.of(2026, 10, 17, 10, 0)).build();
    }

    @BeforeEach
    void defaults() {
        lenient().when(repository.save(any(ShowcaseJqlQuery.class))).thenAnswer(i -> i.getArgument(0));
    }

    // ---------- criar ----------

    @Test
    void createStoresTrimmedUppercaseSquadAndServerOwnedFields() {
        when(userRepository.findAllById(any())).thenReturn(List.of(User.builder().id("ana").name("Ana Souza").build()));

        ShowcaseJqlQueryDto dto = service.create(" ddwmissi ", "  Review da sprint  ", " sprint in openSprints() ", "squad", "ana");

        ArgumentCaptor<ShowcaseJqlQuery> cap = ArgumentCaptor.forClass(ShowcaseJqlQuery.class);
        verify(repository).save(cap.capture());
        ShowcaseJqlQuery saved = cap.getValue();
        assertEquals("DDWMISSI", saved.getSquadId());
        assertEquals("Review da sprint", saved.getName());
        assertEquals("sprint in openSprints()", saved.getJql());
        assertEquals("SQUAD", saved.getVisibility());
        assertEquals("ana", saved.getCreatedBy());
        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        assertTrue(dto.mine());
        assertTrue(dto.canDelete());
        assertEquals("Ana Souza", dto.createdByName());
    }

    @Test
    void visibilityDefaultsToPrivateTheMostRestrictive() {
        service.create("SQ1", "n", "j", null, "ana");
        service.create("SQ1", "m", "j", "  ", "ana");

        ArgumentCaptor<ShowcaseJqlQuery> cap = ArgumentCaptor.forClass(ShowcaseJqlQuery.class);
        verify(repository, times(2)).save(cap.capture());
        cap.getAllValues().forEach(q -> assertEquals("PRIVATE", q.getVisibility()));
    }

    @Test
    void invalidInputIs400() {
        assertEquals(400, status(() -> service.create("SQ1", " ", "j", "SQUAD", "ana")));
        assertEquals(400, status(() -> service.create("SQ1", "n", " ", "SQUAD", "ana")));
        assertEquals(400, status(() -> service.create("SQ1", "n", "j", "PUBLIC", "ana")));
        assertEquals(400, status(() -> service.create(null, "n", "j", "SQUAD", "ana")));
        assertEquals(400, status(() -> service.create("SQ1", "x".repeat(121), "j", "SQUAD", "ana")));
        assertEquals(400, status(() -> service.create("SQ1", "n", "j".repeat(4001), "SQUAD", "ana")));
        verify(repository, never()).save(any());
    }

    @Test
    void duplicateNameOfTheSameOwnerIs409() {
        when(repository.existsBySquadIdAndCreatedByAndNameIgnoreCase("SQ1", "ana", "Review")).thenReturn(true);

        assertEquals(409, status(() -> service.create("sq1", "Review", "j", "SQUAD", "ana")));
        verify(repository, never()).save(any());
    }

    @Test
    void perOwnerLimitIs409() {
        when(repository.countBySquadIdAndCreatedBy("SQ1", "ana")).thenReturn((long) ShowcaseJqlService.MAX_PER_OWNER_PER_SQUAD);

        assertEquals(409, status(() -> service.create("SQ1", "n", "j", "SQUAD", "ana")));
    }

    // ---------- listar ----------

    @Test
    void listAsksOnlyForSquadQueriesPlusTheCallersOwnPrivate() {
        when(repository.findVisible(eq("SQ1"), eq("ana"), any(Pageable.class))).thenReturn(List.of(
                row("a", "SQ1", "SQUAD", "bia"), row("b", "SQ1", "PRIVATE", "ana")));
        when(userRepository.findAllById(any())).thenReturn(List.of(User.builder().id("bia").name("Bia Lima").build()));
        when(accessPolicy.isAdminOrSquadLeader("SQ1", "ana")).thenReturn(false);

        List<ShowcaseJqlQueryDto> out = service.list(" sq1 ", "ana");

        assertEquals(2, out.size());
        assertFalse(out.get(0).mine());
        assertFalse(out.get(0).canDelete(), "consulta SQUAD de outra pessoa, sem ser liderança");
        assertEquals("Bia Lima", out.get(0).createdByName());
        assertTrue(out.get(1).mine());
        assertTrue(out.get(1).canDelete());
    }

    @Test
    void leaderCanDeleteOthersSquadQueriesAndTheLookupHappensOncePerSquad() {
        when(repository.findVisible(eq("SQ1"), eq("lider"), any(Pageable.class))).thenReturn(List.of(
                row("a", "SQ1", "SQUAD", "bia"), row("b", "SQ1", "SQUAD", "caio")));
        when(accessPolicy.isAdminOrSquadLeader("SQ1", "lider")).thenReturn(true);

        List<ShowcaseJqlQueryDto> out = service.list("SQ1", "lider");

        assertTrue(out.get(0).canDelete());
        assertTrue(out.get(1).canDelete());
        verify(accessPolicy, times(1)).isAdminOrSquadLeader("SQ1", "lider");
    }

    @Test
    void listWithoutSquadIs400() {
        assertEquals(400, status(() -> service.list(" ", "ana")));
    }

    // ---------- apagar ----------

    @Test
    void ownerDeletesOwnQuery() {
        ShowcaseJqlQuery q = row("a", "SQ1", "PRIVATE", "ana");
        when(repository.findById("a")).thenReturn(Optional.of(q));

        service.delete("a", "ana");

        verify(repository).delete(q);
        verifyNoInteractions(accessPolicy);
    }

    @Test
    void leaderOrAdminDeletesASquadQueryOfSomeoneElse() {
        ShowcaseJqlQuery q = row("a", "SQ1", "SQUAD", "bia");
        when(repository.findById("a")).thenReturn(Optional.of(q));
        when(accessPolicy.isAdminOrSquadLeader("SQ1", "lider")).thenReturn(true);

        service.delete("a", "lider");

        verify(repository).delete(q);
    }

    @Test
    void plainMemberCannotDeleteSomeoneElsesSquadQuery() {
        when(repository.findById("a")).thenReturn(Optional.of(row("a", "SQ1", "SQUAD", "bia")));
        when(accessPolicy.isAdminOrSquadLeader("SQ1", "dev")).thenReturn(false);

        assertEquals(403, status(() -> service.delete("a", "dev")));
        verify(repository, never()).delete(any(ShowcaseJqlQuery.class));
    }

    @Test
    void privateQueryOfSomeoneElseLooksNonexistentEvenToTheSquadLeader() {
        when(repository.findById("a")).thenReturn(Optional.of(row("a", "SQ1", "PRIVATE", "bia")));
        when(userRepository.findById("lider")).thenReturn(Optional.of(User.builder().id("lider").role("MEMBER").build()));

        assertEquals(404, status(() -> service.delete("a", "lider")));
        verify(repository, never()).delete(any(ShowcaseJqlQuery.class));
    }

    @Test
    void adminCanDeleteEvenAPrivateQuery() {
        ShowcaseJqlQuery q = row("a", "SQ1", "PRIVATE", "bia");
        when(repository.findById("a")).thenReturn(Optional.of(q));
        when(userRepository.findById("adm")).thenReturn(Optional.of(User.builder().id("adm").role("ADMIN").build()));
        when(accessPolicy.isAdminOrSquadLeader("SQ1", "adm")).thenReturn(true);

        service.delete("a", "adm");

        verify(repository).delete(q);
    }

    @Test
    void deletingAnUnknownQueryIs404() {
        when(repository.findById(anyString())).thenReturn(Optional.empty());

        assertEquals(404, status(() -> service.delete("x", "ana")));
    }

    private static int status(Runnable r) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, r::run);
        return ex.getStatusCode().value();
    }
}
