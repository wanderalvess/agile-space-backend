package com.agilespace.backend.service;

import com.agilespace.backend.domain.Prompt;
import com.agilespace.backend.domain.PromptComment;
import com.agilespace.backend.domain.PromptCollection;
import com.agilespace.backend.repository.PromptCollectionRepository;
import com.agilespace.backend.repository.PromptCommentRepository;
import com.agilespace.backend.repository.PromptRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PromptService - Biblioteca de Prompts de IA, Compartilhamento e Métricas de Uso")
class PromptServiceTest {

    @Mock private PromptRepository promptRepository;
    @Mock private PromptCommentRepository commentRepository;
    @Mock private PromptCollectionRepository promptCollectionRepository;

    private final PromptCaller owner = new PromptCaller("user-123", "MEMBER");
    private final PromptCaller stranger = new PromptCaller("intruso", "MEMBER");
    private final PromptCaller admin = new PromptCaller("adm", "ADMIN");

    @InjectMocks
    private PromptService service;

    @Nested
    @DisplayName("Listagem e Busca de Prompts")
    class SearchTests {

        @Test
        @DisplayName("Deve buscar prompts públicos por termo de pesquisa")
        void shouldSearchPublicPromptsByQuery() {
            Page<Prompt> page = new PageImpl<>(Collections.singletonList(new Prompt()));
            when(promptRepository.searchPublic(eq("scrum"), eq("public"), any(Pageable.class))).thenReturn(page);

            Page<Prompt> result = service.listPrompts("scrum", null, owner, PageRequest.of(0, 10));

            assertEquals(1, result.getTotalElements());
            verify(promptRepository).searchPublic(eq("scrum"), eq("public"), any(Pageable.class));
        }

        @Test
        @DisplayName("Deve listar prompts por autor específico")
        void shouldListPromptsByAuthor() {
            Page<Prompt> page = new PageImpl<>(Collections.singletonList(new Prompt()));
            when(promptRepository.findByAuthorId(eq("user-123"), any(Pageable.class))).thenReturn(page);

            Page<Prompt> result = service.listPrompts(null, "user-123", owner, PageRequest.of(0, 10));

            assertEquals(1, result.getTotalElements());
            verify(promptRepository).findByAuthorId(eq("user-123"), any(Pageable.class));
        }
    }

    @Nested
    @DisplayName("Criação, Métricas e Comentários")
    class MutationTests {

        @Test
        @DisplayName("Deve inicializar contadores de uso e fork zerados ao criar novo prompt")
        void shouldResetCountersOnCreate() {
            Prompt prompt = Prompt.builder().title("Prompt de Refatoração").useCount(99).forkCount(12).build();
            when(promptRepository.save(any(Prompt.class))).thenAnswer(i -> i.getArgument(0));

            Prompt saved = service.createPrompt(prompt);

            assertEquals(0, saved.getUseCount(), "Contador de uso deve iniciar em zero");
            assertEquals(0, saved.getForkCount(), "Contador de fork deve iniciar em zero");
        }

        @Test
        @DisplayName("Deve incrementar contagem de utilização de forma atômica, sem salvar a entidade")
        void shouldIncrementUseCount() {
            UUID promptId = UUID.randomUUID();
            Prompt prompt = Prompt.builder().id(promptId).visibility("public").authorId("x").useCount(10).build();
            when(promptRepository.findById(promptId)).thenReturn(Optional.of(prompt));

            service.incrementUseCount(promptId, owner);

            verify(promptRepository).incrementUseCount(promptId);
            verify(promptRepository, never()).save(any());
        }

        @Test
        @DisplayName("Não deve contar uso de item privado de outra pessoa")
        void shouldNotCountUseOfForeignPrivate() {
            UUID promptId = UUID.randomUUID();
            Prompt prompt = Prompt.builder().id(promptId).visibility("private").authorId("outro").build();
            when(promptRepository.findById(promptId)).thenReturn(Optional.of(prompt));

            assertThrows(IllegalArgumentException.class, () -> service.incrementUseCount(promptId, owner));
            verify(promptRepository, never()).incrementUseCount(any());
        }

        @Test
        @DisplayName("Deve excluir prompt tirando-o das coleções e removendo os comentários")
        void shouldDeletePromptFromCollectionsAndComments() {
            UUID promptId = UUID.randomUUID();
            Prompt prompt = Prompt.builder().id(promptId).visibility("public").authorId("user-123").build();
            PromptCollection collection = PromptCollection.builder().ownerId("c").build();
            collection.getItems().add(prompt);
            when(promptRepository.findById(promptId)).thenReturn(Optional.of(prompt));
            when(promptCollectionRepository.findDistinctByItemsId(promptId)).thenReturn(List.of(collection));
            when(commentRepository.findByPromptId(promptId)).thenReturn(Collections.singletonList(new PromptComment()));

            service.deletePrompt(promptId, owner);

            assertTrue(collection.getItems().isEmpty());
            verify(promptCollectionRepository).save(collection);
            verify(commentRepository).deleteAll(any());
            verify(promptRepository).delete(prompt);
        }

        @Test
        @DisplayName("Não deve permitir que outra pessoa exclua ou edite o prompt; ADMIN pode")
        void shouldRequireOwnerOrAdmin() {
            UUID promptId = UUID.randomUUID();
            Prompt prompt = Prompt.builder().id(promptId).visibility("public").authorId("user-123").title("t").build();
            when(promptRepository.findById(promptId)).thenReturn(Optional.of(prompt));

            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> service.deletePrompt(promptId, stranger));
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> service.updatePrompt(promptId, Prompt.builder().title("x").build(), stranger));
            verify(promptRepository, never()).delete(any());

            service.deletePrompt(promptId, admin);
            verify(promptRepository).delete(prompt);
        }

        @Test
        @DisplayName("Criação usa a identidade do chamador, ignora id/autoria do corpo e normaliza visibilidade e tags")
        void shouldForceAuthorOnCreate() {
            Prompt prompt = Prompt.builder().id(UUID.randomUUID()).title("  Novo  ").authorId("vitima")
                    .visibility("role").tags(new java.util.HashSet<>(List.of(" #IA ", "", "qa"))).build();
            when(promptRepository.save(any(Prompt.class))).thenAnswer(i -> i.getArgument(0));

            Prompt saved = service.createPrompt(prompt, owner);

            assertNull(saved.getId());
            assertEquals("user-123", saved.getAuthorId());
            assertEquals("private", saved.getVisibility());
            assertEquals("Novo", saved.getTitle());
            assertEquals(java.util.Set.of("ia", "qa"), saved.getTags());
        }

        @Test
        @DisplayName("Listagem de autor alheio devolve só os públicos")
        void shouldListOnlyPublicOfOtherAuthor() {
            when(promptRepository.findByAuthorIdAndVisibility(eq("user-123"), eq("public"), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            service.listPrompts(null, "user-123", stranger, PageRequest.of(0, 10));

            verify(promptRepository).findByAuthorIdAndVisibility(eq("user-123"), eq("public"), any(Pageable.class));
            verify(promptRepository, never()).findByAuthorId(any(), any());
        }

        @Test
        @DisplayName("Paginação sem ordenação ganha updatedAt desc e tamanho limitado")
        void shouldApplyDefaultSortAndCap() {
            when(promptRepository.findByVisibility(eq("public"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

            service.listPrompts(null, null, owner, PageRequest.of(0, 5000));

            org.mockito.ArgumentCaptor<Pageable> captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
            verify(promptRepository).findByVisibility(eq("public"), captor.capture());
            assertEquals(200, captor.getValue().getPageSize());
            assertNotNull(captor.getValue().getSort().getOrderFor("updatedAt"));
        }

        @Test
        @DisplayName("Coleção só mostra a quem chama os itens que ele enxerga e não altera a entidade gerenciada")
        void shouldHidePrivateItemsInCollectionView() {
            UUID collectionId = UUID.randomUUID();
            Prompt pub = Prompt.builder().id(UUID.randomUUID()).visibility("public").authorId("a").build();
            Prompt priv = Prompt.builder().id(UUID.randomUUID()).visibility("private").authorId("a").build();
            PromptCollection collection = PromptCollection.builder().id(collectionId).visibility("public").ownerId("a").build();
            collection.getItems().add(pub);
            collection.getItems().add(priv);
            when(promptCollectionRepository.findById(collectionId)).thenReturn(Optional.of(collection));

            PromptCollection view = service.getCollectionFor(collectionId, stranger);

            assertEquals(List.of(pub), view.getItems());
            assertEquals(2, collection.getItems().size());
        }

        @Test
        @DisplayName("Coleção privada de outra pessoa responde como inexistente; não dono não exclui coleção pública")
        void shouldProtectCollections() {
            UUID privateId = UUID.randomUUID();
            UUID publicId = UUID.randomUUID();
            when(promptCollectionRepository.findById(privateId)).thenReturn(Optional.of(
                    PromptCollection.builder().id(privateId).visibility("private").ownerId("a").build()));
            when(promptCollectionRepository.findById(publicId)).thenReturn(Optional.of(
                    PromptCollection.builder().id(publicId).visibility("public").ownerId("a").build()));

            assertThrows(IllegalArgumentException.class, () -> service.getCollectionFor(privateId, stranger));
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> service.deleteCollection(publicId, stranger));
            verify(promptCollectionRepository, never()).delete(any());
        }

        @Test
        @DisplayName("Deve adicionar comentário ao prompt com vínculo bidirecional")
        void shouldAddCommentToPrompt() {
            UUID promptId = UUID.randomUUID();
            Prompt prompt = Prompt.builder().id(promptId).visibility("public").build();
            when(promptRepository.findById(promptId)).thenReturn(Optional.of(prompt));

            PromptComment comment = new PromptComment();
            when(commentRepository.save(any(PromptComment.class))).thenAnswer(i -> i.getArgument(0));

            comment.setContent("  ótimo  ");
            PromptComment saved = service.addComment(promptId, comment, owner);

            assertEquals("user-123", saved.getAuthorId());
            assertEquals("ótimo", saved.getContent());

            assertNotNull(saved.getPrompt());
            assertEquals(promptId, saved.getPrompt().getId());
        }

        @Test
        @DisplayName("Deve salvar nova skill quando não existir duplicata anterior")
        void shouldCreateNewSkillWhenNotExists() {
            Prompt newSkill = Prompt.builder()
                    .title("test-skill")
                    .content("---\nname: test-skill\n---\nbody")
                    .authorId("user-1")
                    .type("skill")
                    .build();

            when(promptRepository.findFirstByAuthorIdAndTitleAndType("user-1", "test-skill", "skill"))
                    .thenReturn(Optional.empty());
            when(promptRepository.save(any(Prompt.class))).thenAnswer(i -> i.getArgument(0));

            Prompt result = service.saveOrUpdateSkill(newSkill);

            assertNotNull(result);
            assertEquals("test-skill", result.getTitle());
            verify(promptRepository).save(newSkill);
        }

        @Test
        @DisplayName("Deve atualizar skill existente com mesmo autor e título (upsert)")
        void shouldUpdateExistingSkill() {
            Prompt existing = Prompt.builder()
                    .id(UUID.randomUUID())
                    .title("test-skill")
                    .content("old content")
                    .description("old desc")
                    .authorId("user-1")
                    .type("skill")
                    .build();

            Prompt incoming = Prompt.builder()
                    .title("test-skill")
                    .content("new content")
                    .description("new desc")
                    .authorId("user-1")
                    .type("skill")
                    .build();

            when(promptRepository.findFirstByAuthorIdAndTitleAndType("user-1", "test-skill", "skill"))
                    .thenReturn(Optional.of(existing));
            when(promptRepository.save(any(Prompt.class))).thenAnswer(i -> i.getArgument(0));

            Prompt result = service.saveOrUpdateSkill(incoming);

            assertEquals("new content", result.getContent());
            assertEquals("new desc", result.getDescription());
            assertEquals(existing.getId(), result.getId());
        }

        @Test
        @DisplayName("Deve criar lista de prompts/skills em lote")
        void shouldCreatePromptsBatch() {
            Prompt s1 = Prompt.builder().title("s1").type("skill").authorId("u1").build();
            Prompt s2 = Prompt.builder().title("s2").type("prompt").authorId("u1").build();

            when(promptRepository.findFirstByAuthorIdAndTitleAndType("u1", "s1", "skill"))
                    .thenReturn(Optional.empty());
            when(promptRepository.save(any(Prompt.class))).thenAnswer(i -> i.getArgument(0));

            List<Prompt> result = service.createPromptsBatch(List.of(s1, s2));

            assertEquals(2, result.size());
            verify(promptRepository, times(2)).save(any(Prompt.class));
        }
    }
}
