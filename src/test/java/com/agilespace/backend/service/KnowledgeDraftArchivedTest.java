package com.agilespace.backend.service;

import com.agilespace.backend.domain.KnowledgeDocument;
import com.agilespace.backend.repository.KnowledgeConversationRepository;
import com.agilespace.backend.repository.KnowledgeDocumentVersionRepository;
import com.agilespace.backend.repository.KnowledgeRepository;
import com.agilespace.backend.repository.KnowledgeTokenUsageRepository;
import com.agilespace.backend.repository.KnowledgeUserAiSettingsRepository;
import com.agilespace.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Base de Conhecimento - estados Rascunho (draft) e Arquivado (archived)")
class KnowledgeDraftArchivedTest {

    @Mock private KnowledgeRepository repository;
    @Mock private KnowledgeConversationRepository conversationRepository;
    @Mock private KnowledgeUserAiSettingsRepository settingsRepository;
    @Mock private KnowledgeTokenUsageRepository tokenRepository;
    @Mock private KnowledgeDocumentVersionRepository versionRepository;
    @Mock private SquadAccessService squadAccessService;
    @Mock private UserRepository userRepository;

    private KnowledgeService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeService(repository, conversationRepository, settingsRepository, tokenRepository,
                new ObjectMapper(), versionRepository, squadAccessService, userRepository);
    }

    private KnowledgeDocument doc(String title, String author, String status, String squad, int minutesAgo) {
        return KnowledgeDocument.builder().id(UUID.randomUUID()).title(title).content("conteudo " + title)
                .authorId(author).squadId(squad).status(status)
                .updatedAt(LocalDateTime.now().minusMinutes(minutesAgo)).build();
    }

    private static List<String> titles(Page<KnowledgeDocument> page) {
        return page.getContent().stream().map(KnowledgeDocument::getTitle).toList();
    }

    private void stubFind(KnowledgeDocument d) {
        when(repository.findById(d.getId())).thenReturn(Optional.of(d));
    }

    // ----- validação

    @Test
    @DisplayName("Status 'draft' e 'archived' são aceitos; status desconhecido continua 400")
    void statusValidation() {
        when(repository.save(any(KnowledgeDocument.class))).thenAnswer(i -> i.getArgument(0));
        assertEquals("draft", service.saveOrUpdateDocument(KnowledgeDocument.builder().title("t").authorId("u").status("draft").build()).getStatus());
        assertEquals("archived", service.saveOrUpdateDocument(KnowledgeDocument.builder().title("t").authorId("u").status("archived").build()).getStatus());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.saveOrUpdateDocument(KnowledgeDocument.builder().title("t").authorId("u").status("secreto").build()));
        assertEquals(HttpStatus.BAD_REQUEST.value(), ex.getStatusCode().value());
    }

    // ----- lista geral e buscas em lote

    @Test
    @DisplayName("Lista geral consulta só publicado e indexado (rascunho, arquivado e lixeira ficam de fora)")
    void generalListOnlyListedStatuses() {
        when(repository.findByStatusIn(eq(KnowledgeService.LISTED_STATUSES), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.listDocuments(null, null, null, PageRequest.of(0, 20), "autor", false);
        service.listDocuments(null, null, null, PageRequest.of(0, 20), "admin", true);

        assertEquals(List.of("published", "indexed"), KnowledgeService.LISTED_STATUSES);
        verify(repository, times(2)).findByStatusIn(eq(KnowledgeService.LISTED_STATUSES), any(Pageable.class));
        verify(repository, never()).findByStatus(any(), any());
    }

    @Test
    @DisplayName("Busca textual (inclusive o fallback por palavras-chave) usa só os estados listáveis")
    void textSearchOnlyListedStatuses() {
        KnowledgeDocument pub = doc("Guia de deploy", "a", "published", null, 1);
        when(repository.searchByStatuses(eq("guia deploy"), eq(KnowledgeService.LISTED_STATUSES), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(repository.findByStatusIn(eq(KnowledgeService.LISTED_STATUSES), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(pub)));

        Page<KnowledgeDocument> result = service.listDocuments("guia deploy", null, null, PageRequest.of(0, 20), "autor", false);

        assertEquals(List.of("Guia de deploy"), titles(result));
        verify(repository, never()).findByStatus(any(), any());
    }

    @Test
    @DisplayName("Busca semântica ignora rascunho, arquivado e lixeira")
    void semanticSearchOnlyListed() {
        KnowledgeDocument pub = doc("pub", "a", "published", null, 1);
        pub.setEmbedding(new float[]{1f, 0f});
        when(repository.findByStatusIn(eq(KnowledgeService.LISTED_STATUSES), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(pub)));

        Page<KnowledgeDocument> result = service.semanticSearch(new float[]{1f, 0f}, PageRequest.of(0, 10));

        assertEquals(List.of("pub"), titles(result));
        verify(repository, never()).findByStatus(any(), any());
    }

    // ----- rascunho

    @Test
    @DisplayName("Filtro 'draft': o autor vê só os próprios; outra pessoa e anônimo não veem nada; admin vê todos")
    void draftListVisibility() {
        KnowledgeDocument mine = doc("meu", "ana", "draft", "SQ1", 1);
        KnowledgeDocument theirs = doc("dele", "beto", "draft", "SQ1", 2);
        when(repository.findByStatus(eq("draft"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(mine, theirs)));

        assertEquals(List.of("meu"), titles(service.listDocuments(null, null, "draft", PageRequest.of(0, 20), "ana", false)));
        assertEquals(List.of("dele"), titles(service.listDocuments(null, null, "draft", PageRequest.of(0, 20), "beto", false)));
        assertEquals(List.of(), titles(service.listDocuments(null, null, "draft", PageRequest.of(0, 20), "carla", false)));
        assertEquals(List.of(), titles(service.listDocuments(null, null, "draft", PageRequest.of(0, 20), null, false)));
        assertEquals(List.of("meu", "dele"), titles(service.listDocuments(null, null, "draft", PageRequest.of(0, 20), "admin", true)));
        // a liderança da squad NÃO enxerga rascunho alheio (só autor e admin)
        assertEquals(List.of(), titles(service.listDocuments(null, null, "draft", PageRequest.of(0, 20), "lider", false)));
    }

    @Test
    @DisplayName("Busca dentro de 'Meus rascunhos' filtra pelo termo e pagina")
    void draftListWithQueryAndPaging() {
        KnowledgeDocument a = doc("Guia de deploy", "ana", "draft", null, 1);
        KnowledgeDocument b = doc("Outro assunto", "ana", "draft", null, 2);
        when(repository.findByStatus(eq("draft"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(a, b)));

        Page<KnowledgeDocument> found = service.listDocuments("deploy", null, "draft", PageRequest.of(0, 20), "ana", false);
        assertEquals(List.of("Guia de deploy"), titles(found));

        Page<KnowledgeDocument> second = service.listDocuments(null, null, "draft", PageRequest.of(1, 1), "ana", false);
        assertEquals(2, second.getTotalElements());
        assertEquals(List.of("Outro assunto"), titles(second));
    }

    @Test
    @DisplayName("Leitura por id de rascunho alheio é 'não encontrado' (404); autor e admin leem")
    void getDraftById() {
        KnowledgeDocument d = doc("rascunho", "ana", "draft", "SQ1", 1);
        stubFind(d);

        assertSame(d, service.getReadableDocument(d.getId(), "ana", false));
        assertSame(d, service.getReadableDocument(d.getId(), "admin", true));
        assertThrows(IllegalArgumentException.class, () -> service.getReadableDocument(d.getId(), "beto", false));
        assertThrows(IllegalArgumentException.class, () -> service.getReadableDocument(d.getId(), null, false));
        assertThrows(IllegalArgumentException.class, () -> service.getReadableDocument(d.getId(), "lider", false));
    }

    @Test
    @DisplayName("Histórico, visualização, edição, exclusão e restauração de versão de rascunho alheio também dão 404")
    void otherDraftHiddenInEveryPath() {
        KnowledgeDocument d = doc("rascunho", "ana", "draft", "SQ1", 1);
        stubFind(d);
        KnowledgeDocument body = KnowledgeDocument.builder().title("x").build();

        assertThrows(IllegalArgumentException.class, () -> service.listVersions(d.getId(), "beto", false));
        assertThrows(IllegalArgumentException.class, () -> service.getVersion(d.getId(), 1, "beto", false));
        assertThrows(IllegalArgumentException.class, () -> service.incrementViews(d.getId(), "beto", false));
        assertThrows(IllegalArgumentException.class, () -> service.updateDocument(d.getId(), body, "beto", false));
        assertThrows(IllegalArgumentException.class, () -> service.deleteDocument(d.getId(), "beto", false));
        assertThrows(IllegalArgumentException.class, () -> service.restoreVersion(d.getId(), 1, "beto", false));
        // nem a liderança da squad consegue editar o rascunho de outra pessoa
        assertThrows(IllegalArgumentException.class, () -> service.updateDocument(d.getId(), body, "lider", false));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("O autor publica o próprio rascunho (draft -> published)")
    void authorPublishesDraft() {
        KnowledgeDocument d = doc("rascunho", "ana", "draft", "SQ1", 1);
        stubFind(d);
        when(repository.save(any(KnowledgeDocument.class))).thenAnswer(i -> i.getArgument(0));

        KnowledgeDocument saved = service.updateDocument(d.getId(),
                KnowledgeDocument.builder().title("rascunho").content(d.getContent()).status("published").build(), "ana", false);

        assertEquals("published", saved.getStatus());
    }

    // ----- arquivado

    @Test
    @DisplayName("Filtro 'archived': autor, liderança e admin veem; colega sem permissão não")
    void archivedListVisibility() {
        KnowledgeDocument arq = doc("antigo", "ana", "archived", "SQ1", 1);
        when(repository.findByStatus(eq("archived"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(arq)));
        when(squadAccessService.isSquadLeadership("SQ1", "lider", null)).thenReturn(true);
        when(squadAccessService.isSquadLeadership("SQ1", "colega", null)).thenReturn(false);

        assertEquals(List.of("antigo"), titles(service.listDocuments(null, null, "archived", PageRequest.of(0, 20), "ana", false)));
        assertEquals(List.of("antigo"), titles(service.listDocuments(null, null, "archived", PageRequest.of(0, 20), "lider", false)));
        assertEquals(List.of("antigo"), titles(service.listDocuments(null, null, "archived", PageRequest.of(0, 20), "admin", true)));
        assertEquals(List.of(), titles(service.listDocuments(null, null, "archived", PageRequest.of(0, 20), "colega", false)));
        assertEquals(List.of(), titles(service.listDocuments(null, null, "archived", PageRequest.of(0, 20), null, false)));
    }

    @Test
    @DisplayName("Arquivado segue legível por id para qualquer pessoa autenticada")
    void archivedReadableById() {
        KnowledgeDocument d = doc("antigo", "ana", "archived", "SQ1", 1);
        stubFind(d);
        when(repository.save(any(KnowledgeDocument.class))).thenAnswer(i -> i.getArgument(0));

        assertSame(d, service.getReadableDocument(d.getId(), "colega", false));
        assertSame(d, service.incrementViews(d.getId(), "colega", false));
    }

    @Test
    @DisplayName("Arquivar e restaurar: só quem edita; restaurar volta a 'published'")
    void archiveAndRestore() {
        KnowledgeDocument d = doc("guia", "ana", "published", "SQ1", 1);
        stubFind(d);
        when(repository.save(any(KnowledgeDocument.class))).thenAnswer(i -> i.getArgument(0));
        when(squadAccessService.isSquadLeadership("SQ1", "colega", null)).thenReturn(false);
        when(squadAccessService.isSquadLeadership("SQ1", "lider", null)).thenReturn(true);
        KnowledgeDocument archive = KnowledgeDocument.builder().title("guia").content(d.getContent()).status("archived").build();
        KnowledgeDocument restore = KnowledgeDocument.builder().title("guia").content(d.getContent()).status("published").build();

        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> service.updateDocument(d.getId(), archive, "colega", false));
        assertEquals(HttpStatus.FORBIDDEN.value(), denied.getStatusCode().value());

        assertEquals("archived", service.updateDocument(d.getId(), archive, "ana", false).getStatus());
        assertEquals("published", service.updateDocument(d.getId(), restore, "lider", false).getStatus());
    }

    // ----- API pública / MCP

    @Test
    @DisplayName("API pública e MCP só servem o que não é rascunho, arquivado nem lixeira")
    void publiclyReadable() {
        assertTrue(KnowledgeService.isPubliclyReadable(doc("a", "x", "published", null, 1)));
        assertTrue(KnowledgeService.isPubliclyReadable(doc("a", "x", "indexed", null, 1)));
        assertFalse(KnowledgeService.isPubliclyReadable(doc("a", "x", "draft", null, 1)));
        assertFalse(KnowledgeService.isPubliclyReadable(doc("a", "x", "archived", null, 1)));
        assertFalse(KnowledgeService.isPubliclyReadable(doc("a", "x", "deleted", null, 1)));
    }

    @Test
    @DisplayName("Visão pública da listagem (API pública/MCP, sem usuário) nunca inclui rascunho ou arquivado")
    void publicViewHasNoUser() {
        when(repository.findByStatusIn(eq(KnowledgeService.LISTED_STATUSES), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        service.listDocuments(null, null, null, PageRequest.of(0, 20));
        verify(repository).findByStatusIn(eq(KnowledgeService.LISTED_STATUSES), any(Pageable.class));

        // mesmo pedindo status=draft sem usuário, nada sai
        KnowledgeDocument d = doc("rasc", "ana", "draft", null, 1);
        when(repository.findByStatus(eq("draft"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(d)));
        assertEquals(List.of(), titles(service.listDocuments(null, null, "draft", PageRequest.of(0, 20))));
    }

    @Test
    @DisplayName("Lixeira e filtro de publicados mantêm o comportamento; status desconhecido devolve lista vazia")
    void trashAndUnknownFilter() {
        when(repository.findByStatus(eq("deleted"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(doc("lixo", "a", "deleted", null, 1))));
        assertEquals(List.of("lixo"), titles(service.listDocuments(null, null, "deleted", PageRequest.of(0, 20), "qualquer", false)));

        assertEquals(0, service.listDocuments(null, null, "qualquercoisa", PageRequest.of(0, 20), "u", false).getTotalElements());
    }
}
