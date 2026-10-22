package com.agilespace.backend.service;

import com.agilespace.backend.domain.Prompt;
import com.agilespace.backend.domain.PromptComment;
import com.agilespace.backend.domain.PromptFavorite;
import com.agilespace.backend.domain.PromptUsageEvent;
import com.agilespace.backend.domain.PromptVersion;
import com.agilespace.backend.dto.PromptDto;
import com.agilespace.backend.dto.PromptFacetsDto;
import com.agilespace.backend.dto.PromptPageDto;
import com.agilespace.backend.dto.PromptUsageWeekDto;
import com.agilespace.backend.dto.PromptVersionDto;
import com.agilespace.backend.repository.PromptCollectionRepository;
import com.agilespace.backend.repository.PromptCommentRepository;
import com.agilespace.backend.repository.PromptFavoriteRepository;
import com.agilespace.backend.repository.PromptFilter;
import com.agilespace.backend.repository.PromptQueryRepository;
import com.agilespace.backend.repository.PromptRepository;
import com.agilespace.backend.repository.PromptUsageEventRepository;
import com.agilespace.backend.repository.PromptVersionRepository;
import com.agilespace.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PromptService - busca no servidor, favoritos, versões e uso por semana")
class PromptHubV2ServiceTest {

    @Mock private PromptRepository promptRepository;
    @Mock private PromptCommentRepository commentRepository;
    @Mock private PromptCollectionRepository promptCollectionRepository;
    @Mock private UserRepository userRepository;
    @Mock private PromptFavoriteRepository favoriteRepository;
    @Mock private PromptVersionRepository versionRepository;
    @Mock private PromptUsageEventRepository usageRepository;
    @Mock private PromptQueryRepository queryRepository;

    @InjectMocks private PromptService service;

    private final PromptCaller owner = new PromptCaller("dono", "MEMBER");
    private final PromptCaller stranger = new PromptCaller("outro", "MEMBER");
    private final PromptCaller admin = new PromptCaller("adm", "ADMIN");

    private UUID id;
    private Prompt prompt;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID();
        prompt = Prompt.builder().id(id).title("Titulo").description("d").content("conteudo").type("prompt")
                .visibility("public").status("published").authorId("dono").authorName("Dona")
                .tags(new HashSet<>(Set.of("a", "b"))).useCount(0).forkCount(0).build();
    }

    private static PromptFilter noFilter() {
        return new PromptFilter(null, null, null, null, null, null, false, null);
    }

    // ───────────── Lista paginada ─────────────

    @Test
    @DisplayName("lista paginada: ordenação, teto de tamanho, favorito e permissões por perfil")
    void searchBuildsPageDtoWithPerViewerFlags() {
        Prompt mine = Prompt.builder().id(UUID.randomUUID()).title("meu").visibility("private").authorId("dono").build();
        Prompt theirs = Prompt.builder().id(UUID.randomUUID()).title("deles").visibility("public").authorId("outro").build();
        when(promptRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(mine, theirs), org.springframework.data.domain.PageRequest.of(0, 24), 2));
        when(favoriteRepository.findByUserIdAndPromptIdIn(eq("dono"), anyCollection()))
                .thenReturn(List.of(PromptFavorite.builder().userId("dono").promptId(theirs.getId()).build()));

        PromptPageDto<PromptDto> page = service.searchPromptDtos(noFilter(), "used", owner, 0, 5000);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(promptRepository).findAll(any(Specification.class), captor.capture());
        assertEquals(100, captor.getValue().getPageSize());
        assertEquals("useCount", captor.getValue().getSort().iterator().next().getProperty());
        assertEquals(2, page.totalElements());
        PromptDto first = page.content().get(0);
        assertTrue(first.canEdit());
        assertFalse(first.canModerate());
        assertFalse(first.favorite());
        assertEquals("private", first.visibility());
        PromptDto second = page.content().get(1);
        assertFalse(second.canEdit());
        assertTrue(second.favorite());
    }

    @Test
    @DisplayName("cada ordenação mapeia para a coluna certa; o padrão é recente")
    void sortMapping() {
        when(promptRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
        String[][] cases = {{"recent", "updatedAt"}, {null, "updatedAt"}, {"used", "useCount"},
                {"cloned", "forkCount"}, {"az", "title"}, {"lixo", "updatedAt"}};
        for (String[] c : cases) {
            service.searchPrompts(noFilter(), c[0], owner, 0, 10);
        }
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(promptRepository, times(cases.length)).findAll(any(Specification.class), captor.capture());
        for (int i = 0; i < cases.length; i++) {
            assertEquals(cases[i][1], captor.getAllValues().get(i).getSort().iterator().next().getProperty(), cases[i][0]);
        }
        assertTrue(captor.getAllValues().get(4).getSort().iterator().next().isIgnoreCase());
    }

    @Test
    @DisplayName("ADMIN vê canModerate; visitante nunca tem favorito nem consulta favoritos")
    void adminModeratesAndVisitorHasNoFavorites() {
        when(promptRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(prompt)));

        PromptDto asAdmin = service.searchPromptDtos(noFilter(), null, admin, 0, 24).content().get(0);
        assertTrue(asAdmin.canModerate());
        assertTrue(asAdmin.canEdit());

        PromptDto asVisitor = service.searchPromptDtos(noFilter(), null, null, 0, 24).content().get(0);
        assertFalse(asVisitor.favorite());
        assertFalse(asVisitor.canEdit());
        assertFalse(asVisitor.canModerate());
        verify(favoriteRepository, times(1)).findByUserIdAndPromptIdIn(anyString(), anyCollection());
    }

    @Test
    @DisplayName("visibilidade antiga (squad/role) vira private no DTO")
    void legacyVisibilityBecomesPrivate() {
        prompt.setVisibility("squad");
        assertEquals("private", PromptDto.of(prompt, false, false, false).visibility());
        prompt.setVisibility(null);
        assertEquals("private", PromptDto.of(prompt, false, false, false).visibility());
        prompt.setVisibility("public");
        assertEquals("public", PromptDto.of(prompt, false, false, false).visibility());
    }

    @Test
    @DisplayName("detalhe devolve DTO com favorito; item privado de outro é 404")
    void detailDtoAndHidden() {
        when(promptRepository.findById(id)).thenReturn(Optional.of(prompt));
        when(favoriteRepository.existsById(new PromptFavorite.Key("outro", id))).thenReturn(true);
        assertTrue(service.getVisiblePromptDto(id, stranger).favorite());

        prompt.setVisibility("private");
        assertThrows(IllegalArgumentException.class, () -> service.getVisiblePromptDto(id, stranger));
        assertEquals(id, service.getVisiblePromptDto(id, admin).id());
    }

    // ───────────── Facets ─────────────

    @Test
    @DisplayName("facets: total, pílulas por tipo, 10 tags e totais gerais por perfil")
    void facets() {
        when(promptRepository.count(any(Specification.class))).thenReturn(7L, 12L);
        when(queryRepository.countByType(any())).thenReturn(Map.of("prompt", 5L, "skill", 2L));
        when(queryRepository.topTags(any(), eq(10))).thenReturn(List.of(new PromptFacetsDto.TagCount("scrum", 3)));
        when(queryRepository.sumUseCount(any())).thenReturn(40L);
        when(promptCollectionRepository.countVisibleTo("dono")).thenReturn(4L);

        PromptFacetsDto f = service.facets(noFilter(), owner);

        assertEquals(7, f.total());
        assertEquals(5L, f.typeCounts().get("prompt"));
        assertEquals("scrum", f.topTags().get(0).tag());
        assertEquals(new PromptFacetsDto.Totals(12, 40, 4), f.totals());
    }

    @Test
    @DisplayName("facets: coleções contadas por perfil (visitante só públicas, ADMIN todas)")
    void facetsCollectionsByRole() {
        when(promptCollectionRepository.countPublic()).thenReturn(2L);
        when(promptCollectionRepository.count()).thenReturn(9L);
        assertEquals(2, service.facets(noFilter(), null).totals().collections());
        assertEquals(9, service.facets(noFilter(), admin).totals().collections());
    }

    // ───────────── Favoritos ─────────────

    @Test
    @DisplayName("favoritar é idempotente: segunda chamada não grava de novo")
    void favoriteIsIdempotent() {
        when(promptRepository.findById(id)).thenReturn(Optional.of(prompt));
        PromptFavorite.Key key = new PromptFavorite.Key("outro", id);
        when(favoriteRepository.existsById(key)).thenReturn(false, true);

        assertTrue(service.setFavorite(id, stranger, true));
        assertTrue(service.setFavorite(id, stranger, true));

        verify(favoriteRepository, times(1)).save(any(PromptFavorite.class));
    }

    @Test
    @DisplayName("desfavoritar é idempotente: só remove se existir")
    void unfavoriteIsIdempotent() {
        when(promptRepository.findById(id)).thenReturn(Optional.of(prompt));
        PromptFavorite.Key key = new PromptFavorite.Key("outro", id);
        when(favoriteRepository.existsById(key)).thenReturn(true, false);

        assertFalse(service.setFavorite(id, stranger, false));
        assertFalse(service.setFavorite(id, stranger, false));

        verify(favoriteRepository, times(1)).deleteById(key);
    }

    @Test
    @DisplayName("favoritar item privado de outro usuário responde 404 e não grava")
    void favoriteHiddenItemIs404() {
        prompt.setVisibility("private");
        when(promptRepository.findById(id)).thenReturn(Optional.of(prompt));

        assertThrows(IllegalArgumentException.class, () -> service.setFavorite(id, stranger, true));
        verify(favoriteRepository, never()).save(any(PromptFavorite.class));
    }

    // ───────────── Versões ─────────────

    @Test
    @DisplayName("criar grava a versão 1")
    void createRecordsVersionOne() {
        Prompt novo = Prompt.builder().title("Novo").content("x").visibility("public").build();
        when(promptRepository.save(any(Prompt.class))).thenAnswer(i -> {
            Prompt p = i.getArgument(0);
            p.setId(id);
            return p;
        });
        when(versionRepository.maxVersionNumber(id)).thenReturn(0);

        service.createPrompt(novo, owner);

        ArgumentCaptor<PromptVersion> v = ArgumentCaptor.forClass(PromptVersion.class);
        verify(versionRepository).save(v.capture());
        assertEquals(1, v.getValue().getVersionNumber());
        assertEquals("Novo", v.getValue().getTitle());
        assertEquals("dono", v.getValue().getChangedBy());
    }

    @Test
    @DisplayName("editar o conteúdo grava versão; item sem histórico ganha antes a versão 1 do estado anterior")
    void updateRecordsBaselineAndNewVersion() {
        when(promptRepository.findById(id)).thenReturn(Optional.of(prompt));
        when(promptRepository.save(any(Prompt.class))).thenAnswer(i -> i.getArgument(0));
        when(versionRepository.maxVersionNumber(id)).thenReturn(0, 1);

        Prompt edit = Prompt.builder().title("Titulo").description("d").content("NOVO").type("prompt")
                .visibility("public").status("published").tags(new HashSet<>(Set.of("a", "b"))).build();
        service.updatePrompt(id, edit, owner);

        ArgumentCaptor<PromptVersion> v = ArgumentCaptor.forClass(PromptVersion.class);
        verify(versionRepository, times(2)).save(v.capture());
        assertEquals(1, v.getAllValues().get(0).getVersionNumber());
        assertEquals("conteudo", v.getAllValues().get(0).getContent());
        assertEquals(2, v.getAllValues().get(1).getVersionNumber());
        assertEquals("NOVO", v.getAllValues().get(1).getContent());
    }

    @Test
    @DisplayName("editar só campos não versionados (impacto, links) não grava versão")
    void updateWithoutVersionedChangeDoesNotRecord() {
        when(promptRepository.findById(id)).thenReturn(Optional.of(prompt));
        when(promptRepository.save(any(Prompt.class))).thenAnswer(i -> i.getArgument(0));
        when(versionRepository.maxVersionNumber(id)).thenReturn(3);

        Prompt edit = Prompt.builder().title("Titulo").description("d").content("conteudo").type("prompt")
                .visibility("public").status("published").impact("high").gemLink("https://x")
                .tags(new HashSet<>(Set.of("b", "a"))).build();
        service.updatePrompt(id, edit, owner);

        verify(versionRepository, never()).save(any(PromptVersion.class));
    }

    @Test
    @DisplayName("mudar tag, visibilidade ou status também versiona")
    void tagVisibilityStatusChangeRecords() {
        when(promptRepository.findById(id)).thenReturn(Optional.of(prompt));
        when(promptRepository.save(any(Prompt.class))).thenAnswer(i -> i.getArgument(0));
        when(versionRepository.maxVersionNumber(id)).thenReturn(1);

        Prompt edit = Prompt.builder().title("Titulo").description("d").content("conteudo").type("prompt")
                .visibility("private").status("archived").tags(new HashSet<>(Set.of("a", "c"))).build();
        service.updatePrompt(id, edit, owner);

        ArgumentCaptor<PromptVersion> v = ArgumentCaptor.forClass(PromptVersion.class);
        verify(versionRepository).save(v.capture());
        assertEquals(2, v.getValue().getVersionNumber());
        assertEquals("private", v.getValue().getVisibility());
        assertEquals("archived", v.getValue().getStatus());
        assertEquals("[\"a\",\"c\"]", v.getValue().getTags());
    }

    @Test
    @DisplayName("lista de versões: mais recente primeiro e só ela é current")
    void listVersionsMarksCurrent() {
        when(promptRepository.findById(id)).thenReturn(Optional.of(prompt));
        when(versionRepository.findByPromptIdOrderByVersionNumberDesc(id)).thenReturn(List.of(
                PromptVersion.builder().promptId(id).versionNumber(3).title("t3").changedAt(Instant.now()).build(),
                PromptVersion.builder().promptId(id).versionNumber(2).title("t2").changedAt(Instant.now()).build()));

        List<PromptVersionDto> list = service.listVersions(id, stranger);

        assertEquals(3, list.get(0).versionNumber());
        assertTrue(list.get(0).current());
        assertFalse(list.get(1).current());
        assertNull(list.get(0).content());
    }

    @Test
    @DisplayName("item sem histórico mostra o estado atual como versão 1")
    void virtualVersionForUntouchedItem() {
        when(promptRepository.findById(id)).thenReturn(Optional.of(prompt));
        when(versionRepository.findByPromptIdOrderByVersionNumberDesc(id)).thenReturn(List.of());

        List<PromptVersionDto> list = service.listVersions(id, stranger);
        assertEquals(1, list.size());
        assertTrue(list.get(0).current());
        PromptVersionDto full = service.getVersion(id, 1, stranger);
        assertEquals("conteudo", full.content());
        assertThrows(IllegalArgumentException.class, () -> service.getVersion(id, 2, stranger));
    }

    @Test
    @DisplayName("versões só para quem pode ver o item (privado de outro = 404)")
    void versionsOfHiddenItemAre404() {
        prompt.setVisibility("private");
        when(promptRepository.findById(id)).thenReturn(Optional.of(prompt));

        assertThrows(IllegalArgumentException.class, () -> service.listVersions(id, stranger));
        assertThrows(IllegalArgumentException.class, () -> service.getVersion(id, 1, stranger));
        verifyNoInteractions(versionRepository);
    }

    @Test
    @DisplayName("versão inexistente é 404")
    void unknownVersionIs404() {
        when(promptRepository.findById(id)).thenReturn(Optional.of(prompt));
        when(versionRepository.findByPromptIdOrderByVersionNumberDesc(id)).thenReturn(List.of(
                PromptVersion.builder().promptId(id).versionNumber(1).title("t").changedAt(Instant.now()).build()));

        assertThrows(IllegalArgumentException.class, () -> service.getVersion(id, 9, owner));
    }

    @Test
    @DisplayName("restaurar cria uma NOVA versão com o conteúdo antigo (o histórico não é reescrito)")
    void restoreCreatesNewVersion() {
        prompt.setContent("atual");
        when(promptRepository.findById(id)).thenReturn(Optional.of(prompt));
        when(promptRepository.save(any(Prompt.class))).thenAnswer(i -> i.getArgument(0));
        when(versionRepository.maxVersionNumber(id)).thenReturn(4);
        when(versionRepository.findByPromptIdAndVersionNumber(id, 2)).thenReturn(Optional.of(
                PromptVersion.builder().promptId(id).versionNumber(2).title("Antigo").description("dd")
                        .content("antigo").type("skill").visibility("squad").status("draft")
                        .tags("[\"x\",\"y\"]").changedAt(Instant.now()).build()));

        Prompt restored = service.restoreVersion(id, 2, owner);

        assertEquals("antigo", restored.getContent());
        assertEquals("Antigo", restored.getTitle());
        assertEquals("private", restored.getVisibility());
        assertEquals(Set.of("x", "y"), restored.getTags());
        ArgumentCaptor<PromptVersion> v = ArgumentCaptor.forClass(PromptVersion.class);
        verify(versionRepository).save(v.capture());
        assertEquals(5, v.getValue().getVersionNumber());
        assertEquals("antigo", v.getValue().getContent());
        verify(versionRepository, never()).delete(any(PromptVersion.class));
    }

    @Test
    @DisplayName("restaurar: estranho 403, ADMIN pode, versão inexistente 404")
    void restorePermissions() {
        when(promptRepository.findById(id)).thenReturn(Optional.of(prompt));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.restoreVersion(id, 1, stranger));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());

        when(versionRepository.maxVersionNumber(id)).thenReturn(2);
        when(versionRepository.findByPromptIdAndVersionNumber(id, 7)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.restoreVersion(id, 7, admin));
    }

    // ───────────── Uso por semana ─────────────

    private static Instant at(LocalDate d) {
        return d.atTime(12, 0).toInstant(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("uso por semana: todas as semanas (zeros incluídos), segunda UTC, uso e clone separados")
    void usageByWeekWithZeros() {
        when(promptRepository.findById(id)).thenReturn(Optional.of(prompt));
        LocalDate monday = LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        when(usageRepository.findByPromptIdAndOccurredAtGreaterThanEqual(eq(id), any(Instant.class))).thenReturn(List.of(
                PromptUsageEvent.builder().promptId(id).kind("use").occurredAt(at(monday)).build(),
                PromptUsageEvent.builder().promptId(id).kind("use").occurredAt(at(monday)).build(),
                PromptUsageEvent.builder().promptId(id).kind("clone").occurredAt(at(monday)).build(),
                PromptUsageEvent.builder().promptId(id).kind("use").occurredAt(at(monday.minusWeeks(2))).build()));

        List<PromptUsageWeekDto> weeks = service.usageByWeek(id, 8, stranger);

        assertEquals(8, weeks.size());
        assertEquals(monday.minusWeeks(7).toString(), weeks.get(0).weekStart());
        PromptUsageWeekDto last = weeks.get(7);
        assertEquals(monday.toString(), last.weekStart());
        assertEquals(2, last.uses());
        assertEquals(1, last.clones());
        assertEquals(1, weeks.get(5).uses());
        assertEquals(0, weeks.get(6).uses());
        assertEquals(0, weeks.get(0).clones());
        for (PromptUsageWeekDto w : weeks) {
            assertEquals(DayOfWeek.MONDAY, LocalDate.parse(w.weekStart()).getDayOfWeek());
        }
    }

    @Test
    @DisplayName("uso por semana: weeks limitado a 1..52; item privado de outro é 404")
    void usageClampAndHidden() {
        when(promptRepository.findById(id)).thenReturn(Optional.of(prompt));
        when(usageRepository.findByPromptIdAndOccurredAtGreaterThanEqual(any(), any())).thenReturn(List.of());
        assertEquals(52, service.usageByWeek(id, 500, owner).size());
        assertEquals(1, service.usageByWeek(id, 0, owner).size());

        prompt.setVisibility("private");
        assertThrows(IllegalArgumentException.class, () -> service.usageByWeek(id, 8, stranger));
    }

    @Test
    @DisplayName("use e clone gravam evento além do contador")
    void useAndCloneRecordEvents() {
        when(promptRepository.findById(id)).thenReturn(Optional.of(prompt));
        when(promptRepository.save(any(Prompt.class))).thenAnswer(i -> {
            Prompt p = i.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });

        service.incrementUseCount(id, stranger);
        service.clonePrompt(id, null, stranger);

        verify(promptRepository).incrementUseCount(id);
        verify(promptRepository).incrementForkCount(id);
        ArgumentCaptor<PromptUsageEvent> e = ArgumentCaptor.forClass(PromptUsageEvent.class);
        verify(usageRepository, times(2)).save(e.capture());
        assertEquals("use", e.getAllValues().get(0).getKind());
        assertEquals("clone", e.getAllValues().get(1).getKind());
        assertEquals("outro", e.getAllValues().get(0).getUserId());
        assertEquals(id, e.getAllValues().get(1).getPromptId());
    }

    @Test
    @DisplayName("uso de item privado de outro: 404, sem contar nem gravar evento")
    void useOfHiddenItemRecordsNothing() {
        prompt.setVisibility("private");
        when(promptRepository.findById(id)).thenReturn(Optional.of(prompt));

        assertThrows(IllegalArgumentException.class, () -> service.incrementUseCount(id, stranger));
        verify(promptRepository, never()).incrementUseCount(any());
        verifyNoInteractions(usageRepository);
    }

    // ───────────── Moderação de comentários ─────────────

    private PromptComment comment(String authorId) {
        PromptComment c = PromptComment.builder().id(UUID.randomUUID()).content("oi").authorId(authorId).prompt(prompt).build();
        when(commentRepository.findById(c.getId())).thenReturn(Optional.of(c));
        return c;
    }

    @Test
    @DisplayName("ADMIN remove comentário de qualquer item; dono do item e autor do comentário também")
    void commentModeration() {
        when(promptRepository.findById(id)).thenReturn(Optional.of(prompt));

        PromptComment byStranger = comment("outro");
        service.deleteComment(id, byStranger.getId(), admin);
        verify(commentRepository).delete(byStranger);

        PromptComment byOther = comment("terceiro");
        service.deleteComment(id, byOther.getId(), owner); // dono do item
        verify(commentRepository).delete(byOther);

        PromptComment mineAsStranger = comment("outro");
        service.deleteComment(id, mineAsStranger.getId(), stranger); // autor do comentário
        verify(commentRepository).delete(mineAsStranger);
    }

    @Test
    @DisplayName("terceiro não remove comentário alheio (403)")
    void commentByThirdPartyForbidden() {
        when(promptRepository.findById(id)).thenReturn(Optional.of(prompt));
        PromptComment c = comment("dono");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.deleteComment(id, c.getId(), new PromptCaller("terceiro", "MEMBER")));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(commentRepository, never()).delete(any(PromptComment.class));
    }

    @Test
    @DisplayName("ADMIN edita e exclui qualquer item")
    void adminEditsAndDeletesAnyItem() {
        when(promptRepository.findById(id)).thenReturn(Optional.of(prompt));
        when(promptRepository.save(any(Prompt.class))).thenAnswer(i -> i.getArgument(0));
        Prompt edit = Prompt.builder().title("Editado pelo admin").content("c").visibility("public").build();

        assertEquals("Editado pelo admin", service.updatePrompt(id, edit, admin).getTitle());
        service.deletePrompt(id, admin);
        verify(promptRepository).delete(prompt);
    }
}
