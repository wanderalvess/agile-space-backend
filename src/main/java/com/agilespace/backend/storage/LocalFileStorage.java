package com.agilespace.backend.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Component
public class LocalFileStorage implements FileStorage {

    private final Path root;

    public LocalFileStorage(@Value("${app.uploads.dir:./data/uploads}") String dir) throws IOException {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    @Override
    public long store(String key, InputStream content) throws IOException {
        Path target = resolve(key);
        // Grava num temporário e move: um upload interrompido nunca deixa arquivo pela metade.
        Path tmp = Files.createTempFile(root, "upload-", ".part");
        try {
            long written = Files.copy(content, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            return written;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Override
    public Resource load(String key) throws IOException {
        Path file = resolve(key);
        if (!Files.isRegularFile(file)) {
            throw new IOException("Arquivo não encontrado no armazenamento");
        }
        return new FileSystemResource(file);
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    /** Chaves vêm do servidor, mas a checagem existe para que um bug nunca vire path traversal. */
    private Path resolve(String key) {
        if (key == null || key.isBlank() || key.contains("/") || key.contains("\\") || key.contains("..")) {
            throw new IllegalArgumentException("Chave de arquivo inválida");
        }
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalArgumentException("Chave de arquivo inválida");
        }
        return resolved;
    }
}
