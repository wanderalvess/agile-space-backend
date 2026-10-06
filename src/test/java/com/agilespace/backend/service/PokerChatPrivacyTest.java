package com.agilespace.backend.service;

import com.agilespace.backend.domain.PokerChatMessage;
import com.agilespace.backend.repository.PokerChatMessageRepository;
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
    @Mock private PokerWebSocketHandler webSocketHandler;
    @InjectMocks private PokerService service;

    private static PokerChatMessage dm(String sender, String channel) {
        return PokerChatMessage.builder().id("m1").senderId(sender).senderName("x").channelId(channel).text("oi").kind("text").build();
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
        when(chatMessageRepository.save(any(PokerChatMessage.class))).thenAnswer(i -> i.getArgument(0));
        service.saveChatMessage("room", dm("ana", "dm_ana_bia"), "ana");
        verify(webSocketHandler).broadcastEventToUsers(eq("room"), eq("CHAT_MESSAGE_SAVED"), any(), eq(Set.of("ana", "bia")));
        verify(webSocketHandler, never()).broadcastEvent(any(), any(), any());
    }

    @Test
    void canalGeralContinuaParaASalaToda() {
        when(chatMessageRepository.save(any(PokerChatMessage.class))).thenAnswer(i -> i.getArgument(0));
        service.saveChatMessage("room", dm("ana", "geral"), "ana");
        verify(webSocketHandler).broadcastEvent(eq("room"), eq("CHAT_MESSAGE_SAVED"), any());
    }
}
