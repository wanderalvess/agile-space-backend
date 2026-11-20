package com.agilespace.backend.controller;

import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.UserPhotoService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class UserPhotoControllerTest {

    @Mock private UserPhotoService service;
    @InjectMocks private UserPhotoController controller;
    private HttpServletRequest request;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn("ana");
    }

    @Test
    public void uploadUsaSoOUsuarioDoToken() {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1});
        when(service.upload("ana", file)).thenReturn("/api/users/ana/photo?v=7");

        ResponseEntity<Map<String, String>> res = controller.upload(file, request);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals("/api/users/ana/photo?v=7", res.getBody().get("photoUrl"));
    }

    @Test
    public void semSessaoE401() {
        when(request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)).thenReturn(null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.remove(request));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
        assertThrows(ResponseStatusException.class, () -> controller.get("ana", null, request));
        verifyNoInteractions(service);
    }

    @Test
    public void deleteE204() {
        assertEquals(HttpStatus.NO_CONTENT, controller.remove(request).getStatusCode());
        verify(service).remove("ana");
    }

    @Test
    public void getDevolveBytesTipoCacheEEtag_deQualquerPessoa() {
        Resource bytes = new ByteArrayResource(new byte[]{1, 2, 3});
        when(service.open("bia")).thenReturn(new UserPhotoService.PhotoContent(bytes, "image/webp", 42L));

        ResponseEntity<Resource> res = controller.get("bia", null, request);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals("image/webp", res.getHeaders().getContentType().toString());
        assertEquals("\"42\"", res.getHeaders().getETag());
        String cache = res.getHeaders().getCacheControl();
        assertTrue(cache.contains("private") && cache.contains("max-age=86400"), cache);
        assertSame(bytes, res.getBody());
    }

    @Test
    public void getComEtagIgualE304() {
        when(service.open("bia")).thenReturn(new UserPhotoService.PhotoContent(new ByteArrayResource(new byte[]{1}), "image/png", 42L));
        ResponseEntity<Resource> res = controller.get("bia", "\"42\"", request);
        assertEquals(HttpStatus.NOT_MODIFIED, res.getStatusCode());
        assertNull(res.getBody());
        assertNotNull(res.getHeaders().getFirst(HttpHeaders.ETAG));
    }

    @Test
    public void getSemFotoE404() {
        when(service.open("bia")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> controller.get("bia", null, request)).getStatusCode());
    }
}
