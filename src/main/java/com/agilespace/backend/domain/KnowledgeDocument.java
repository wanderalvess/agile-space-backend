package com.agilespace.backend.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "knowledge_kb")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true)
    private String tdnId; // ID vindo da importação externa do TDN/Confluence

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    private String category;
    private String fullPath;
    private String moduleId;
    private String moduleName;
    private String folderId;
    private String folderName;

    @Column(length = 50)
    private String status; // indexed, published, deleted

    @Column(nullable = false)
    private String authorId;

    @Builder.Default
    private Long byteSize = 0L;

    @Builder.Default
    private Integer views = 0;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "knowledge_tags", joinColumns = @JoinColumn(name = "document_id"))
    @Column(name = "tag")
    @Builder.Default
    private Set<String> tags = new HashSet<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    private String updatedBy;

    // Squad do autor no momento da criação (definida no servidor). Documentos antigos ficam sem squad:
    // só o autor e ADMIN editam. A liderança dessa squad também edita.
    @Column(name = "squad_id")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String squadId;

    // Calculado a cada resposta (não é coluna): a tela usa para mostrar editar/apagar/restaurar.
    @Transient
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Boolean canEdit;

    private LocalDateTime deletedAt;
    private String deletedBy;

    // Embedding local (transformers.js, 384 dims) usado na busca semântica do chat do poker.
    // WRITE_ONLY: aceito no POST/PUT, nunca sai no JSON de listagem (evitaria ~6-7KB por doc à toa).
    @Convert(converter = EmbeddingConverter.class)
    @Column(columnDefinition = "TEXT")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private float[] embedding;

    // --- Ingestão pelo Hub (V103). Todos READ_ONLY no JSON: só o endpoint de ingestão (API key
    // com KNOWLEDGE_WRITE) grava; o POST/PUT da UI ignora o que vier no corpo. ---

    /** Tipo: api_doc, tdn_page, prompt, skill, instruction. Nulo = documento comum da UI. */
    @Column(name = "doc_type", length = 30)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String docType;

    /** Origem do conteúdo: code ou tdn. */
    @Column(length = 20)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String source;

    @Column(name = "source_url", columnDefinition = "TEXT")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String sourceUrl;

    /** Id estável vindo de fora; (docType, externalId) é a chave do upsert. */
    @Column(name = "external_id")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String externalId;

    /** Tag/commit de origem do conteúdo. */
    @Column(name = "doc_version", length = 100)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String docVersion;

    /** SHA-256 calculado no servidor (título + conteúdo + metadados): decide se a ingestão precisa regravar. */
    @Column(name = "content_hash", length = 64)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String contentHash;

    /** shared (padrão, KB compartilhada entre squads) ou restricted. Hoje só gravado; ver KnowledgeIngestService. */
    @Column(nullable = false, length = 20)
    @Builder.Default
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String visibility = "shared";
}
