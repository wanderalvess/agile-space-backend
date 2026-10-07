package com.agilespace.backend.service;

import com.agilespace.backend.domain.PokerChatMessage;
import com.agilespace.backend.domain.PokerParticipant;
import com.agilespace.backend.domain.PokerRoom;
import com.agilespace.backend.repository.PokerChatMessageRepository;
import com.agilespace.backend.repository.PokerParticipantRepository;
import com.agilespace.backend.repository.PokerRoomRepository;
import com.agilespace.backend.websocket.PokerWebSocketHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PokerService - privacidade das mensagens diretas do chat")
class PokerChatPrivacyTest {

    @Mock private PokerChatMessageRepository chatMessageRepository;
    @Mock private PokerRoomRepository roomRepository;
    @Mock private PokerParticipantRepository participantRepository;
    @Mock private PokerWebSocketHandler webSocketHandler;
    @InjectMocks private PokerService service;

    private static PokerChatMessage dm(String sender, String channel) {
        return PokerChatMessage.builder().id("m1").senderId(sender).senderName("x").channelId(channel).text("oi").kind("text").build();
    }


    private void roomWithParticipant(String userId) {
        when(roomRepository.findById("room")).thenReturn(Optional.of(PokerRoom.builder().id("room").creatorId("dono").build()));
        when(participantRepository.findById("room_" + userId))
                .thenReturn(Optional.of(PokerParticipant.builder().id(userId).roomId("room").nickname("Nome " + userId).build()));
    }

    private void assertStatus(HttpStatus expected, org.junit.jupiter.api.function.Executable call) {
        assertEquals(expected, assertThrows(ResponseStatusException.class, call).getStatusCode());
    }

    @Test
    void terceiroNaoLeHistoricoDeDm() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.getChatMessages("room", "dm_ana_bia", "carlos"));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verifyNoInteractions(chatMessageRepository);
    }

    @Test
    void participanteLeHistoricoDeDm() {
        when(chatMessageRepository.findByRoomIdAndChannelIdOrderByTsAsc("room", "dm_ana_bia")).thenReturn(List.of());
        assertEquals(List.of(), service.getChatMessages("room", "dm_ana_bia", "bia"));
    }

    @Test
    void naoEnviaDmEmCanalDeOutrosDois() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.saveChatMessage("room", dm("carlos", "dm_ana_bia"), "carlos"));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
    }

    @Test
    void dmEntregueSoAosDoisParticipantes() {
        roomWithParticipant("ana");
        when(chatMessageRepository.save(any(PokerChatMessage.class))).thenAnswer(i -> i.getArgument(0));
        service.saveChatMessage("room", dm("ana", "dm_ana_bia"), "ana");
        verify(webSocketHandler).broadcastEventToUsers(eq("room"), eq("CHAT_MESSAGE_SAVED"), any(), eq(Set.of("ana", "bia")));
        verify(webSocketHandler, never()).broadcastEvent(any(), any(), any());
    }

    @Test
    void canalGeralContinuaParaASalaToda() {
        roomWithParticipant("ana");
        when(chatMessageRepository.save(any(PokerChatMessage.class))).thenAnswer(i -> i.getArgument(0));
        service.saveChatMessage("room", dm("ana", "geral"), "ana");
        verify(webSocketHandler).broadcastEvent(eq("room"), eq("CHAT_MESSAGE_SAVED"), any());
    }

    @Test
    void idDeMensagemExistenteNaoSobrescreve() {
        roomWithParticipant("ana");
        when(chatMessageRepository.existsById("m1")).thenReturn(true);
        assertStatus(HttpStatus.CONFLICT, () -> service.saveChatMessage("room", dm("ana", "geral"), "ana"));
        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    void foraDaSalaNaoEnviaMensagem() {
        when(roomRepository.findById("room")).thenReturn(Optional.of(PokerRoom.builder().id("room").creatorId("dono").build()));
        when(participantRepository.findById("room_intruso")).thenReturn(Optional.empty());
        assertStatus(HttpStatus.FORBIDDEN, () -> service.saveChatMessage("room", dm("intruso", "geral"), "intruso"));
    }

    @Test
    void nomeDoRemetenteVemDoCadastro() {
        roomWithParticipant("ana");
        when(chatMessageRepository.save(any(PokerChatMessage.class))).thenAnswer(i -> i.getArgument(0));
        assertEquals("Nome ana", service.saveChatMessage("room", dm("ana", "geral"), "ana").getSenderName());
    }

    @Test
    void textoVazioOuGrandeDemaisERejeitado() {
        PokerChatMessage vazio = dm("ana", "geral");
        vazio.setText("  ");
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.saveChatMessage("room", vazio, "ana"));
        PokerChatMessage grande = dm("ana", "geral");
        grande.setText("a".repeat(8001));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.saveChatMessage("room", grande, "ana"));
    }

    @Test
    void naoApagaMensagemDeOutraSala() {
        PokerChatMessage outra = PokerChatMessage.builder().id("m9").roomId("outra").senderId("ana").channelId("geral").build();
        when(chatMessageRepository.findById("m9")).thenReturn(Optional.of(outra));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.deleteChatMessage("room", "m9", "ana", "ADMIN"));
        verify(chatMessageRepository, never()).delete(any());
    }

    @Test
    void dmComUnderscoreNoUidChegaAoDestinatarioCerto() {
        roomWithParticipant("ana_1");
        when(chatMessageRepository.save(any(PokerChatMessage.class))).thenAnswer(i -> i.getArgument(0));
        service.saveChatMessage("room", dm("ana_1", "dm_ana_1_bia_2"), "ana_1");
        verify(webSocketHandler).broadcastEventToUsers(eq("room"), eq("CHAT_MESSAGE_SAVED"), any(), eq(Set.of("ana_1", "bia_2")));
    }
}
