package com.agilespace.backend.service;

import com.agilespace.backend.domain.KnowledgeDocument;
import com.agilespace.backend.domain.KnowledgeDocumentVersion;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.KnowledgeVersionDto;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Base de Conhecimento - edição por autor/liderança/ADMIN e histórico de versões")
class KnowledgeAccessAndVersionsTest {

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

    private static HttpStatus statusOf(Supplier<?> call) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, call::get);
        return HttpStatus.valueOf(ex.getStatusCode().value());
    }

    private KnowledgeDocument doc(UUID id, String author, String squad) {
        return KnowledgeDocument.builder().id(id).title("Guia").content("v1").authorId(author).squadId(squad)
                .status("published").tags(new java.util.HashSet<>(Set.of("a"))).build();
    }

    // ----- permissão

    @Test
    @DisplayName("Autor edita; ADMIN edita; outra pessoa sem liderança recebe 403 com a mensagem em português")
    void editPermission() {
        UUID id = UUID.randomUUID();
        KnowledgeDocument d = doc(id, "autor", "SQ1");
        when(repository.findById(id)).thenReturn(Optional.of(d));
        when(repository.save(any(KnowledgeDocument.class))).thenAnswer(i -> i.getArgument(0));
        when(squadAccessService.isSquadLeadership("SQ1", "colega", null)).thenReturn(false);

        KnowledgeDocument change = KnowledgeDocument.builder().title("Guia").content("v2").build();
        assertDoesNotThrow(() -> service.updateDocument(id, change, "autor", false));
        assertDoesNotThrow(() -> service.updateDocument(id, KnowledgeDocument.builder().title("Guia").content("v3").build(), "admin", true));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.updateDocument(id, KnowledgeDocument.builder().title("Guia").content("hack").build(), "colega", false));
        assertEquals(HttpStatus.FORBIDDEN.value(), ex.getStatusCode().value());
        assertEquals("Só o autor ou a liderança editam este documento.", ex.getReason());
    }

    @Test
    @DisplayName("Liderança da squad do documento edita, apaga e restaura")
    void leadershipCanEdit() {
        UUID id = UUID.randomUUID();
        KnowledgeDocument d = doc(id, "autor", "SQ1");
        when(repository.findById(id)).thenReturn(Optional.of(d));
        when(repository.save(any(KnowledgeDocument.class))).thenAnswer(i -> i.getArgument(0));
        when(squadAccessService.isSquadLeadership("SQ1", "lider", null)).thenReturn(true);

        assertTrue(service.canEdit(d, "lider", false));
        assertEquals("deleted", service.deleteDocument(id, "lider", false).getStatus());
        KnowledgeDocument restored = service.updateDocument(id, KnowledgeDocument.builder().title("Guia").content("v1").status("published").build(), "lider", false);
        assertEquals("published", restored.getStatus());
    }

    @Test
    @DisplayName("Documento sem squad: liderança não vale; só autor e ADMIN")
    void noSquadMeansAuthorAndAdminOnly() {
        KnowledgeDocument d = doc(UUID.randomUUID(), "autor", null);
        assertTrue(service.canEdit(d, "autor", false));
        assertTrue(service.canEdit(d, "x", true));
        assertFalse(service.canEdit(d, "lider", false));
        verifyNoInteractions(squadAccessService);
    }

    @Test
    @DisplayName("Documento sem autor registrado: só ADMIN")
    void noAuthorOnlyAdmin() {
        KnowledgeDocument d = doc(UUID.randomUUID(), "", "SQ1");
        assertFalse(service.canEdit(d, "", false));
        assertFalse(service.canEdit(d, "qualquer", false));
        assertTrue(service.canEdit(d, "admin", true));
    }

    @Test
    @DisplayName("Apagar e restaurar da lixeira por quem não pode editar é 403")
    void deleteAndRestoreForbidden() {
        UUID id = UUID.randomUUID();
        KnowledgeDocument d = doc(id, "autor", "SQ1");
        when(repository.findById(id)).thenReturn(Optional.of(d));

        assertEquals(HttpStatus.FORBIDDEN, statusOf(() -> service.deleteDocument(id, "colega", false)));
        d.setStatus("deleted");
        assertEquals(HttpStatus.FORBIDDEN, statusOf(() -> service.updateDocument(id,
                KnowledgeDocument.builder().title("Guia").status("published").build(), "colega", false)));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Reimportar do TDN sobre documento alheio sem permissão é 403")
    void tdnUpsertRequiresPermission() {
        KnowledgeDocument existing = doc(UUID.randomUUID(), "autor", "SQ1");
        existing.setTdnId("123");
        when(repository.findByTdnId("123")).thenReturn(Optional.of(existing));

        KnowledgeDocument incoming = KnowledgeDocument.builder().title("Novo").authorId("colega").tdnId("123").build();
        assertEquals(HttpStatus.FORBIDDEN, statusOf(() -> service.saveOrUpdateDocument(incoming, false)));
    }

    @Test
    @DisplayName("markEditable consulta a liderança uma vez por squad, não por documento")
    void markEditableCachesLeadership() {
        when(squadAccessService.isSquadLeadership("SQ1", "u", null)).thenReturn(true);
        List<KnowledgeDocument> docs = List.of(doc(UUID.randomUUID(), "a", "SQ1"), doc(UUID.randomUUID(), "b", "SQ1"), doc(UUID.randomUUID(), "u", null));

        service.markEditable(docs, "u", false);

        assertTrue(docs.stream().allMatch(KnowledgeDocument::getCanEdit));
        verify(squadAccessService, times(1)).isSquadLeadership(eq("SQ1"), eq("u"), any());
    }

    // ----- criação

    @Test
    @DisplayName("Documento novo ganha a squad do autor (do servidor) e a versão 1")
    void createSetsSquadAndFirstVersion() {
        when(userRepository.findById("autor")).thenReturn(Optional.of(User.builder().id("autor").squadId("SQ9").build()));
        when(repository.save(any(KnowledgeDocument.class))).thenAnswer(i -> {
            KnowledgeDocument saved = i.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        KnowledgeDocument saved = service.saveOrUpdateDocument(
                KnowledgeDocument.builder().title("Novo").content("x").authorId("autor").squadId("FORJADA").build());

        assertEquals("SQ9", saved.getSquadId());
        ArgumentCaptor<KnowledgeDocumentVersion> v = ArgumentCaptor.forClass(KnowledgeDocumentVersion.class);
        verify(versionRepository).save(v.capture());
        assertEquals(1, v.getValue().getVersionNumber());
        assertEquals("autor", v.getValue().getChangedBy());
    }

    // ----- versões

    @Test
    @DisplayName("Editar o conteúdo cria versão (com a inicial para documento antigo) e descarta o vetor antigo")
    void updateCreatesVersions() {
        UUID id = UUID.randomUUID();
        KnowledgeDocument d = doc(id, "autor", null);
        d.setEmbedding(new float[]{1f});
        when(repository.findById(id)).thenReturn(Optional.of(d));
        when(repository.save(any(KnowledgeDocument.class))).thenAnswer(i -> i.getArgument(0));
        when(versionRepository.countByDocumentId(id)).thenReturn(0L);
        when(versionRepository.findTopByDocumentIdOrderByVersionNumberDesc(id)).thenReturn(Optional.empty());
        when(versionRepository.findByDocumentIdOrderByVersionNumberDesc(id)).thenReturn(new ArrayList<>());

        KnowledgeDocument saved = service.updateDocument(id, KnowledgeDocument.builder().title("Guia").content("v2").build(), "autor", false);

        assertNull(saved.getEmbedding());
        ArgumentCaptor<KnowledgeDocumentVersion> v = ArgumentCaptor.forClass(KnowledgeDocumentVersion.class);
        verify(versionRepository, times(2)).save(v.capture());
        assertEquals("v1", v.getAllValues().get(0).getContent());
        assertEquals("v2", v.getAllValues().get(1).getContent());
    }

    @Test
    @DisplayName("Salvar sem mudar título, conteúdo ou tags não cria versão e mantém o vetor")
    void unchangedDoesNotVersion() {
        UUID id = UUID.randomUUID();
        KnowledgeDocument d = doc(id, "autor", null);
        d.setEmbedding(new float[]{1f});
        when(repository.findById(id)).thenReturn(Optional.of(d));
        when(repository.save(any(KnowledgeDocument.class))).thenAnswer(i -> i.getArgument(0));

        KnowledgeDocument saved = service.updateDocument(id,
                KnowledgeDocument.builder().title("Guia").content("v1").tags(new java.util.HashSet<>(Set.of("a"))).category("Nova").build(), "autor", false);

        assertNotNull(saved.getEmbedding());
        verify(versionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Mais de 50 versões: as mais antigas são descartadas")
    void prunesOldVersions() {
        UUID id = UUID.randomUUID();
        KnowledgeDocument d = doc(id, "autor", null);
        when(repository.findById(id)).thenReturn(Optional.of(d));
        when(repository.save(any(KnowledgeDocument.class))).thenAnswer(i -> i.getArgument(0));
        when(versionRepository.countByDocumentId(id)).thenReturn(51L);
        when(versionRepository.findTopByDocumentIdOrderByVersionNumberDesc(id)).thenReturn(Optional.of(KnowledgeDocumentVersion.builder().versionNumber(51).build()));
        List<KnowledgeDocumentVersion> all = new ArrayList<>();
        for (int n = 52; n >= 1; n--) all.add(KnowledgeDocumentVersion.builder().documentId(id).versionNumber(n).build());
        when(versionRepository.findByDocumentIdOrderByVersionNumberDesc(id)).thenReturn(all);

        service.updateDocument(id, KnowledgeDocument.builder().title("Guia").content("novo").build(), "autor", false);

        ArgumentCaptor<Iterable<KnowledgeDocumentVersion>> removed = ArgumentCaptor.forClass(Iterable.class);
        verify(versionRepository).deleteAll(removed.capture());
        List<Integer> numbers = new ArrayList<>();
        removed.getValue().forEach(x -> numbers.add(x.getVersionNumber()));
        assertEquals(List.of(2, 1), numbers);
    }

    @Test
    @DisplayName("Restaurar uma versão cria uma nova versão, zera o vetor e exige permissão")
    void restoreCreatesNewVersion() {
        UUID id = UUID.randomUUID();
        KnowledgeDocument d = doc(id, "autor", null);
        d.setContent("atual");
        d.setEmbedding(new float[]{1f});
        when(repository.findById(id)).thenReturn(Optional.of(d));
        when(repository.save(any(KnowledgeDocument.class))).thenAnswer(i -> i.getArgument(0));
        when(versionRepository.countByDocumentId(id)).thenReturn(3L);
        when(versionRepository.findByDocumentIdAndVersionNumber(id, 1)).thenReturn(Optional.of(KnowledgeDocumentVersion.builder()
                .documentId(id).versionNumber(1).title("Guia antigo").content("antigo").tags("[\"x\",\"y\"]").build()));
        when(versionRepository.findTopByDocumentIdOrderByVersionNumberDesc(id)).thenReturn(Optional.of(KnowledgeDocumentVersion.builder().versionNumber(3).build()));
        when(versionRepository.findByDocumentIdOrderByVersionNumberDesc(id)).thenReturn(new ArrayList<>());

        assertEquals(HttpStatus.FORBIDDEN, statusOf(() -> service.restoreVersion(id, 1, "colega", false)));

        KnowledgeDocument restored = service.restoreVersion(id, 1, "autor", false);

        assertEquals("antigo", restored.getContent());
        assertEquals("Guia antigo", restored.getTitle());
        assertEquals(Set.of("x", "y"), restored.getTags());
        assertNull(restored.getEmbedding());
        ArgumentCaptor<KnowledgeDocumentVersion> v = ArgumentCaptor.forClass(KnowledgeDocumentVersion.class);
        verify(versionRepository).save(v.capture());
        assertEquals(4, v.getValue().getVersionNumber());
        assertEquals("antigo", v.getValue().getContent());
    }

    @Test
    @DisplayName("Restaurar versão inexistente é not found")
    void restoreMissing() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(doc(id, "autor", null)));
        when(versionRepository.countByDocumentId(id)).thenReturn(1L);
        when(versionRepository.findByDocumentIdAndVersionNumber(eq(id), anyInt())).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.restoreVersion(id, 99, "autor", false));
    }

    @Test
    @DisplayName("Lista de versões traz o nome de quem alterou e marca a atual, sem o conteúdo")
    void listVersions() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(doc(id, "autor", null)));
        when(versionRepository.countByDocumentId(id)).thenReturn(2L);
        when(versionRepository.findByDocumentIdOrderByVersionNumberDesc(id)).thenReturn(List.of(
                KnowledgeDocumentVersion.builder().versionNumber(2).title("B").changedBy("u1").content("segredo").build(),
                KnowledgeDocumentVersion.builder().versionNumber(1).title("A").changedBy("u1").build()));
        when(userRepository.findById("u1")).thenReturn(Optional.of(User.builder().id("u1").name("Ana").build()));

        List<KnowledgeVersionDto> list = service.listVersions(id);

        assertEquals(2, list.size());
        assertTrue(list.get(0).current());
        assertFalse(list.get(1).current());
        assertEquals("Ana", list.get(0).changedByName());
        assertNull(list.get(0).content());
    }
}
