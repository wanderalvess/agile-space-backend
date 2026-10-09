package com.agilespace.backend.service;

import com.agilespace.backend.domain.KnowledgeConversation;
import com.agilespace.backend.domain.KnowledgeDocument;
import com.agilespace.backend.domain.KnowledgeTokenUsage;
import com.agilespace.backend.repository.KnowledgeConversationRepository;
import com.agilespace.backend.repository.KnowledgeRepository;
import com.agilespace.backend.repository.KnowledgeTokenUsageRepository;
import com.agilespace.backend.repository.KnowledgeUserAiSettingsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("KnowledgeService - endurecimento: autoria, lixeira, limites e consumo de tokens")
class KnowledgeServiceHardeningTest {

    @Mock private KnowledgeRepository repository;
    @Mock private KnowledgeConversationRepository conversationRepository;
    @Mock private KnowledgeUserAiSettingsRepository settingsRepository;
    @Mock private KnowledgeTokenUsageRepository tokenRepository;

    private KnowledgeService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeService(repository, conversationRepository, settingsRepository, tokenRepository, new ObjectMapper());
    }

    private static HttpStatus statusOf(Supplier<?> call) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, call::get);
        return HttpStatus.valueOf(ex.getStatusCode().value());
    }

    private KnowledgeDocument existing(UUID id, String author, String status) {
        return KnowledgeDocument.builder().id(id).title("Guia").content("x").authorId(author).status(status).build();
    }

    @Test
    @DisplayName("POST ignora id, views e lixeira vindos do corpo (não sobrescreve documento alheio)")
    void createIgnoresClientId() {
        when(repository.save(any(KnowledgeDocument.class))).thenAnswer(i -> i.getArgument(0));
        KnowledgeDocument body = KnowledgeDocument.builder().id(UUID.randomUUID()).title("Novo").authorId("u1").views(999)
                .deletedBy("x").build();

        KnowledgeDocument saved = service.saveOrUpdateDocument(body);

        assertNull(saved.getId());
        assertEquals(0, saved.getViews());
        assertNull(saved.getDeletedBy());
        assertEquals("published", saved.getStatus());
    }

    @Test
    @DisplayName("POST recusa título vazio, status inválido, 'deleted' e conteúdo gigante")
    void createValidates() {
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.saveOrUpdateDocument(KnowledgeDocument.builder().title(" ").authorId("u").build())));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.saveOrUpdateDocument(KnowledgeDocument.builder().title("t").authorId("u").status("hack").build())));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.saveOrUpdateDocument(KnowledgeDocument.builder().title("t").authorId("u").status("deleted").build())));
        String big = "a".repeat(KnowledgeService.MAX_CONTENT + 1);
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.saveOrUpdateDocument(KnowledgeDocument.builder().title("t").authorId("u").content(big).build())));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("PUT que muda status para 'deleted' só vale para autor ou admin")
    void putCannotBypassDeleteRule() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(existing(id, "autor", "published")));
        KnowledgeDocument change = KnowledgeDocument.builder().title("Guia").status("deleted").build();

        assertEquals(HttpStatus.FORBIDDEN, statusOf(() -> service.updateDocument(id, change, "intruso", false)));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("PUT de admin ou autor com 'deleted' grava deletedAt/deletedBy")
    void putDeleteByAuthor() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(existing(id, "autor", "published")));
        when(repository.save(any(KnowledgeDocument.class))).thenAnswer(i -> i.getArgument(0));

        KnowledgeDocument saved = service.updateDocument(id, KnowledgeDocument.builder().title("Guia").status("deleted").build(), "autor", false);

        assertEquals("deleted", saved.getStatus());
        assertEquals("autor", saved.getDeletedBy());
        assertNotNull(saved.getDeletedAt());
    }

    @Test
    @DisplayName("PUT sem status mantém o status atual (não deixa o documento sumir das listas)")
    void putWithoutStatusKeepsStatus() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(existing(id, "autor", "published")));
        when(repository.save(any(KnowledgeDocument.class))).thenAnswer(i -> i.getArgument(0));

        KnowledgeDocument saved = service.updateDocument(id, KnowledgeDocument.builder().title("Guia 2").build(), "outro", false);

        assertEquals("published", saved.getStatus());
        assertEquals("Guia 2", saved.getTitle());
    }

    @Test
    @DisplayName("Restaurar da lixeira limpa deletedAt e deletedBy")
    void restoreClearsTrashMarks() {
        UUID id = UUID.randomUUID();
        KnowledgeDocument trashed = existing(id, "autor", "deleted");
        trashed.setDeletedBy("autor");
        trashed.setDeletedAt(java.time.LocalDateTime.now());
        when(repository.findById(id)).thenReturn(Optional.of(trashed));
        when(repository.save(any(KnowledgeDocument.class))).thenAnswer(i -> i.getArgument(0));

        KnowledgeDocument saved = service.updateDocument(id, KnowledgeDocument.builder().title("Guia").status("published").build(), "qualquer", false);

        assertEquals("published", saved.getStatus());
        assertNull(saved.getDeletedAt());
        assertNull(saved.getDeletedBy());
    }

    @Test
    @DisplayName("Listagem sem ordenação passa a ordenar por updatedAt decrescente")
    void listDefaultsToNewestFirst() {
        when(repository.findByStatusNot(eq("deleted"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.listDocuments(null, null, null, PageRequest.of(0, 20));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByStatusNot(eq("deleted"), captor.capture());
        Sort.Order order = captor.getValue().getSort().getOrderFor("updatedAt");
        assertNotNull(order);
        assertTrue(order.isDescending());
    }

    @Test
    @DisplayName("Consumo de tokens ignora negativos e limita cada chamada")
    void tokenUsageClamped() {
        KnowledgeTokenUsage usage = KnowledgeTokenUsage.builder().userId("u1").totalTokens(100L).build();
        when(tokenRepository.findById("u1")).thenReturn(Optional.of(usage));

        service.incrementTokenUsage("u1", "Ana", -5000L);
        assertEquals(100L, usage.getTotalTokens());

        service.incrementTokenUsage("u1", "Ana", Long.MAX_VALUE);
        assertEquals(100L + KnowledgeService.MAX_TOKENS_PER_CALL, usage.getTotalTokens());
    }

    @Test
    @DisplayName("Mensagem de conversa acima do limite é recusada (400)")
    void messageTooLong() {
        UUID id = UUID.randomUUID();
        KnowledgeConversation conv = KnowledgeConversation.builder().id(id).userId("u1").messages("[]").build();
        when(conversationRepository.findById(id)).thenReturn(Optional.of(conv));
        Map<String, Object> huge = Map.of("role", "user", "content", "a".repeat(KnowledgeService.MAX_MESSAGE_CHARS + 10));

        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.appendMessage(id, "u1", huge)));
        verify(conversationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Conversa de outro usuário continua inacessível ao anexar mensagem")
    void appendToForeignConversation() {
        UUID id = UUID.randomUUID();
        when(conversationRepository.findById(id)).thenReturn(Optional.of(
                KnowledgeConversation.builder().id(id).userId("dono").messages("[]").build()));

        assertThrows(IllegalArgumentException.class, () -> service.appendMessage(id, "outro", Map.of("role", "user", "content", "oi")));
    }

    @Test
    @DisplayName("Título de conversa vazio ganha padrão e título longo é cortado")
    void conversationTitle() {
        when(conversationRepository.save(any(KnowledgeConversation.class))).thenAnswer(i -> i.getArgument(0));
        assertEquals("Nova Consulta de Conhecimento", service.createConversation("u1", "  ").getTitle());
        assertEquals(255, service.createConversation("u1", "t".repeat(400)).getTitle().length());
    }

    @Test
    @DisplayName("Tags demais ou grandes demais são recusadas")
    void tagLimits() {
        Set<String> many = new java.util.HashSet<>();
        for (int i = 0; i <= KnowledgeService.MAX_TAGS; i++) many.add("t" + i);
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.saveOrUpdateDocument(KnowledgeDocument.builder().title("t").authorId("u").tags(many).build())));
        Set<String> longTag = Set.of("x".repeat(KnowledgeService.MAX_TAG_LENGTH + 1));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.saveOrUpdateDocument(KnowledgeDocument.builder().title("t").authorId("u").tags(longTag).build())));
    }
}
