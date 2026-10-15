package com.agilespace.backend.service;

import com.agilespace.backend.domain.Conversation;
import com.agilespace.backend.domain.DirectMessage;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.ConversationDto;
import com.agilespace.backend.dto.MessageDto;
import com.agilespace.backend.dto.UserRefDto;
import com.agilespace.backend.repository.ConversationRepository;
import com.agilespace.backend.repository.DirectMessageRepository;
import com.agilespace.backend.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
@DisplayName("ConversationService - mensagens diretas só entre quem compartilha squad")
class ConversationServiceTest {

    @Mock private ConversationRepository conversationRepository;
    @Mock private DirectMessageRepository messageRepository;
    @Mock private UserRepository userRepository;
    @Mock private SquadAudienceService audienceService;
    @Mock private UserSignalService signalService;
    @InjectMocks private ConversationService service;

    private static User user(String id) {
        return User.builder().id(id).name("Nome " + id).email(id + "@totvs.com.br").build();
    }

    private void squadsOf(String userId, String... squads) {
        lenient().when(audienceService.contextFor(eq(userId), any())).thenReturn(Optional.of(
                new SquadAudienceService.UserContext(user(userId), squads.length > 0 ? squads[0] : null,
                        List.of(squads), Set.of("MEMBER"), false)));
    }

    private Conversation conv(String a, String b) {
        return Conversation.builder().id("c1").userAId(a).userBId(b)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    @Test
    @DisplayName("Sem squad em comum: 403 e nada é criado")
    void openWithoutSharedSquadIsForbidden() {
        when(userRepository.findById("bob")).thenReturn(Optional.of(user("bob")));
        when(conversationRepository.findByUserAIdAndUserBId("ana", "bob")).thenReturn(Optional.empty());
        squadsOf("ana", "SQA");
        squadsOf("bob", "SQB");
        when(audienceService.memberUserIdsOf(List.of("SQA"))).thenReturn(Set.of("ana", "carla"));
        when(audienceService.memberUserIdsOf(List.of("SQB"))).thenReturn(Set.of("bob"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.open("ana", "MEMBER", "bob"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(conversationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Consigo mesmo: 400; usuário inexistente: 404")
    void openSelfOrUnknown() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.open("ana", "MEMBER", "ana")).getStatusCode());
        when(userRepository.findById("ghost")).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.open("ana", "MEMBER", "ghost")).getStatusCode());
    }

    @Test
    @DisplayName("Squad em comum: cria a conversa com o par canônico (a < b)")
    void openCreatesCanonicalPair() {
        when(userRepository.findById("ana")).thenReturn(Optional.of(user("ana")));
        when(conversationRepository.findByUserAIdAndUserBId("ana", "zeca")).thenReturn(Optional.empty());
        squadsOf("zeca", "SQA");
        when(audienceService.memberUserIdsOf(List.of("SQA"))).thenReturn(Set.of("ana", "zeca"));
        when(conversationRepository.saveAndFlush(any(Conversation.class))).thenAnswer(i -> i.getArgument(0));

        ConversationDto dto = service.open("zeca", "MEMBER", "ana");

        verify(conversationRepository).saveAndFlush(argThat(c -> "ana".equals(c.getUserAId()) && "zeca".equals(c.getUserBId())));
        assertEquals("ana", dto.otherUser().id());
        assertNull(dto.lastMessage());
        assertEquals(0, dto.unreadCount());
    }

    @Test
    @DisplayName("Conversa já existente é devolvida sem reavaliar squad")
    void openReturnsExisting() {
        when(userRepository.findById("bob")).thenReturn(Optional.of(user("bob")));
        when(conversationRepository.findByUserAIdAndUserBId("ana", "bob")).thenReturn(Optional.of(conv("ana", "bob")));
        when(messageRepository.countUnreadByConversation("ana")).thenReturn(List.<Object[]>of(new Object[]{"c1", 3L}));

        ConversationDto dto = service.open("ana", "MEMBER", "bob");

        assertEquals("c1", dto.id());
        assertEquals(3, dto.unreadCount());
        verify(audienceService, never()).contextFor(any(), any());
    }

    @Test
    @DisplayName("Ler mensagens de conversa alheia: 403; inexistente: 404")
    void messagesRequireParticipation() {
        when(conversationRepository.findById("c1")).thenReturn(Optional.of(conv("bob", "carla")));
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> service.messages("ana", "c1", 50, null)).getStatusCode());
        when(conversationRepository.findById("nope")).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.send("ana", "nope", "oi")).getStatusCode());
    }

    @Test
    @DisplayName("Mensagens voltam em ordem crescente")
    void messagesAscending() {
        when(conversationRepository.findById("c1")).thenReturn(Optional.of(conv("ana", "bob")));
        LocalDateTime t = LocalDateTime.now();
        when(messageRepository.findLatest(eq("c1"), any())).thenReturn(List.of(
                DirectMessage.builder().id("m2").conversationId("c1").senderId("bob").body("2").createdAt(t).build(),
                DirectMessage.builder().id("m1").conversationId("c1").senderId("ana").body("1").createdAt(t.minusMinutes(1)).build()));

        List<MessageDto> out = service.messages("ana", "c1", null, null);

        assertEquals(List.of("m1", "m2"), out.stream().map(MessageDto::id).toList());
    }

    @Test
    @DisplayName("Enviar: valida 1–2000, grava, marca lida para quem enviou e sinaliza os dois lados")
    void sendValidatesAndSignals() {
        Conversation c = conv("ana", "bob");
        when(conversationRepository.findById("c1")).thenReturn(Optional.of(c));
        assertThrows(ResponseStatusException.class, () -> service.send("ana", "c1", "   "));
        assertThrows(ResponseStatusException.class, () -> service.send("ana", "c1", "x".repeat(2001)));

        when(messageRepository.save(any(DirectMessage.class))).thenAnswer(i -> i.getArgument(0));
        MessageDto dto = service.send("ana", "c1", "  olá  ");

        assertEquals("olá", dto.body());
        assertEquals("ana", dto.senderId());
        assertNotNull(c.getLastReadAtA());
        verify(signalService).signal(List.of("bob", "ana"), UserSignalService.MESSAGE, "c1");
    }

    @Test
    @DisplayName("Lista: outro participante, última mensagem e não lidas por conversa")
    void listBuildsDtos() {
        Conversation c = conv("ana", "bob");
        when(conversationRepository.findByParticipant("bob")).thenReturn(List.of(c));
        when(messageRepository.findLastMessages(List.of("c1"))).thenReturn(List.of(
                DirectMessage.builder().id("m9").conversationId("c1").senderId("ana").body("oi").createdAt(LocalDateTime.now()).build()));
        when(messageRepository.countUnreadByConversation("bob")).thenReturn(List.<Object[]>of(new Object[]{"c1", 2L}));
        when(userRepository.findAllById(any())).thenReturn(List.of(user("ana")));

        List<ConversationDto> out = service.list("bob");

        assertEquals(1, out.size());
        assertEquals("ana", out.get(0).otherUser().id());
        assertEquals("m9", out.get(0).lastMessage().id());
        assertEquals(2, out.get(0).unreadCount());
    }

    @Test
    @DisplayName("Contatos: membros das minhas squads, sem eu mesmo")
    void contactsExcludeSelf() {
        squadsOf("ana", "SQA");
        when(audienceService.memberUserIdsOf(List.of("SQA"))).thenReturn(Set.of("ana", "bob"));
        when(userRepository.findActiveByIds(Set.of("bob"))).thenReturn(List.of(user("bob")));

        List<UserRefDto> out = service.contacts("ana", "MEMBER");

        assertEquals(List.of("bob"), out.stream().map(UserRefDto::id).toList());
    }

    @Test
    @DisplayName("Marcar como lida grava a leitura do lado certo e manda UNREAD")
    void markRead() {
        Conversation c = conv("ana", "bob");
        when(conversationRepository.findById("c1")).thenReturn(Optional.of(c));
        service.markRead("bob", "c1");
        assertNotNull(c.getLastReadAtB());
        assertNull(c.getLastReadAtA());
        verify(signalService).signal("bob", UserSignalService.UNREAD);
    }
}
