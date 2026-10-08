package com.agilespace.backend.service;

import com.agilespace.backend.domain.ShowcaseTask;
import com.agilespace.backend.domain.ShowcaseTaskFile;
import com.agilespace.backend.repository.ShowcaseSessionRepository;
import com.agilespace.backend.repository.ShowcaseTaskFileRepository;
import com.agilespace.backend.repository.ShowcaseTaskRepository;
import com.agilespace.backend.storage.LocalFileStorage;
import com.agilespace.backend.websocket.ShowcaseWebSocketHandler;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ShowcaseTaskFileService - anexos dos cards")
class ShowcaseTaskFileServiceTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};
    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n".getBytes(StandardCharsets.US_ASCII);

    @Mock
    private ShowcaseTaskFileRepository fileRepository;
    @Mock
    private ShowcaseSessionRepository sessionRepository;
    @Mock
    private ShowcaseTaskRepository taskRepository;
    @Mock
    private ShowcaseWebSocketHandler webSocketHandler;

    @TempDir
    Path tempDir;

    private LocalFileStorage storage;
    private ShowcaseTaskFileService service;

    @BeforeEach
    void setUp() throws Exception {
        storage = new LocalFileStorage(tempDir.toString());
        service = new ShowcaseTaskFileService(fileRepository, sessionRepository, taskRepository, storage, webSocketHandler);
    }

    private void cardExists() {
        when(sessionRepository.existsById("s1")).thenReturn(true);
        when(taskRepository.findById("t1")).thenReturn(Optional.of(ShowcaseTask.builder().id("t1").sessionId("s1").build()));
        when(fileRepository.countBySessionIdAndTaskId("s1", "t1")).thenReturn(0L);
        lenient().when(fileRepository.save(any(ShowcaseTaskFile.class))).thenAnswer(i -> i.getArgument(0));
    }

    private long filesOnDisk() throws Exception {
        try (Stream<Path> s = Files.list(tempDir)) {
            return s.count();
        }
    }

    @Test
    @DisplayName("Grava PNG, devolve os metadados e avisa a sala para atualizar")
    void shouldStorePng() throws Exception {
        cardExists();

        ShowcaseTaskFile saved = service.upload("s1", "t1", new MockMultipartFile("file", "print.png", "image/png", PNG), "user-1");

        assertEquals("print.png", saved.getName());
        assertEquals("image/png", saved.getContentType());
        assertEquals(PNG.length, saved.getSize());
        assertEquals("user-1", saved.getUploadedBy());
        assertEquals("t1", saved.getTaskId());
        assertTrue(saved.getStorageKey().endsWith(".png"));
        assertNotEquals("print.png", saved.getStorageKey(), "o nome no disco nunca é o enviado pelo usuário");
        assertArrayEquals(PNG, storage.load(saved.getStorageKey()).getInputStream().readAllBytes());
        verify(webSocketHandler).broadcastRefresh("s1");
    }

    @Test
    @DisplayName("Aceita JPEG e PDF")
    void shouldAcceptJpegAndPdf() {
        cardExists();

        ShowcaseTaskFile jpeg = service.upload("s1", "t1", new MockMultipartFile("file", "foto.jpeg", "image/jpeg", JPEG), "u");
        ShowcaseTaskFile pdf = service.upload("s1", "t1", new MockMultipartFile("file", "laudo.pdf", "application/pdf", PDF), "u");

        assertEquals("image/jpeg", jpeg.getContentType());
        assertEquals("foto.jpeg", jpeg.getName());
        assertEquals("application/pdf", pdf.getContentType());
    }

    @Test
    @DisplayName("Decide o tipo pelo conteúdo: HTML renomeado para .png é recusado")
    void shouldRejectDisguisedFile() throws Exception {
        cardExists();
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upload("s1", "t1", new MockMultipartFile("file", "print.png", "image/png", html), "u"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals(0, filesOnDisk());
        verify(fileRepository, never()).save(any());
        verify(webSocketHandler, never()).broadcastRefresh(any());
    }

    @Test
    @DisplayName("Recusa o sexto arquivo do mesmo card")
    void shouldRejectBeyondFiveFiles() throws Exception {
        cardExists();
        when(fileRepository.countBySessionIdAndTaskId("s1", "t1")).thenReturn(5L);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upload("s1", "t1", new MockMultipartFile("file", "a.png", "image/png", PNG), "u"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("5"));
        assertEquals(0, filesOnDisk());
    }

    @Test
    @DisplayName("Recusa arquivo acima de 10 MB e arquivo vazio")
    void shouldRejectTooLargeAndEmpty() {
        byte[] big = new byte[(int) ShowcaseTaskFileService.MAX_FILE_BYTES + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);

        ResponseStatusException tooBig = assertThrows(ResponseStatusException.class,
                () -> service.upload("s1", "t1", new MockMultipartFile("file", "big.png", "image/png", big), "u"));
        ResponseStatusException empty = assertThrows(ResponseStatusException.class,
                () -> service.upload("s1", "t1", new MockMultipartFile("file", "a.png", "image/png", new byte[0]), "u"));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, tooBig.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, empty.getStatusCode());
    }

    @Test
    @DisplayName("404 quando a Review ou o card não existem (ou o card é de outra Review)")
    void shouldReturnNotFoundForUnknownCard() {
        when(sessionRepository.existsById("s1")).thenReturn(true);
        when(taskRepository.findById("t-outra")).thenReturn(Optional.of(ShowcaseTask.builder().id("t-outra").sessionId("s2").build()));
        when(taskRepository.findById("t-nada")).thenReturn(Optional.empty());
        when(sessionRepository.existsById("s-nada")).thenReturn(false);
        MockMultipartFile png = new MockMultipartFile("file", "a.png", "image/png", PNG);

        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, () -> service.upload("s1", "t-outra", png, "u")).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, () -> service.upload("s1", "t-nada", png, "u")).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, () -> service.upload("s-nada", "t1", png, "u")).getStatusCode());
    }

    @Test
    @DisplayName("Nome exibido: sem caminho, sem caractere de controle, com extensão coerente e tamanho limitado")
    void shouldSanitizeNames() {
        assertEquals("evil.png", ShowcaseTaskFileService.sanitizeName("..\\..\\pasta/evil.png", "image/png"));
        assertEquals("foto.png", ShowcaseTaskFileService.sanitizeName("foto.html", "image/png"));
        assertEquals("sem-extensao.pdf", ShowcaseTaskFileService.sanitizeName("sem-extensao", "application/pdf"));
        assertEquals("anexo.jpg", ShowcaseTaskFileService.sanitizeName("   ", "image/jpeg"));
        assertEquals("anexo.png", ShowcaseTaskFileService.sanitizeName(null, "image/png"));
        assertEquals("ab.png", ShowcaseTaskFileService.sanitizeName("a\u0000b.png", "image/png"));
        String longName = "x".repeat(300) + ".pdf";
        String cut = ShowcaseTaskFileService.sanitizeName(longName, "application/pdf");
        assertEquals(120, cut.length());
        assertTrue(cut.endsWith(".pdf"));
    }

    @Test
    @DisplayName("Abre o arquivo da Review certa e 404 para outra Review ou conteúdo sumido")
    void shouldOpenOnlyFromOwnSession() throws Exception {
        cardExists();
        ShowcaseTaskFile saved = service.upload("s1", "t1", new MockMultipartFile("file", "a.png", "image/png", PNG), "u");
        when(fileRepository.findById(saved.getId())).thenReturn(Optional.of(saved));

        assertArrayEquals(PNG, service.open("s1", saved.getId()).resource().getInputStream().readAllBytes());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, () -> service.open("outra", saved.getId())).getStatusCode());

        storage.delete(saved.getStorageKey());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, () -> service.open("s1", saved.getId())).getStatusCode());
    }

    @Test
    @DisplayName("Apagar remove a linha, o conteúdo do disco e avisa a sala")
    void shouldDelete() throws Exception {
        cardExists();
        ShowcaseTaskFile saved = service.upload("s1", "t1", new MockMultipartFile("file", "a.png", "image/png", PNG), "u");
        when(fileRepository.findById(saved.getId())).thenReturn(Optional.of(saved));
        assertEquals(1, filesOnDisk());

        service.delete("s1", saved.getId());

        verify(fileRepository).delete(saved);
        assertEquals(0, filesOnDisk());
        verify(webSocketHandler, times(2)).broadcastRefresh("s1");
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, () -> service.delete("outra", saved.getId())).getStatusCode());
    }

    @Test
    @DisplayName("Anexos de cards que saíram da Review são removidos; os dos cards que ficam, não")
    void shouldRemoveOrphans() throws Exception {
        cardExists();
        ShowcaseTaskFile keep = service.upload("s1", "t1", new MockMultipartFile("file", "a.png", "image/png", PNG), "u");
        ShowcaseTaskFile gone = ShowcaseTaskFile.builder().id("f2").sessionId("s1").taskId("t-removida").storageKey("sumiu.png").build();
        when(fileRepository.findBySessionIdOrderByCreatedAtAsc("s1")).thenReturn(List.of(keep, gone));

        service.removeOrphans("s1", Set.of("t1"));

        verify(fileRepository).delete(gone);
        verify(fileRepository, never()).delete(keep);
        assertEquals(1, filesOnDisk(), "o conteúdo do card que ficou continua no disco");
    }
}
