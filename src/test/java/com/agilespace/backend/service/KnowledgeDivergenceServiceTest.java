package com.agilespace.backend.service;

import com.agilespace.backend.domain.KnowledgeDocRelation;
import com.agilespace.backend.domain.KnowledgeDocument;
import com.agilespace.backend.dto.KnowledgeDivergenceDtos.Report;
import com.agilespace.backend.repository.KnowledgeDocRelationRepository;
import com.agilespace.backend.repository.KnowledgeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KnowledgeDivergenceService - código x TDN por identificadores")
class KnowledgeDivergenceServiceTest {

    @Mock
    private KnowledgeRepository knowledgeRepository;
    @Mock
    private KnowledgeDocRelationRepository relationRepository;
    @InjectMocks
    private KnowledgeDivergenceService service;

    private static KnowledgeDocument doc(String source, String ext, String content) {
        return KnowledgeDocument.builder().id(UUID.randomUUID()).source(source).externalId(ext).title(ext)
                .moduleName("pedidos").status("published").content(content).build();
    }

    @Test
    @DisplayName("extrai rotas (parâmetros normalizados), crases, camelCase e snake_case")
    void extractsIdentifiers() {
        Set<String> ids = KnowledgeDivergenceService.identifiers(
                "POST /pedidos/{id}/itens e GET /pedidos/:pedidoId. Use `statusCode` no OrderService.createOrder; "
                        + "tabela tb_pedido_item. Texto comum sem nada.");

        assertTrue(ids.contains("/pedidos/{}/itens"));
        assertTrue(ids.contains("/pedidos/{}"));
        assertTrue(ids.contains("statuscode"));
        assertTrue(ids.contains("orderservice"));
        assertTrue(ids.contains("tb_pedido_item"));
        assertFalse(ids.contains("texto"));
        assertTrue(KnowledgeDivergenceService.identifiers(null).isEmpty());
    }

    @Test
    @DisplayName("limiares de status")
    void statusThresholds() {
        assertEquals("aligned", KnowledgeDivergenceService.status(0.7));
        assertEquals("review", KnowledgeDivergenceService.status(0.69));
        assertEquals("review", KnowledgeDivergenceService.status(0.3));
        assertEquals("diverges", KnowledgeDivergenceService.status(0.29));
    }

    @Test
    @DisplayName("relatório: pior par primeiro, diferenças dos dois lados e código sem TDN listado")
    void reportOrdersAndListsUnlinked() {
        KnowledgeDocument codeA = doc("code", "p:A.md", "/pedidos /pedidos/{id} OrderService tb_pedido");
        KnowledgeDocument tdnA = doc("tdn", "1", "/pedidos /pedidos/{id} OrderService tb_pedido");
        KnowledgeDocument codeB = doc("code", "p:B.md", "/clientes /clientes/{id} CustomerService tb_cliente");
        KnowledgeDocument tdnB = doc("tdn", "2", "/fornecedores SupplierService");
        KnowledgeDocument codeC = doc("code", "p:C.md", "/itens");
        KnowledgeDocRelation r1 = KnowledgeDocRelation.builder().documentId(codeA.getId()).relatedDocumentId(tdnA.getId()).build();
        // relação gravada no sentido inverso e duplicada: conta uma vez
        KnowledgeDocRelation r2 = KnowledgeDocRelation.builder().documentId(tdnB.getId()).relatedDocumentId(codeB.getId()).build();
        KnowledgeDocRelation r2dup = KnowledgeDocRelation.builder().documentId(codeB.getId()).relatedDocumentId(tdnB.getId()).build();
        when(relationRepository.findAll()).thenReturn(List.of(r1, r2, r2dup));
        when(knowledgeRepository.findAllById(any())).thenReturn(List.of(codeA, tdnA, codeB, tdnB));
        when(knowledgeRepository.findBySourceAndStatusIn(any(), any())).thenReturn(List.of(codeA, codeB, codeC));

        Report r = service.report(null, null);

        assertEquals(2, r.pairCount());
        assertEquals(1, r.diverging());
        assertEquals(1, r.aligned());
        assertEquals("p:B.md", r.pairs().get(0).code().externalId());
        assertEquals("diverges", r.pairs().get(0).status());
        assertTrue(r.pairs().get(0).onlyInCode().contains("customerservice"));
        assertTrue(r.pairs().get(0).onlyInTdn().contains("supplierservice"));
        assertEquals(1.0, r.pairs().get(1).similarity());
        assertEquals(List.of("p:C.md"), r.unlinkedCode());
        assertEquals(1, r.unlinkedCodeCount());
    }

    @Test
    @DisplayName("filtro por módulo e docs fora de publicado/indexado são ignorados")
    void moduleFilterAndStatus() {
        KnowledgeDocument code = doc("code", "p:A.md", "/a");
        code.setModuleName("outro");
        KnowledgeDocument tdn = doc("tdn", "1", "/a");
        KnowledgeDocument deleted = doc("code", "p:D.md", "/d");
        deleted.setStatus("deleted");
        when(relationRepository.findAll()).thenReturn(List.of(
                KnowledgeDocRelation.builder().documentId(code.getId()).relatedDocumentId(tdn.getId()).build(),
                KnowledgeDocRelation.builder().documentId(deleted.getId()).relatedDocumentId(tdn.getId()).build()));
        when(knowledgeRepository.findAllById(any())).thenReturn(List.of(code, tdn, deleted));
        when(knowledgeRepository.findBySourceAndStatusIn(any(), any())).thenReturn(List.of(code));

        assertEquals(0, service.report("pedidos", null).pairCount());
        assertEquals(1, service.report("outro", null).pairCount());
    }
}
