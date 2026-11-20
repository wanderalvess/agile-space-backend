package com.agilespace.backend.service;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.storage.LocalFileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserPhotoService - foto de perfil")
class UserPhotoServiceTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};

    @Mock
    private UserRepository userRepository;

    @TempDir
    Path tempDir;

    private UserPhotoService service;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        service = new UserPhotoService(userRepository, new LocalFileStorage(tempDir.toString()));
        user = User.builder().id("u1").email("a@x.com").name("Ana").build();
        lenient().when(userRepository.findById("u1")).thenReturn(Optional.of(user));
    }

    private static MockMultipartFile file(byte[] bytes, String contentType) {
        return new MockMultipartFile("file", "foto.bin", contentType, bytes);
    }

    private long filesOnDisk() throws Exception {
        try (Stream<Path> s = Files.list(tempDir)) {
            return s.count();
        }
    }

    private static void assertUnprocessable(Runnable r, String fragment) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, r::run);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        assertTrue(ex.getReason().contains(fragment), ex.getReason());
    }

    @Test
    void uploadPng_gravaArquivoEAtualizaColunas_devolveUrlVersionada() throws Exception {
        String url = service.upload("u1", file(PNG, "image/png"));

        assertTrue(user.getPhotoKey().startsWith("avatar-u1-"));
        assertTrue(user.getPhotoKey().endsWith(".png"));
        assertFalse(user.getPhotoKey().contains("/"));
        assertNotNull(user.getPhotoUpdatedAt());
        assertEquals("/api/users/u1/photo?v=" + user.getPhotoUpdatedAt().toEpochMilli(), url);
        assertEquals(1, filesOnDisk());
        verify(userRepository).save(user);
    }

    @Test
    void jpegEWebpTambemPassam() {
        service.upload("u1", file(JPEG, "image/jpeg"));
        assertTrue(user.getPhotoKey().endsWith(".jpg"));
        service.upload("u1", file(WEBP, "image/webp"));
        assertTrue(user.getPhotoKey().endsWith(".webp"));
    }

    @Test
    void tipoPelosBytes_ignoraContentTypeDeclarado() {
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        assertUnprocessable(() -> service.upload("u1", file(html, "image/png")), "JPEG, PNG ou WebP");
        verify(userRepository, never()).save(any());
    }

    @Test
    void gifERiffQueNaoEWebpSaoRecusados() {
        assertUnprocessable(() -> service.upload("u1", file("GIF89a....".getBytes(StandardCharsets.US_ASCII), "image/gif")), "JPEG, PNG ou WebP");
        byte[] wav = {'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'A', 'V', 'E'};
        assertUnprocessable(() -> service.upload("u1", file(wav, "image/webp")), "JPEG, PNG ou WebP");
    }

    @Test
    void vazio_e422() {
        assertUnprocessable(() -> service.upload("u1", file(new byte[0], "image/png")), "Selecione");
        assertUnprocessable(() -> service.upload("u1", null), "Selecione");
    }

    @Test
    void grandeDemais_e422_eNaoGravaNada() throws Exception {
        byte[] big = new byte[(int) UserPhotoService.MAX_BYTES + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        assertUnprocessable(() -> service.upload("u1", file(big, "image/png")), "1 MB");
        assertEquals(0, filesOnDisk());
        verify(userRepository, never()).save(any());
    }

    @Test
    void exatamenteUmMegabyte_passa() {
        byte[] max = new byte[(int) UserPhotoService.MAX_BYTES];
        System.arraycopy(PNG, 0, max, 0, PNG.length);
        assertDoesNotThrow(() -> service.upload("u1", file(max, "image/png")));
    }

    @Test
    void trocarFoto_apagaAAnterior() throws Exception {
        service.upload("u1", file(PNG, "image/png"));
        String first = user.getPhotoKey();
        Thread.sleep(5); // a chave leva epochMillis: garante outra chave
        service.upload("u1", file(JPEG, "image/jpeg"));

        assertNotEquals(first, user.getPhotoKey());
        assertFalse(Files.exists(tempDir.resolve(first)), "arquivo anterior apagado");
        assertTrue(Files.exists(tempDir.resolve(user.getPhotoKey())));
        assertEquals(1, filesOnDisk());
    }

    @Test
    void remove_apagaArquivoEZeraColunas_eEIdempotente() throws Exception {
        service.upload("u1", file(PNG, "image/png"));
        service.remove("u1");

        assertNull(user.getPhotoKey());
        assertNull(user.getPhotoUpdatedAt());
        assertEquals(0, filesOnDisk());
        assertDoesNotThrow(() -> service.remove("u1"));
        assertDoesNotThrow(() -> service.remove("inexistente"));
    }

    @Test
    void open_devolveBytesETipo_e404SemFoto() throws Exception {
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.open("u1")).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.open("ghost")).getStatusCode());

        service.upload("u1", file(PNG, "image/png"));
        UserPhotoService.PhotoContent content = service.open("u1");
        assertEquals("image/png", content.contentType());
        assertEquals(user.getPhotoUpdatedAt().toEpochMilli(), content.version());
        assertArrayEquals(PNG, content.resource().getContentAsByteArray());
    }

    @Test
    void open_arquivoSumidoDoArmazenamento_e404() throws Exception {
        service.upload("u1", file(PNG, "image/png"));
        Files.delete(tempDir.resolve(user.getPhotoKey()));
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.open("u1")).getStatusCode());
    }

    @Test
    void photoUrl_nullSemFoto() {
        assertNull(UserPhotoService.photoUrl(user));
        assertNull(UserPhotoService.photoUrl(null));
    }
}
