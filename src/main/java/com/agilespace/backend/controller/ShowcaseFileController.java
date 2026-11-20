package com.agilespace.backend.controller;

import com.agilespace.backend.domain.ShowcaseTaskFile;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.ShowcaseTaskFileService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * Anexos dos cards da Review. Mesmo nível de acesso do restante da Review (usuário autenticado
 * com o link da sessão); o JWT é exigido pelo JwtAuthenticationFilter em todo /api/**.
 */
@RestController
@RequestMapping("/api/showcase-sessions/{sessionId}")
@RequiredArgsConstructor
public class ShowcaseFileController {

    private final ShowcaseTaskFileService service;

    @PostMapping(path = "/tasks/{taskId}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ShowcaseTaskFile> upload(
            @PathVariable String sessionId,
            @PathVariable String taskId,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest request) {
        String callerId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        return ResponseEntity.ok(service.upload(sessionId, taskId, file, callerId));
    }

    @GetMapping("/files/{fileId}")
    public ResponseEntity<Resource> download(@PathVariable String sessionId, @PathVariable String fileId) {
        ShowcaseTaskFileService.FileContent content = service.open(sessionId, fileId);
        ShowcaseTaskFile file = content.file();
        return ResponseEntity.ok()
                // O tipo vem do que foi detectado no upload, nunca do que o cliente declarou.
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .contentLength(file.getSize())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(file.getName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .cacheControl(CacheControl.noCache().cachePrivate())
                .body(content.resource());
    }

    @DeleteMapping("/files/{fileId}")
    public ResponseEntity<Void> delete(@PathVariable String sessionId, @PathVariable String fileId) {
        service.delete(sessionId, fileId);
        return ResponseEntity.noContent().build();
    }
}
