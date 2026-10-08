package com.agilespace.backend.service;

import com.agilespace.backend.domain.ShowcaseTask;
import com.agilespace.backend.domain.ShowcaseTaskFile;
import com.agilespace.backend.repository.ShowcaseSessionRepository;
import com.agilespace.backend.repository.ShowcaseTaskFileRepository;
import com.agilespace.backend.repository.ShowcaseTaskRepository;
import com.agilespace.backend.storage.FileStorage;
import com.agilespace.backend.websocket.ShowcaseWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anexos (PNG, JPEG ou PDF) dos cards da Review: evidências que ainda não estão no Jira.
 *
 * O tipo do arquivo é decidido pelo CONTEÚDO (assinatura dos primeiros bytes), nunca pelo nome
 * nem pelo Content-Type que o cliente mandou — um HTML renomeado para .png é recusado.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShowcaseTaskFileService {

    public static final int MAX_FILES_PER_TASK = 5;
    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private static final int MAX_NAME_LENGTH = 120;

    public static final String PNG = "image/png";
    public static final String JPEG = "image/jpeg";
    public static final String PDF = "application/pdf";

    private final ShowcaseTaskFileRepository fileRepository;
    private final ShowcaseSessionRepository sessionRepository;
    private final ShowcaseTaskRepository taskRepository;
    private final FileStorage storage;
    private final ShowcaseWebSocketHandler webSocketHandler;

    /** Uma trava por card: o limite de 5 não pode ser furado por dois uploads simultâneos (backend roda em réplica única). */
    private final ConcurrentHashMap<String, Object> taskLocks = new ConcurrentHashMap<>();

    public record FileContent(ShowcaseTaskFile file, Resource resource) {
    }

    public ShowcaseTaskFile upload(String sessionId, String taskId, MultipartFile upload, String callerId) {
        if (upload == null || upload.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecione um arquivo para anexar.");
        }
        if (upload.getSize() > MAX_FILE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "O arquivo excede o limite de 10 MB.");
        }
        if (!sessionRepository.existsById(sessionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Review não encontrada.");
        }
        ShowcaseTask task = taskRepository.findById(taskId)
                .filter(t -> sessionId.equals(t.getSessionId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Card não encontrado. Aguarde o card salvar e tente de novo."));

        ShowcaseTaskFile saved;
        synchronized (taskLocks.computeIfAbsent(task.getId(), k -> new Object())) {
            if (fileRepository.countBySessionIdAndTaskId(sessionId, task.getId()) >= MAX_FILES_PER_TASK) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Cada card aceita no máximo " + MAX_FILES_PER_TASK + " arquivos. Remova um para anexar outro.");
            }
            saved = store(sessionId, task.getId(), upload, callerId);
        }
        webSocketHandler.broadcastRefresh(sessionId);
        return saved;
    }

    public FileContent open(String sessionId, String fileId) {
        ShowcaseTaskFile file = find(sessionId, fileId);
        try {
            return new FileContent(file, storage.load(file.getStorageKey()));
        } catch (IOException e) {
            log.warn("Arquivo {} da Review {} sem conteúdo no armazenamento", fileId, sessionId, e);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "O conteúdo deste arquivo não está mais disponível.");
        }
    }

    public void delete(String sessionId, String fileId) {
        ShowcaseTaskFile file = find(sessionId, fileId);
        fileRepository.delete(file);
        deleteContentAfterCommit(file.getStorageKey());
        webSocketHandler.broadcastRefresh(sessionId);
    }

    public List<ShowcaseTaskFile> listBySession(String sessionId) {
        return fileRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    /** Cards que saíram da Review levam seus anexos junto (banco e armazenamento). */
    public void removeOrphans(String sessionId, Set<String> keepTaskIds) {
        for (ShowcaseTaskFile file : fileRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)) {
            if (!keepTaskIds.contains(file.getTaskId())) {
                fileRepository.delete(file);
                deleteContentAfterCommit(file.getStorageKey());
            }
        }
    }

    // ------------------------------------------------------------------ internos

    private ShowcaseTaskFile store(String sessionId, String taskId, MultipartFile upload, String callerId) {
        String key = null;
        try (InputStream raw = upload.getInputStream(); BufferedInputStream in = new BufferedInputStream(raw)) {
            in.mark(16);
            String contentType = detectContentType(in.readNBytes(16));
            in.reset();
            if (contentType == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Formato não aceito. Envie PNG, JPEG ou PDF.");
            }
            key = UUID.randomUUID() + extensionFor(contentType);
            long written = storage.store(key, in);
            if (written <= 0 || written > MAX_FILE_BYTES) {
                storage.delete(key);
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "O arquivo excede o limite de 10 MB.");
            }
            ShowcaseTaskFile entity = ShowcaseTaskFile.builder()
                    .id(UUID.randomUUID().toString())
                    .sessionId(sessionId)
                    .taskId(taskId)
                    .name(sanitizeName(upload.getOriginalFilename(), contentType))
                    .contentType(contentType)
                    .size(written)
                    .storageKey(key)
                    .uploadedBy(callerId)
                    .createdAt(LocalDateTime.now())
                    .build();
            try {
                return fileRepository.save(entity);
            } catch (RuntimeException e) {
                storage.delete(key);
                throw e;
            }
        } catch (IOException e) {
            log.error("Falha ao gravar anexo da Review {} (card {})", sessionId, taskId, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Não foi possível gravar o arquivo. Tente de novo.");
        }
    }

    private ShowcaseTaskFile find(String sessionId, String fileId) {
        return fileRepository.findById(fileId)
                .filter(f -> sessionId.equals(f.getSessionId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Arquivo não encontrado."));
    }

    /** Dentro de uma transação, só apaga o conteúdo depois do commit (rollback não pode perder arquivo). */
    private void deleteContentAfterCommit(String key) {
        Runnable delete = () -> {
            try {
                storage.delete(key);
            } catch (IOException e) {
                log.warn("Não foi possível apagar o conteúdo {} do armazenamento", key, e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delete.run();
                }
            });
        } else {
            delete.run();
        }
    }

    /** PNG, JPEG ou PDF pela assinatura dos primeiros bytes; qualquer outra coisa devolve null. */
    static String detectContentType(byte[] head) {
        if (head.length >= 8
                && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                && head[4] == 0x0D && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A) {
            return PNG;
        }
        if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return JPEG;
        }
        if (head.length >= 5 && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F' && head[4] == '-') {
            return PDF;
        }
        return null;
    }

    static String extensionFor(String contentType) {
        return switch (contentType) {
            case PNG -> ".png";
            case JPEG -> ".jpg";
            default -> ".pdf";
        };
    }

    /** Nome só para exibição/download: sem caminho, sem caractere de controle, tamanho limitado e extensão coerente com o tipo. */
    static String sanitizeName(String original, String contentType) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").trim();

        String ext = extensionFor(contentType);
        String lower = name.toLowerCase(Locale.ROOT);
        boolean extOk = switch (contentType) {
            case PNG -> lower.endsWith(".png");
            case JPEG -> lower.endsWith(".jpg") || lower.endsWith(".jpeg");
            default -> lower.endsWith(".pdf");
        };
        if (name.isEmpty()) {
            return "anexo" + ext;
        }
        if (!extOk) {
            int dot = name.lastIndexOf('.');
            name = (dot > 0 ? name.substring(0, dot) : name) + ext;
        }
        if (name.length() > MAX_NAME_LENGTH) {
            String keepExt = name.substring(name.lastIndexOf('.'));
            name = name.substring(0, MAX_NAME_LENGTH - keepExt.length()) + keepExt;
        }
        return name;
    }
}
