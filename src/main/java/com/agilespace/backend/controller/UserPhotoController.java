package com.agilespace.backend.controller;

import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.UserPhotoService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Map;

/** Foto de perfil: cada pessoa troca/remove só a própria; qualquer usuário autenticado vê a de qualquer colega. */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserPhotoController {

    private final UserPhotoService service;

    @PostMapping(value = "/me/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> upload(@RequestPart("file") MultipartFile file, HttpServletRequest request) {
        String userId = requireUser(request);
        return ResponseEntity.ok(Map.of("photoUrl", service.upload(userId, file)));
    }

    @DeleteMapping("/me/photo")
    public ResponseEntity<Void> remove(HttpServletRequest request) {
        service.remove(requireUser(request));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/photo")
    public ResponseEntity<Resource> get(@PathVariable String id,
                                        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
                                        HttpServletRequest request) {
        requireUser(request);
        UserPhotoService.PhotoContent photo = service.open(id);
        String etag = "\"" + photo.version() + "\"";
        CacheControl cache = CacheControl.maxAge(Duration.ofDays(1)).cachePrivate();
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(cache).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.contentType()))
                .eTag(etag)
                .cacheControl(cache)
                .header("X-Content-Type-Options", "nosniff")
                .body(photo.resource());
    }

    private static String requireUser(HttpServletRequest request) {
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida.");
        }
        return userId;
    }
}
