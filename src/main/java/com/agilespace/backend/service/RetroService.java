package com.agilespace.backend.service;

import com.agilespace.backend.domain.RetroBoard;
import com.agilespace.backend.domain.RetroCard;
import com.agilespace.backend.domain.RetroChatMessage;
import com.agilespace.backend.domain.RetroParticipant;
import com.agilespace.backend.repository.RetroBoardRepository;
import com.agilespace.backend.repository.RetroCardRepository;
import com.agilespace.backend.repository.RetroChatMessageRepository;
import com.agilespace.backend.repository.RetroParticipantRepository;
import com.agilespace.backend.websocket.RetroWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class RetroService {

    private final RetroBoardRepository boardRepository;
    private final RetroParticipantRepository participantRepository;
    private final RetroCardRepository cardRepository;
    private final RetroChatMessageRepository chatMessageRepository;
    private final RetroWebSocketHandler webSocketHandler;

    // --- Board Logic ---
    @Transactional(readOnly = true)
    public List<RetroBoard> listBoardsBySprintId(String sprintId) {
        return boardRepository.findBySprintIdOrderByCreatedAtDesc(sprintId);
    }

    @Transactional(readOnly = true)
    public List<RetroBoard> listBoardsByTeam(String team) {
        return boardRepository.findByTeamIgnoreCaseOrderByCreatedAtDesc(team);
    }

    @Transactional(readOnly = true)
    public List<RetroBoard> listBoardsBySquadId(String squadId) {
        return boardRepository.findBySquadIdIgnoreCaseOrderByCreatedAtDesc(squadId);
    }

    @Transactional(readOnly = true)
    public Optional<RetroBoard> getBoard(String boardId) {
        return boardRepository.findById(boardId);
    }

    @Transactional
    public RetroBoard saveOrUpdateBoard(RetroBoard board) {
        RetroBoard saved = boardRepository.save(board);
        webSocketHandler.broadcastEvent(saved.getId(), "BOARD_UPDATED", saved);
        return saved;
    }

    // --- Participants Logic ---
    @Transactional(readOnly = true)
    public List<RetroParticipant> getParticipants(String boardId) {
        return participantRepository.findByBoardId(boardId);
    }

    // Sem @Transactional de propósito: cada chamada a save()/findById() abaixo
    // já abre sua própria transação via Spring Data. Isso é o que permite o
    // catch abaixo tentar de novo depois de uma falha — dentro de UMA
    // transação compartilhada, uma DataIntegrityViolationException marca a
    // transação como rollback-only e qualquer save() seguinte no mesmo método
    // falharia ao tentar comitar, mesmo capturando a exceção.
    public RetroParticipant addOrUpdateParticipant(RetroParticipant incoming) {
        // ID composto implícito para garantir unicidade na tabela e evitar conflitos de restrição
        String dbId = incoming.getBoardId() + "_" + incoming.getId();
        incoming.setDbId(dbId);

        Optional<RetroParticipant> existing = participantRepository.findById(dbId);
        if (existing.isPresent()) {
            // Merge por campo, não replace total: o front dispara vários
            // "addOrUpdateParticipant" quase simultâneos pro mesmo participante
            // (auto-join da identidade global, sincronização de perfil, resposta
            // do check-in inicial) e cada um só sabe sobre o pedaço que está
            // mudando. Um save() do objeto inteiro faria o que chegasse por
            // último apagar campo que outro acabou de gravar (ex.: auto-join
            // sem healthCheckAnswer zerando a resposta que acabou de ser salva).
            RetroParticipant saved = participantRepository.save(mergeParticipant(existing.get(), incoming));
            webSocketHandler.broadcastEvent(incoming.getBoardId(), "PARTICIPANT_JOINED", saved);
            return saved;
        }

        RetroParticipant saved;
        try {
            saved = participantRepository.save(incoming);
        } catch (DataIntegrityViolationException e) {
            // Corrida na criação: duas requisições viram "não existe ainda" no
            // findById acima e tentam INSERT ao mesmo tempo — a segunda esbarra
            // na PK que a primeira acabou de commitar. Refaz como merge sobre a
            // linha que já existe agora em vez de propagar 500 pro cliente.
            saved = participantRepository.findById(dbId)
                    .map(row -> participantRepository.save(mergeParticipant(row, incoming)))
                    .orElseThrow(() -> e);
        }
        webSocketHandler.broadcastEvent(incoming.getBoardId(), "PARTICIPANT_JOINED", saved);
        return saved;
    }

    private RetroParticipant mergeParticipant(RetroParticipant existing, RetroParticipant incoming) {
        if (incoming.getNickname() != null) existing.setNickname(incoming.getNickname());
        if (incoming.getRole() != null) existing.setRole(incoming.getRole());
        if (incoming.getIsCreator() != null) existing.setIsCreator(incoming.getIsCreator());
        if (incoming.getGlobalRole() != null) existing.setGlobalRole(incoming.getGlobalRole());
        if (incoming.getHealthCheckAnswer() != null) existing.setHealthCheckAnswer(incoming.getHealthCheckAnswer());
        return existing;
    }

    @Transactional
    public void removeParticipant(String boardId, String userId) {
        participantRepository.deleteByBoardIdAndId(boardId, userId);
        webSocketHandler.broadcastEvent(boardId, "PARTICIPANT_LEFT", Map.of("userId", userId));
    }

    // --- Cards Logic ---
    @Transactional(readOnly = true)
    public List<RetroCard> getCards(String boardId) {
        return cardRepository.findByBoardId(boardId);
    }

    @Transactional
    public RetroCard saveOrUpdateCard(RetroCard card) {
        if (card.getId() != null) {
            cardRepository.findById(card.getId()).ifPresent(existing -> {
                if (!existing.getBoardId().equals(card.getBoardId())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                            "Cartão pertence a outro board.");
                }
            });
        }
        RetroCard saved = cardRepository.save(card);
        webSocketHandler.broadcastEvent(card.getBoardId(), "CARD_SAVED", saved);
        return saved;
    }

    @Transactional
    public void deleteCard(String boardId, String cardId) {
        Optional<RetroCard> cardOpt = cardRepository.findById(cardId);
        if (cardOpt.isPresent()) {
            RetroCard card = cardOpt.get();
            if (!card.getBoardId().equals(boardId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Cartão não pertence a este board.");
            }
            cardRepository.deleteById(cardId);
            webSocketHandler.broadcastEvent(card.getBoardId(), "CARD_DELETED", Map.of("cardId", cardId));
        }
    }

    @Transactional
    public void importActions(String boardId, List<RetroCard> cards) {
        for (RetroCard card : cards) {
            card.setBoardId(boardId);
            cardRepository.save(card);
        }
        webSocketHandler.broadcastEvent(boardId, "CARDS_IMPORTED", cards);
    }

    // --- Chat do time ---
    static final int CHAT_MAX_TEXT = 8000;
    static final int CHAT_MAX_SENDER_NAME = 120;
    static final int CHAT_MAX_SENDER_CATEGORY = 255;
    static final int CHAT_HISTORY_LIMIT = 100;
    private static final Set<String> CHAT_KINDS = Set.of("text", "code");
    private static final Pattern CHAT_PUBLIC_CHANNEL = Pattern.compile("geral|role-(Developer|QA|UX|Designer|Management)");
    private static final java.time.format.DateTimeFormatter CHAT_TS_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC);

    @Transactional(readOnly = true)
    public List<RetroChatMessage> getChatMessages(String boardId, String channelId, String callerId) {
        RetroBoard board = requireChatBoard(boardId);
        requireChatParticipant(board, callerId);
        requireChatChannelAccess(channelId, callerId);
        // Só as últimas mensagens do canal (a carga inicial é uma chamada por canal).
        List<RetroChatMessage> latest = new java.util.ArrayList<>(chatMessageRepository
                .findByBoardIdAndChannelIdOrderByTsDesc(boardId, channelId,
                        org.springframework.data.domain.PageRequest.of(0, CHAT_HISTORY_LIMIT)));
        java.util.Collections.reverse(latest);
        return latest;
    }

    @Transactional
    public RetroChatMessage saveChatMessage(String boardId, RetroChatMessage message, String callerId) {
        if (message == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mensagem inválida.");
        }
        RetroBoard board = requireChatBoard(boardId);
        Optional<RetroParticipant> participant = requireChatParticipant(board, callerId);
        requireChatChannelAccess(message.getChannelId(), callerId);

        String text = message.getText();
        if (text == null || text.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A mensagem não pode ser vazia.");
        }
        if (text.length() > CHAT_MAX_TEXT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A mensagem excede " + CHAT_MAX_TEXT + " caracteres.");
        }
        String kind = message.getKind() == null || message.getKind().isBlank() ? "text" : message.getKind();
        if (!CHAT_KINDS.contains(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de mensagem inválido.");
        }
        // O nome exibido vem do cadastro do participante: o corpo da requisição não pode
        // fazer uma mensagem aparecer como se fosse de outra pessoa. Só o criador sem
        // registro de participante cai no nome informado (validado).
        String senderName = participant.map(RetroParticipant::getNickname)
                .filter(n -> n != null && !n.isBlank())
                .orElse(message.getSenderName());
        if (senderName == null || senderName.trim().isEmpty() || senderName.length() > CHAT_MAX_SENDER_NAME) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome do remetente inválido.");
        }
        if (message.getSenderCategory() != null && message.getSenderCategory().length() > CHAT_MAX_SENDER_CATEGORY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Categoria do remetente inválida.");
        }

        String id = message.getId() == null || message.getId().trim().isEmpty()
                ? java.util.UUID.randomUUID().toString() : message.getId();
        // id vindo do cliente nunca sobrescreve uma mensagem existente (de outro autor ou board)
        if (chatMessageRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma mensagem com este id.");
        }

        RetroChatMessage toSave = RetroChatMessage.builder()
                .id(id)
                .boardId(boardId)
                .channelId(message.getChannelId())
                .senderId(callerId) // sempre o caller autenticado, nunca o corpo
                .senderName(senderName)
                .senderCategory(message.getSenderCategory())
                .text(text)
                .kind(kind)
                .ts(CHAT_TS_FORMAT.format(java.time.Instant.now()))
                .build();
        RetroChatMessage saved = chatMessageRepository.save(toSave);
        publishChatEvent(boardId, "CHAT_MESSAGE_SAVED", saved, saved.getChannelId(), callerId);
        return saved;
    }

    @Transactional
    public void deleteChatMessage(String boardId, String messageId, String callerId) {
        RetroBoard board = requireChatBoard(boardId);
        requireChatParticipant(board, callerId);
        RetroChatMessage message = chatMessageRepository.findById(messageId)
                .filter(m -> boardId.equals(m.getBoardId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mensagem não encontrada"));

        boolean isAuthor = callerId.equals(message.getSenderId());
        boolean isDm = message.getChannelId() != null && message.getChannelId().startsWith("dm_");
        // Em DM só o autor apaga; nos canais públicos o criador do board também modera.
        boolean isModerator = !isDm && callerId.equals(board.getCreatorId());
        if (!isAuthor && !isModerator) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sem permissão para excluir esta mensagem.");
        }

        chatMessageRepository.delete(message);
        publishChatEvent(boardId, "CHAT_MESSAGE_DELETED", Map.of(
                "messageId", messageId,
                "channelId", message.getChannelId()
        ), message.getChannelId(), callerId);
    }

    private RetroBoard requireChatBoard(String boardId) {
        return boardRepository.findById(boardId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Board não encontrado"));
    }

    /** Exige que o caller seja o criador ou um participante; devolve o registro de participante, se houver. */
    private Optional<RetroParticipant> requireChatParticipant(RetroBoard board, String callerId) {
        if (callerId == null || callerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a participantes deste board.");
        }
        Optional<RetroParticipant> participant = participantRepository.findByBoardId(board.getId()).stream()
                .filter(p -> callerId.equals(p.getId()))
                .findFirst();
        if (participant.isEmpty() && !callerId.equals(board.getCreatorId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a participantes deste board.");
        }
        return participant;
    }

    /**
     * Canais públicos vão para o board inteiro; DM só para as duas pessoas do canal
     * (o texto não pode trafegar para as sessões dos demais participantes).
     */
    private void publishChatEvent(String boardId, String eventType, Object payload, String channelId, String callerId) {
        if (channelId != null && channelId.startsWith("dm_")) {
            String rest = channelId.substring(3);
            String peer = rest.startsWith(callerId + "_")
                    ? rest.substring(callerId.length() + 1)
                    : rest.substring(0, rest.length() - callerId.length() - 1);
            webSocketHandler.broadcastEventToUsers(boardId, eventType, payload, new java.util.HashSet<>(List.of(callerId, peer)));
        } else {
            webSocketHandler.broadcastEvent(boardId, eventType, payload);
        }
    }

    /** Canais válidos: geral, role-categoria e dm_uidA_uidB (só os dois uids acessam). */
    private static void requireChatChannelAccess(String channelId, String callerId) {
        if (channelId == null || channelId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Canal inválido.");
        }
        if (CHAT_PUBLIC_CHANNEL.matcher(channelId).matches()) {
            return;
        }
        if (channelId.startsWith("dm_")) {
            String rest = channelId.substring(3);
            String asFirst = callerId + "_";
            String asSecond = "_" + callerId;
            boolean callerFirst = rest.startsWith(asFirst) && rest.length() > asFirst.length();
            boolean callerSecond = rest.endsWith(asSecond) && rest.length() > asSecond.length();
            if (callerFirst || callerSecond) {
                return;
            }
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sem acesso a esta conversa privada.");
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Canal inválido.");
    }
}
