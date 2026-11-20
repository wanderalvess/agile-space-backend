package com.agilespace.backend.service;

import com.agilespace.backend.domain.Notification;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.NotificationDto;
import com.agilespace.backend.dto.UnreadCountDto;
import com.agilespace.backend.repository.NotificationRepository;
import com.agilespace.backend.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService - só do próprio usuário, categorias e criação pelos gatilhos")
class NotificationServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private UserRepository userRepository;
    @Mock private ConversationService conversationService;
    @Mock private UserSignalService signalService;
    @InjectMocks private NotificationService service;

    @Test
    @DisplayName("Marcar como lida notificação de outra pessoa: 404 (a busca é por id E dono)")
    void cannotReadOthersNotification() {
        when(notificationRepository.findByIdAndUserId("n1", "ana")).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.markRead("ana", "n1"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Marcar a própria como lida grava readAt e manda UNREAD")
    void markOwnAsRead() {
        Notification n = Notification.builder().id("n1").userId("ana").kind("INVITE").text("t").createdAt(LocalDateTime.now()).build();
        when(notificationRepository.findByIdAndUserId("n1", "ana")).thenReturn(Optional.of(n));
        service.markRead("ana", "n1");
        assertNotNull(n.getReadAt());
        verify(notificationRepository).save(n);
        verify(signalService).signal("ana", UserSignalService.UNREAD);
    }

    @Test
    @DisplayName("Lista FOR_YOU não inclui ANNOUNCEMENT nem MESSAGE e resolve o nome do autor")
    void listForYouKinds() {
        Notification n = Notification.builder().id("n1").userId("ana").kind("INVITE").text("convidou você")
                .actorId("bob").createdAt(LocalDateTime.now()).build();
        when(notificationRepository.findForUser(eq("ana"), eq(NotificationService.FOR_YOU_KINDS), any())).thenReturn(List.of(n));
        when(userRepository.findAllById(Set.of("bob"))).thenReturn(List.of(User.builder().id("bob").name("Bob").build()));

        List<NotificationDto> out = service.list("ana", null, null);

        assertEquals(1, out.size());
        assertEquals("Bob", out.get(0).actorName());
        assertFalse(NotificationService.FOR_YOU_KINDS.contains("ANNOUNCEMENT"));
        assertFalse(NotificationService.FOR_YOU_KINDS.contains("MESSAGE"));
    }

    @Test
    @DisplayName("Categoria ANNOUNCEMENTS, inválida (400) e read-all sem categoria = todas")
    void categories() {
        when(notificationRepository.findForUser(eq("ana"), eq(List.of("ANNOUNCEMENT")), any())).thenReturn(List.of());
        assertTrue(service.list("ana", "announcements", 10).isEmpty());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.list("ana", "OUTRA", 10)).getStatusCode());

        service.markAllRead("ana", null);
        verify(notificationRepository).markAllRead(eq("ana"), eq(NotificationService.ALL_KINDS), any());
    }

    @Test
    @DisplayName("Sem sessão: 401")
    void requiresUser() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                assertThrows(ResponseStatusException.class, () -> service.unreadCount(null)).getStatusCode());
    }

    @Test
    @DisplayName("Contagem: para você + avisos + mensagens diretas")
    void unreadCount() {
        when(notificationRepository.countUnread("ana", NotificationService.FOR_YOU_KINDS)).thenReturn(2L);
        when(notificationRepository.countUnread("ana", NotificationService.ANNOUNCEMENT_KINDS)).thenReturn(1L);
        when(conversationService.unreadTotal("ana")).thenReturn(4L);

        UnreadCountDto dto = service.unreadCount("ana");

        assertEquals(new UnreadCountDto(2, 4, 1, 7), dto);
    }

    @Test
    @DisplayName("Criação: nunca o autor, sem repetir a mesma chave, sinal para quem recebeu")
    @SuppressWarnings("unchecked")
    void notifyUsersExcludesActorAndDuplicates() {
        when(notificationRepository.findUserIdsWithDedupeKey(eq("POKER_ASYNC:r1"), any())).thenReturn(List.of("bob"));

        Set<String> notified = service.notifyUsers(List.of("ana", "bob", "carla"),
                new NotificationService.Draft("REMINDER", "Sala", "Falta seu voto.", "/room/r1", "ana", "SQ", "POKER_ASYNC:r1"),
                UserSignalService.NOTIFICATION);

        assertEquals(Set.of("carla"), notified);
        ArgumentCaptor<List<Notification>> saved = ArgumentCaptor.forClass(List.class);
        verify(notificationRepository).saveAll(saved.capture());
        assertEquals(1, saved.getValue().size());
        assertEquals("carla", saved.getValue().get(0).getUserId());
        assertEquals("/room/r1", saved.getValue().get(0).getLink());
        verify(signalService).signal(Set.of("carla"), UserSignalService.NOTIFICATION);
    }

    @Test
    @DisplayName("Só o autor como destinatário: nada é gravado")
    void notifyOnlyActor() {
        assertTrue(service.notifyUsers(List.of("ana"),
                new NotificationService.Draft("INVITE", null, "x", null, "ana", null, null), null).isEmpty());
        verify(notificationRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("retireByDedupeKey: marca como lido o aviso pendente da chave e sinaliza quem o tinha; sem pendência não faz nada")
    void retireByDedupeKey() {
        when(notificationRepository.findUnreadUserIdsByDedupeKey("LINK_REQUEST_CREATED:lr1")).thenReturn(List.of("am", "pl"));
        service.retireByDedupeKey("LINK_REQUEST_CREATED:lr1");
        verify(notificationRepository).markReadByDedupeKey(eq("LINK_REQUEST_CREATED:lr1"), any());
        verify(signalService).signal(List.of("am", "pl"), UserSignalService.UNREAD);

        when(notificationRepository.findUnreadUserIdsByDedupeKey("vazia")).thenReturn(List.of());
        service.retireByDedupeKey("vazia");
        service.retireByDedupeKey(" ");
        service.retireByDedupeKey(null);
        verify(notificationRepository, times(1)).markReadByDedupeKey(any(), any());
    }
}
