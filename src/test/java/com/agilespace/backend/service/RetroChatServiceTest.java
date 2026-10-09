package com.agilespace.backend.service;

import com.agilespace.backend.domain.RetroBoard;
import com.agilespace.backend.domain.RetroChatMessage;
import com.agilespace.backend.domain.RetroParticipant;
import com.agilespace.backend.repository.RetroBoardRepository;
import com.agilespace.backend.repository.RetroCardRepository;
import com.agilespace.backend.repository.RetroChatMessageRepository;
import com.agilespace.backend.repository.RetroParticipantRepository;
import com.agilespace.backend.websocket.RetroWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RetroService - Chat do time")
class RetroChatServiceTest {

    @Mock private RetroBoardRepository boardRepository;
    @Mock private RetroParticipantRepository participantRepository;
    @Mock private RetroCardRepository cardRepository;
    @Mock private RetroChatMessageRepository chatMessageRepository;
    @Mock private RetroWebSocketHandler webSocketHandler;
    @Mock private SquadAccessService squadAccessService;

    @InjectMocks
    private RetroService service;

    private static final String BOARD = "retro-1";

    @BeforeEach
    void setUp() {
        RetroBoard board = RetroBoard.builder().id(BOARD).creatorId("creator").title("Retro").build();
        lenient().when(boardRepository.findById(BOARD)).thenReturn(Optional.of(board));
        RetroParticipant ana = new RetroParticipant();
        ana.setId("ana");
        ana.setNickname("Ana Souza");
        RetroParticipant bia = new RetroParticipant();
        bia.setId("bia");
        bia.setNickname("Bia");
        lenient().when(participantRepository.findByBoardIdAndId(BOARD, "ana")).thenReturn(Optional.of(ana));
        lenient().when(participantRepository.findByBoardIdAndId(BOARD, "bia")).thenReturn(Optional.of(bia));
    }

    private RetroChatMessage msg(String channel, String text) {
        return RetroChatMessage.builder().channelId(channel).senderId("hacker").senderName("Ana")
                .senderCategory("Developer").text(text).kind("text").build();
    }

    private RetroChatMessage stored(String id, String channel, String sender) {
        return RetroChatMessage.builder().id(id).boardId(BOARD).channelId(channel).senderId(sender)
                .senderName("X").text("oi").kind("text").ts("2026-01-01T00:00:00.000Z").build();
    }

    private void assertStatus(HttpStatus expected, org.junit.jupiter.api.function.Executable call) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, call);
        assertEquals(expected, ex.getStatusCode());
    }

    @Test
    @DisplayName("Participante envia mensagem: senderId vem do caller, ts do servidor, broadcast emitido")
    void shouldSaveAndBroadcast() {
        when(chatMessageRepository.save(any(RetroChatMessage.class))).thenAnswer(i -> i.getArgument(0));

        RetroChatMessage saved = service.saveChatMessage(BOARD, msg("geral", "olá"), "ana");

        assertEquals("ana", saved.getSenderId());
        assertEquals(BOARD, saved.getBoardId());
        assertNotNull(saved.getId());
        assertTrue(saved.getTs().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
        verify(webSocketHandler).broadcastEvent(BOARD, "CHAT_MESSAGE_SAVED", saved);
    }

    @Test
    @DisplayName("Criador do board (sem registro de participante) também pode escrever")
    void creatorCanWrite() {
        when(chatMessageRepository.save(any(RetroChatMessage.class))).thenAnswer(i -> i.getArgument(0));
        assertEquals("creator", service.saveChatMessage(BOARD, msg("role-QA", "x"), "creator").getSenderId());
    }

    @Test
    @DisplayName("Aceita DM entre os dois uids, inclusive para o segundo uid")
    void dmBothSidesCanWrite() {
        when(chatMessageRepository.save(any(RetroChatMessage.class))).thenAnswer(i -> i.getArgument(0));
        service.saveChatMessage(BOARD, msg("dm_ana_bia", "oi"), "ana");
        service.saveChatMessage(BOARD, msg("dm_ana_bia", "oi"), "bia");
        verify(chatMessageRepository, times(2)).save(any(RetroChatMessage.class));
    }

    @Test
    @DisplayName("Não participante não lê nem escreve")
    void nonParticipantForbidden() {
        assertStatus(HttpStatus.FORBIDDEN, () -> service.saveChatMessage(BOARD, msg("geral", "x"), "intruso"));
        assertStatus(HttpStatus.FORBIDDEN, () -> service.getChatMessages(BOARD, "geral", "intruso"));
        assertStatus(HttpStatus.FORBIDDEN, () -> service.saveChatMessage(BOARD, msg("geral", "x"), null));
        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    @DisplayName("Board inexistente responde 404")
    void boardNotFound() {
        when(boardRepository.findById("nope")).thenReturn(Optional.empty());
        assertStatus(HttpStatus.NOT_FOUND, () -> service.getChatMessages("nope", "geral", "ana"));
    }

    @Test
    @DisplayName("Canal inválido responde 400")
    void invalidChannel() {
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.saveChatMessage(BOARD, msg("qualquer", "x"), "ana"));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.saveChatMessage(BOARD, msg("role-Hacker", "x"), "ana"));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.saveChatMessage(BOARD, msg(null, "x"), "ana"));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.getChatMessages(BOARD, "geral2", "ana"));
    }

    @Test
    @DisplayName("DM de terceiros é proibida para leitura e escrita")
    void dmOfOthersForbidden() {
        assertStatus(HttpStatus.FORBIDDEN, () -> service.getChatMessages(BOARD, "dm_ana_creator", "bia"));
        assertStatus(HttpStatus.FORBIDDEN, () -> service.saveChatMessage(BOARD, msg("dm_ana_creator", "x"), "bia"));
        assertStatus(HttpStatus.FORBIDDEN, () -> service.saveChatMessage(BOARD, msg("dm_ana_", "x"), "ana"));
        verify(chatMessageRepository, never()).findByBoardIdAndChannelIdOrderByTsDesc(any(), any(), any());
    }

    @Test
    @DisplayName("Texto vazio, longo, kind e nome inválidos respondem 400")
    void invalidPayload() {
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.saveChatMessage(BOARD, msg("geral", "   "), "ana"));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.saveChatMessage(BOARD, msg("geral", null), "ana"));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.saveChatMessage(BOARD, msg("geral", "a".repeat(8001)), "ana"));
        RetroChatMessage badKind = msg("geral", "x");
        badKind.setKind("html");
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.saveChatMessage(BOARD, badKind, "ana"));
        RetroChatMessage longName = msg("geral", "x");
        longName.setSenderName("n".repeat(121));
        // criador sem registro de participante usa o nome do corpo, que é validado
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.saveChatMessage(BOARD, longName, "creator"));
        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    @DisplayName("Texto de exatamente 8000 caracteres e kind code são aceitos")
    void boundaryAccepted() {
        when(chatMessageRepository.save(any(RetroChatMessage.class))).thenAnswer(i -> i.getArgument(0));
        RetroChatMessage m = msg("geral", "a".repeat(8000));
        m.setKind("code");
        assertEquals("code", service.saveChatMessage(BOARD, m, "ana").getKind());
    }

    @Test
    @DisplayName("Id já existente não sobrescreve mensagem de outro (409)")
    void duplicateIdConflict() {
        RetroChatMessage m = msg("geral", "x");
        m.setId("m1");
        when(chatMessageRepository.existsById("m1")).thenReturn(true);
        assertStatus(HttpStatus.CONFLICT, () -> service.saveChatMessage(BOARD, m, "ana"));
        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    @DisplayName("Lista mensagens do canal para participante")
    void listMessages() {
        RetroChatMessage newest = stored("m2", "geral", "bia");
        RetroChatMessage oldest = stored("m1", "geral", "ana");
        // o repositório devolve da mais nova para a mais antiga; o service entrega em ordem cronológica
        when(chatMessageRepository.findByBoardIdAndChannelIdOrderByTsDesc(eq(BOARD), eq("geral"), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(List.of(newest, oldest));
        assertEquals(List.of(oldest, newest), service.getChatMessages(BOARD, "geral", "bia"));
    }

    @Test
    @DisplayName("Histórico pede só as últimas mensagens do canal")
    void historyIsLimited() {
        service.getChatMessages(BOARD, "geral", "ana");
        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> page =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(chatMessageRepository).findByBoardIdAndChannelIdOrderByTsDesc(eq(BOARD), eq("geral"), page.capture());
        assertEquals(RetroService.CHAT_HISTORY_LIMIT, page.getValue().getPageSize());
    }

    @Test
    @DisplayName("Nome exibido vem do cadastro do participante, não do corpo")
    void senderNameComesFromParticipant() {
        when(chatMessageRepository.save(any(RetroChatMessage.class))).thenAnswer(i -> i.getArgument(0));
        RetroChatMessage spoof = msg("geral", "oi");
        spoof.setSenderName("Bia");
        assertEquals("Ana Souza", service.saveChatMessage(BOARD, spoof, "ana").getSenderName());
    }

    @Test
    @DisplayName("DM só é entregue por WebSocket aos dois participantes; canal público vai ao board todo")
    void dmIsBroadcastOnlyToItsTwoUsers() {
        when(chatMessageRepository.save(any(RetroChatMessage.class))).thenAnswer(i -> i.getArgument(0));

        RetroChatMessage dm = service.saveChatMessage(BOARD, msg("dm_ana_bia", "segredo"), "ana");
        verify(webSocketHandler).broadcastEventToUsers(BOARD, "CHAT_MESSAGE_SAVED", dm, java.util.Set.of("ana", "bia"));
        verify(webSocketHandler, never()).broadcastEvent(eq(BOARD), eq("CHAT_MESSAGE_SAVED"), eq(dm));

        RetroChatMessage pub = service.saveChatMessage(BOARD, msg("geral", "oi"), "bia");
        verify(webSocketHandler).broadcastEvent(BOARD, "CHAT_MESSAGE_SAVED", pub);
    }

    @Test
    @DisplayName("Remoção de mensagem de DM também só é avisada aos dois participantes")
    void dmDeleteIsTargeted() {
        RetroChatMessage dm = stored("m5", "dm_ana_bia", "bia");
        when(chatMessageRepository.findById("m5")).thenReturn(Optional.of(dm));

        service.deleteChatMessage(BOARD, "m5", "bia");

        verify(webSocketHandler).broadcastEventToUsers(BOARD, "CHAT_MESSAGE_DELETED",
                Map.of("messageId", "m5", "channelId", "dm_ana_bia"), java.util.Set.of("ana", "bia"));
    }

    @Test
    @DisplayName("Autor apaga a própria mensagem e o broadcast leva messageId e channelId")
    void authorDeletes() {
        RetroChatMessage m = stored("m1", "geral", "ana");
        when(chatMessageRepository.findById("m1")).thenReturn(Optional.of(m));

        service.deleteChatMessage(BOARD, "m1", "ana");

        verify(chatMessageRepository).delete(m);
        verify(webSocketHandler).broadcastEvent(BOARD, "CHAT_MESSAGE_DELETED",
                Map.of("messageId", "m1", "channelId", "geral"));
    }

    @Test
    @DisplayName("Criador do board modera canal público, mas não DM")
    void creatorModeratesPublicOnly() {
        RetroChatMessage pub = stored("m1", "geral", "ana");
        RetroChatMessage dm = stored("m2", "dm_ana_bia", "ana");
        when(chatMessageRepository.findById("m1")).thenReturn(Optional.of(pub));
        when(chatMessageRepository.findById("m2")).thenReturn(Optional.of(dm));

        service.deleteChatMessage(BOARD, "m1", "creator");
        verify(chatMessageRepository).delete(pub);

        assertStatus(HttpStatus.FORBIDDEN, () -> service.deleteChatMessage(BOARD, "m2", "creator"));
        verify(chatMessageRepository, never()).delete(dm);
    }

    @Test
    @DisplayName("Outro participante não apaga mensagem alheia")
    void otherCannotDelete() {
        RetroChatMessage m = stored("m1", "geral", "ana");
        when(chatMessageRepository.findById("m1")).thenReturn(Optional.of(m));
        assertStatus(HttpStatus.FORBIDDEN, () -> service.deleteChatMessage(BOARD, "m1", "bia"));
        verify(chatMessageRepository, never()).delete(any(RetroChatMessage.class));
    }

    @Test
    @DisplayName("Mensagem inexistente ou de outro board responde 404")
    void deleteNotFound() {
        RetroChatMessage other = stored("m9", "geral", "ana");
        other.setBoardId("outro-board");
        when(chatMessageRepository.findById("m9")).thenReturn(Optional.of(other));
        when(chatMessageRepository.findById("zzz")).thenReturn(Optional.empty());
        assertStatus(HttpStatus.NOT_FOUND, () -> service.deleteChatMessage(BOARD, "m9", "ana"));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.deleteChatMessage(BOARD, "zzz", "ana"));
    }

    @Test
    @DisplayName("Não participante não apaga")
    void nonParticipantCannotDelete() {
        assertStatus(HttpStatus.FORBIDDEN, () -> service.deleteChatMessage(BOARD, "m1", "intruso"));
        verify(chatMessageRepository, never()).delete(any(RetroChatMessage.class));
    }
}
