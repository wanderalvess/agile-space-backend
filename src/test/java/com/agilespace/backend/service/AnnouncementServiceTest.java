package com.agilespace.backend.service;

import com.agilespace.backend.domain.AnnouncementDismissal;
import com.agilespace.backend.domain.GlobalAnnouncement;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.AnnouncementDto;
import com.agilespace.backend.dto.AnnouncementRequest;
import com.agilespace.backend.repository.AnnouncementDismissalRepository;
import com.agilespace.backend.repository.GlobalAnnouncementRepository;
import com.agilespace.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementService - público, vigência, dispensa e validação")
class AnnouncementServiceTest {

    @Mock private GlobalAnnouncementRepository announcementRepository;
    @Mock private AnnouncementDismissalRepository dismissalRepository;
    @Mock private UserRepository userRepository;
    @Mock private SquadAudienceService audienceService;
    @Mock private NotificationTriggers notificationTriggers;
    @InjectMocks private AnnouncementService service;

    private static final LocalDateTime NOW = LocalDateTime.now();

    @BeforeEach
    void member() {
        // ana: squad SQA, papel de projeto AGILE_MASTER
        lenient().when(audienceService.contextFor(eq("ana"), any())).thenReturn(Optional.of(new SquadAudienceService.UserContext(
                User.builder().id("ana").build(), "SQA", List.of("SQA"), Set.of("MEMBER", "AGILE_MASTER"), true)));
    }

    private static GlobalAnnouncement ann(String id, String type, String audience, Set<String> squads, Set<String> roles, int minutesAgo) {
        return GlobalAnnouncement.builder().id(id).title("T " + id).content("C").type(type).audienceType(audience)
                .audienceSquadIds(new LinkedHashSet<>(squads)).audienceRoles(new LinkedHashSet<>(roles))
                .createdBy("adm@x.com").createdAt(NOW.minusMinutes(minutesAgo)).validFrom(NOW.minusMinutes(minutesAgo)).build();
    }

    @Test
    @DisplayName("Ativos: só do público do usuário, sem dispensados, MAINTENANCE > IMPORTANT > INFO, depois mais novo")
    void activeFiltersAudienceAndOrders() {
        when(announcementRepository.findActive(any())).thenReturn(List.of(
                ann("info-new", "INFO", "ALL", Set.of(), Set.of(), 1),
                ann("other-squad", "IMPORTANT", "SQUADS", Set.of("SQB"), Set.of(), 2),
                ann("my-squad", "IMPORTANT", "SQUADS", Set.of("sqa"), Set.of(), 3),
                ann("maint", "MAINTENANCE", "ALL", Set.of(), Set.of(), 4),
                ann("am-role", "INFO", "ROLES", Set.of(), Set.of("AGILE_MASTER"), 5),
                ann("admins", "INFO", "ROLES", Set.of(), Set.of("ADMIN"), 6),
                ann("dismissed", "MAINTENANCE", "ALL", Set.of(), Set.of(), 7)));
        when(dismissalRepository.findDismissedIds("ana")).thenReturn(Set.of("dismissed"));

        List<AnnouncementDto> out = service.active("ana", "MEMBER");

        assertEquals(List.of("maint", "my-squad", "info-new", "am-role"), out.stream().map(AnnouncementDto::id).toList());
        assertTrue(out.stream().noneMatch(AnnouncementDto::dismissed));
    }

    @Test
    @DisplayName("Histórico: inclui dispensados (dismissed=true), respeita o público e o limite")
    void historyMarksDismissed() {
        when(announcementRepository.findStarted(any())).thenReturn(List.of(
                ann("a1", "INFO", "ALL", Set.of(), Set.of(), 1),
                ann("a2", "INFO", "SQUADS", Set.of("SQB"), Set.of(), 2),
                ann("a3", "INFO", "ALL", Set.of(), Set.of(), 3)));
        when(dismissalRepository.findDismissedIds("ana")).thenReturn(Set.of("a3"));
        when(userRepository.findActiveByEmails(Set.of("adm@x.com"))).thenReturn(List.of(
                User.builder().id("adm").email("adm@x.com").name("Admin").build()));

        List<AnnouncementDto> out = service.history("ana", "MEMBER", 5);

        assertEquals(List.of("a1", "a3"), out.stream().map(AnnouncementDto::id).toList());
        assertTrue(out.get(1).dismissed());
        assertEquals("Admin", out.get(0).createdByName());
        assertEquals(1, service.history("ana", "MEMBER", 1).size());
    }

    @Test
    @DisplayName("Dispensar: idempotente; aviso de outro público é 404")
    void dismiss() {
        GlobalAnnouncement mine = ann("a1", "INFO", "ALL", Set.of(), Set.of(), 1);
        when(announcementRepository.findById("a1")).thenReturn(Optional.of(mine));
        when(dismissalRepository.existsById(any())).thenReturn(false, true);

        service.dismiss("ana", "MEMBER", "a1");
        service.dismiss("ana", "MEMBER", "a1");
        verify(dismissalRepository, times(1)).save(any(AnnouncementDismissal.class));

        when(announcementRepository.findById("a2")).thenReturn(Optional.of(ann("a2", "INFO", "SQUADS", Set.of("SQB"), Set.of(), 1)));
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.dismiss("ana", "MEMBER", "a2")).getStatusCode());
    }

    @Test
    @DisplayName("Público (sem login): só ALL")
    void publicOnlyAll() {
        when(announcementRepository.findActive(any())).thenReturn(List.of(
                ann("all", "INFO", "ALL", Set.of(), Set.of(), 1),
                ann("squad", "INFO", "SQUADS", Set.of("SQA"), Set.of(), 1)));
        assertEquals(List.of("all"), service.publicActive().stream().map(AnnouncementDto::id).toList());
    }

    @Test
    @DisplayName("Criar: validFrom padrão = criação, normaliza papéis e dispara notificação do público")
    void createDefaultsAndNotifies() {
        when(announcementRepository.save(any(GlobalAnnouncement.class))).thenAnswer(i -> i.getArgument(0));

        AnnouncementDto dto = service.create(new AnnouncementRequest(" Janela ", "Sistema fora às 22h", "maintenance", "ROLES",
                List.of(), List.of("agile_master", " "), null, null, "/painel"), "adm@x.com", "adm");

        assertEquals("Janela", dto.title());
        assertEquals("MAINTENANCE", dto.type());
        assertEquals(List.of("AGILE_MASTER"), dto.audienceRoles());
        assertEquals(dto.createdAt(), dto.validFrom());
        assertFalse(dto.dismissed());
        verify(notificationTriggers).announcementPublished(eq(dto.id()), eq("Janela"), eq("ROLES"), eq(List.of()),
                eq(List.of("AGILE_MASTER")), any(), isNull(), eq("/painel"), eq("adm"));
    }

    @Test
    @DisplayName("Validação: título, texto, tipo, público vazio, link e vigência invertida → 400")
    void validation() {
        Instant from = Instant.now();
        List<AnnouncementRequest> invalid = List.of(
                new AnnouncementRequest(" ", "c", "INFO", "ALL", null, null, null, null, null),
                new AnnouncementRequest("x".repeat(121), "c", "INFO", "ALL", null, null, null, null, null),
                new AnnouncementRequest("t", "x".repeat(2001), "INFO", "ALL", null, null, null, null, null),
                new AnnouncementRequest("t", "c", "URGENTE", "ALL", null, null, null, null, null),
                new AnnouncementRequest("t", "c", "INFO", "SQUADS", List.of(), null, null, null, null),
                new AnnouncementRequest("t", "c", "INFO", "ROLES", null, List.of(), null, null, null),
                new AnnouncementRequest("t", "c", "INFO", "ALL", null, null, null, null, "javascript:alert(1)"),
                new AnnouncementRequest("t", "c", "INFO", "ALL", null, null, null, null, "//evil.com"),
                new AnnouncementRequest("t", "c", "INFO", "ALL", null, null, from, from.minus(1, ChronoUnit.HOURS), null));
        for (AnnouncementRequest r : invalid) {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.create(r, "adm@x.com", "adm"));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }
        verify(announcementRepository, never()).save(any());
        assertTrue(AnnouncementService.isAllowedLink("https://totvs.com/x"));
        assertTrue(AnnouncementService.isAllowedLink("/room/abc"));
    }

    @Test
    @DisplayName("Editar inexistente: 404; excluir inexistente: 404")
    void updateDeleteMissing() {
        when(announcementRepository.findById("x")).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.update("x", new AnnouncementRequest("t", "c", null, null, null, null, null, null, null), "adm")).getStatusCode());
        when(announcementRepository.existsById("x")).thenReturn(false);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, () -> service.delete("x")).getStatusCode());
    }

    @Test
    @DisplayName("Editar mantém autoria/criação e troca o público (squads ficam vazias fora de SQUADS)")
    void updateKeepsAuthor() {
        GlobalAnnouncement a = ann("a1", "INFO", "SQUADS", Set.of("SQA"), Set.of(), 10);
        when(announcementRepository.findById("a1")).thenReturn(Optional.of(a));
        when(announcementRepository.save(any(GlobalAnnouncement.class))).thenAnswer(i -> i.getArgument(0));

        AnnouncementDto dto = service.update("a1", new AnnouncementRequest("Novo", "c", "IMPORTANT", "ALL", List.of("SQA"), null, null, null, null), "adm");

        assertEquals("adm@x.com", dto.createdBy());
        assertEquals("ALL", dto.audienceType());
        assertTrue(dto.audienceSquadIds().isEmpty());
    }
}
