package com.agilespace.backend.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LocalFileStorage")
class LocalFileStorageTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Grava, lê e apaga; apagar chave inexistente não é erro")
    void shouldStoreLoadAndDelete() throws Exception {
        LocalFileStorage storage = new LocalFileStorage(tempDir.toString());

        assertEquals(5, storage.store("a1.png", new ByteArrayInputStream("12345".getBytes())));
        assertEquals("12345", new String(storage.load("a1.png").getInputStream().readAllBytes()));

        storage.delete("a1.png");
        assertThrows(IOException.class, () -> storage.load("a1.png"));
        assertDoesNotThrow(() -> storage.delete("a1.png"));
    }

    @Test
    @DisplayName("Não deixa arquivo temporário para trás depois de gravar")
    void shouldNotLeaveTempFiles() throws Exception {
        LocalFileStorage storage = new LocalFileStorage(tempDir.toString());
        storage.store("a1.png", new ByteArrayInputStream("x".getBytes()));

        try (var files = Files.list(tempDir)) {
            assertEquals(1, files.count());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../fora.png", "..\\fora.png", "sub/dir.png", "..", "", "   "})
    @DisplayName("Chaves com caminho ou traversal são recusadas")
    void shouldRejectUnsafeKeys(String key) throws Exception {
        LocalFileStorage storage = new LocalFileStorage(tempDir.toString());

        assertThrows(IllegalArgumentException.class, () -> storage.store(key, new ByteArrayInputStream(new byte[]{1})));
        assertThrows(IllegalArgumentException.class, () -> storage.load(key));
        assertThrows(IllegalArgumentException.class, () -> storage.delete(key));
    }
}
