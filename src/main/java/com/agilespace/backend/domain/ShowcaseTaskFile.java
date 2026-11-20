package com.agilespace.backend.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Arquivo (PNG, JPEG ou PDF) anexado a um card da Review. Só os metadados vivem no banco;
 * o conteúdo fica no {@link com.agilespace.backend.storage.FileStorage}.
 */
@Entity
@Table(name = "showcase_task_files")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShowcaseTaskFile {

    @Id
    private String id;

    @JsonIgnore
    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "original_name", nullable = false)
    private String name;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long size;

    /** Nome gerado pelo servidor no armazenamento; nunca sai pela API. */
    @JsonIgnore
    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "uploaded_by")
    private String uploadedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
