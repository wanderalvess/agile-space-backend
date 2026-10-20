package com.agilespace.backend.service;

import com.agilespace.backend.domain.JiraIssueTypeCache;
import com.agilespace.backend.domain.SquadSubtaskTypeMapping;
import com.agilespace.backend.domain.SubtaskClass;
import com.agilespace.backend.domain.SubtaskMappingOrigin;
import com.agilespace.backend.repository.JiraIssueTypeCacheRepository;
import com.agilespace.backend.repository.SquadSubtaskTypeMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubtaskTypeMappingService - classe pelo tipo, semente por id/nome, exceção Code Review QA, herança")
class SubtaskTypeMappingServiceTest {

    @Mock private SquadSubtaskTypeMappingRepository mappingRepository;
    @Mock private JiraIssueTypeCacheRepository cacheRepository;
    private SubtaskTypeMappingService service;

    @BeforeEach
    void setUp() {
        service = new SubtaskTypeMappingService(mappingRepository, cacheRepository);
        lenient().when(mappingRepository.findBySquadIdAndSprintKey(any(), any())).thenReturn(List.of());
        lenient().when(mappingRepository.findBySquadIdOrderByUpdatedAtDesc(any())).thenReturn(List.of());
        lenient().when(cacheRepository.findBySquadIdOrderByNameAsc(any())).thenReturn(List.of());
    }

    private static JiraIssueTypeCache type(String id, String name, boolean subtask) {
        return JiraIssueTypeCache.builder().squadId("SQ").typeId(id).name(name).subtask(subtask).fetchedAt("2026-10-20T00:00:00Z").build();
    }

    private static SquadSubtaskTypeMapping row(String sprint, String id, String name, SubtaskClass c, String updatedAt) {
        return SquadSubtaskTypeMapping.builder().id(SquadSubtaskTypeMapping.idFor("SQ", sprint, id)).squadId("SQ").sprintKey(sprint)
                .typeId(id).typeName(name).subtaskClass(c).origin(SubtaskMappingOrigin.MANUAL).updatedAt(updatedAt).build();
    }

    @Test
    @DisplayName("semente por id: Codificação/Code Review = DEV (CR 1 h), Execução de TI/Teste Automatizado/Documento Técnico = QA, resto SEM")
    void seedById() {
        when(cacheRepository.findBySquadIdOrderByNameAsc("SQ")).thenReturn(List.of(
                type("10036", "Codificação", true), type("12300", "Code Review", true), type("10013", "Execução de TI", true),
                type("10069", "Teste Automatizado", true), type("10063", "Documento Técnico", true), type("10999", "Reunião", true),
                type("1", "História", false)));
        Map<String, SubtaskTypeMappingService.ResolvedType> m = service.resolveMap("SQ", null, List.of());
        assertEquals(SubtaskClass.DEV, m.get("10036").subtaskClass());
        assertEquals(SubtaskClass.DEV, m.get("12300").subtaskClass());
        assertEquals(0, BigDecimal.ONE.compareTo(m.get("12300").defaultHours()));
        assertTrue(m.get("12300").qaByTitleException());
        assertEquals(SubtaskClass.QA, m.get("10013").subtaskClass());
        assertEquals(SubtaskClass.QA, m.get("10069").subtaskClass());
        assertEquals(SubtaskClass.QA, m.get("10063").subtaskClass());
        assertEquals(SubtaskClass.SEM_CLASSIFICACAO, m.get("10999").subtaskClass());
        assertFalse(m.containsKey("1"), "tipo que não é subtarefa fica fora da lista");
        assertTrue(m.values().stream().allMatch(t -> t.origin() == SubtaskMappingOrigin.PADRAO));
    }

    @Test
    @DisplayName("instância nova com outros ids: casa pelo NOME (sem acento/caixa) quando o id da semente não existe")
    void seedByNameWhenIdMissing() {
        when(cacheRepository.findBySquadIdOrderByNameAsc("SQ")).thenReturn(List.of(
                type("20001", "codificacao", true), type("20002", "CODE REVIEW", true), type("20003", "Execucao de TI", true)));
        Map<String, SubtaskTypeMappingService.ResolvedType> m = service.resolveMap("SQ", "GLOBAL", List.of());
        assertEquals(SubtaskClass.DEV, m.get("20001").subtaskClass());
        assertEquals(SubtaskClass.DEV, m.get("20002").subtaskClass());
        assertTrue(m.get("20002").qaByTitleException());
        assertEquals(SubtaskClass.QA, m.get("20003").subtaskClass());
    }

    @Test
    @DisplayName("se o id da semente EXISTE na instância com outro tipo, o nome igual em outro id não herda a semente")
    void nameDoesNotMatchWhenSeedIdExists() {
        Set<String> ids = Set.of("10036", "30000");
        assertNull(SubtaskTypeMappingService.seedFor("30000", "Codificação", ids));
        assertNotNull(SubtaskTypeMappingService.seedFor("30000", "Codificação", Set.of("30000")));
    }

    @Test
    @DisplayName("exceção: Code Review com a palavra QA no título = QA (caixa indiferente); sem a palavra continua DEV")
    void codeReviewQaException() {
        SubtaskTypeMappingService.ResolvedType cr = SubtaskTypeMappingService.seeded("12300", "Code Review", Set.of());
        assertEquals(SubtaskClass.QA, SubtaskTypeMappingService.classify(cr, "Code Review QA"));
        assertEquals(SubtaskClass.QA, SubtaskTypeMappingService.classify(cr, "[qa] revisão dos testes"));
        assertEquals(SubtaskClass.DEV, SubtaskTypeMappingService.classify(cr, "Code Review"));
        assertEquals(SubtaskClass.DEV, SubtaskTypeMappingService.classify(cr, "Revisão QAS e QUALIDADE"), "QA só como palavra");
        SubtaskTypeMappingService.ResolvedType cod = SubtaskTypeMappingService.seeded("10036", "Codificação", Set.of());
        assertEquals(SubtaskClass.DEV, SubtaskTypeMappingService.classify(cod, "Codificação QA"), "exceção só vale para Code Review");
        assertEquals(SubtaskClass.SEM_CLASSIFICACAO, SubtaskTypeMappingService.classify(null, "x"));
    }

    @Test
    @DisplayName("sprint sem mapeamento herda o da sprint anterior da squad (origem HERDADO)")
    void inheritsFromPreviousSprint() {
        SquadSubtaskTypeMapping prev = row("500", "10999", "Reunião", SubtaskClass.FORA_DA_CONTA, "2026-10-01T00:00:00Z");
        when(mappingRepository.findBySquadIdOrderByUpdatedAtDesc("SQ")).thenReturn(List.of(prev));
        when(mappingRepository.findBySquadIdAndSprintKey("SQ", "500")).thenReturn(List.of(prev));
        when(cacheRepository.findBySquadIdOrderByNameAsc("SQ")).thenReturn(List.of(type("10999", "Reunião", true), type("10036", "Codificação", true)));

        Map<String, SubtaskTypeMappingService.ResolvedType> m = service.resolveMap("SQ", "501", List.of());
        assertEquals(SubtaskClass.FORA_DA_CONTA, m.get("10999").subtaskClass());
        assertEquals(SubtaskMappingOrigin.HERDADO, m.get("10999").origin());
        assertEquals("500", m.get("10999").inheritedFromSprint());
        assertEquals(SubtaskMappingOrigin.PADRAO, m.get("10036").origin(), "tipo sem linha na anterior cai na semente");
    }

    @Test
    @DisplayName("mapeamento da própria sprint vence a herança")
    void ownSprintWins() {
        SquadSubtaskTypeMapping own = row("501", "10999", "Reunião", SubtaskClass.QA, "2026-10-02T00:00:00Z");
        when(mappingRepository.findBySquadIdAndSprintKey("SQ", "501")).thenReturn(List.of(own));
        Map<String, SubtaskTypeMappingService.ResolvedType> m = service.resolveMap("SQ", "501", List.of());
        assertEquals(SubtaskClass.QA, m.get("10999").subtaskClass());
        assertEquals(SubtaskMappingOrigin.MANUAL, m.get("10999").origin());
        verify(mappingRepository, never()).findBySquadIdOrderByUpdatedAtDesc(any());
    }

    @Test
    @DisplayName("PUT na primeira vez grava a lista resolvida e marca só o alterado como MANUAL, com quem/quando")
    @SuppressWarnings("unchecked")
    void updateMaterializesAndMarksManual() {
        when(cacheRepository.findBySquadIdOrderByNameAsc("SQ")).thenReturn(List.of(type("10036", "Codificação", true), type("10999", "Reunião", true)));
        service.update("SQ", "501", List.of(new SubtaskTypeMappingService.UpdateRequest("10999", null, SubtaskClass.FORA_DA_CONTA, null, null)), "u1");
        ArgumentCaptor<Collection<SquadSubtaskTypeMapping>> cap = ArgumentCaptor.forClass(Collection.class);
        verify(mappingRepository).saveAll(cap.capture());
        Map<String, SquadSubtaskTypeMapping> saved = new HashMap<>();
        cap.getValue().forEach(r -> saved.put(r.getTypeId(), r));
        assertEquals(SubtaskMappingOrigin.PADRAO, saved.get("10036").getOrigin());
        assertEquals(SubtaskClass.FORA_DA_CONTA, saved.get("10999").getSubtaskClass());
        assertEquals(SubtaskMappingOrigin.MANUAL, saved.get("10999").getOrigin());
        assertEquals("u1", saved.get("10999").getUpdatedBy());
        assertEquals("Reunião", saved.get("10999").getTypeName());
        assertEquals("501", saved.get("10999").getSprintKey());
    }

    @Test
    @DisplayName("PUT sem classe ou com sprint/tipo inválido -> 422")
    void updateValidation() {
        assertThrows(ResponseStatusException.class, () -> service.update("SQ", "501",
                List.of(new SubtaskTypeMappingService.UpdateRequest("10999", null, null, null, null)), "u1"));
        assertThrows(ResponseStatusException.class, () -> service.update("SQ", "501 OR 1=1",
                List.of(new SubtaskTypeMappingService.UpdateRequest("10999", null, SubtaskClass.QA, null, null)), "u1"));
        assertThrows(ResponseStatusException.class, () -> service.update("SQ", "501",
                List.of(new SubtaskTypeMappingService.UpdateRequest("10 99", null, SubtaskClass.QA, null, null)), "u1"));
        assertThrows(ResponseStatusException.class, () -> service.update("SQ", "501", List.of(), "u1"));
    }
}
