package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/** Ligação entre dois documentos da KB (ex.: doc de código e a página do TDN sobre o mesmo assunto). Criada pela ingestão. */
@Entity
@Table(name = "knowledge_doc_relation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeDocRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "related_document_id", nullable = false)
    private UUID relatedDocumentId;

    @Column(nullable = false, length = 30)
    @Builder.Default
    private String kind = "related";
}
