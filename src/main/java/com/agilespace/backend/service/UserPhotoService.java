package com.agilespace.backend.service;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;

/**
 * Foto de perfil da própria pessoa. O tipo é decidido pelos primeiros bytes (JPEG, PNG ou WebP), nunca pelo
 * Content-Type nem pelo nome que o cliente mandou. O frontend já reduz a imagem (~256x256); o teto de 1 MB é a rede de segurança.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPhotoService {

    public static final long MAX_BYTES = 1024L * 1024;

    private final UserRepository userRepository;
    private final FileStorage storage;

    public record PhotoContent(Resource resource, String contentType, long version) {
    }

    /** URL relativa da foto (com versão para furar o cache ao trocar) ou null quando a pessoa não tem foto. */
    public static String photoUrl(User user) {
        if (user == null || user.getPhotoKey() == null || user.getPhotoKey().isBlank()) {
            return null;
        }
        long version = user.getPhotoUpdatedAt() != null ? user.getPhotoUpdatedAt().toEpochMilli() : 0L;
        return "/api/users/" + user.getId() + "/photo?v=" + version;
    }

    @Transactional
    public String upload(String userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw unprocessable("Selecione uma imagem para a foto de perfil.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw unprocessable("A foto excede o limite de 1 MB.");
        }
        byte[] bytes;
        try (InputStream in = file.getInputStream()) {
            bytes = in.readNBytes((int) MAX_BYTES + 1);
        } catch (IOException e) {
            throw unprocessable("Não foi possível ler a imagem enviada.");
        }
        if (bytes.length == 0) {
            throw unprocessable("Selecione uma imagem para a foto de perfil.");
        }
        if (bytes.length > MAX_BYTES) {
            throw unprocessable("A foto excede o limite de 1 MB.");
        }
        String contentType = detectContentType(bytes);
        if (contentType == null) {
            throw unprocessable("Formato não aceito. Envie uma imagem JPEG, PNG ou WebP.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado."));
        String oldKey = user.getPhotoKey();
        Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
        String newKey = "avatar-" + userId + "-" + now.toEpochMilli() + extensionFor(contentType);
        try {
            storage.store(newKey, new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            log.error("Falha ao gravar a foto de perfil de {}", userId, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Não foi possível salvar a foto. Tente novamente.");
        }
        user.setPhotoKey(newKey);
        user.setPhotoUpdatedAt(now);
        userRepository.save(user);
        if (oldKey != null && !oldKey.equals(newKey)) {
            deleteQuietly(oldKey);
        }
        return photoUrl(user);
    }

    /** Idempotente: sem foto não faz nada. */
    @Transactional
    public void remove(String userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getPhotoKey() == null) {
            return;
        }
        String oldKey = user.getPhotoKey();
        user.setPhotoKey(null);
        user.setPhotoUpdatedAt(null);
        userRepository.save(user);
        deleteQuietly(oldKey);
    }

    /** Foto de qualquer pessoa (a autenticação é checada no controller); 404 se não houver. */
    @Transactional(readOnly = true)
    public PhotoContent open(String userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getPhotoKey() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Esta pessoa não tem foto de perfil.");
        }
        try {
            Resource resource = storage.load(user.getPhotoKey());
            long version = user.getPhotoUpdatedAt() != null ? user.getPhotoUpdatedAt().toEpochMilli() : 0L;
            return new PhotoContent(resource, contentTypeForKey(user.getPhotoKey()), version);
        } catch (IOException e) {
            log.warn("Foto de {} sem conteúdo no armazenamento", userId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Esta pessoa não tem foto de perfil.");
        }
    }

    // ------------------------------------------------------------------ internos

    static String detectContentType(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "image/png";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            default -> ".webp";
        };
    }

    private static String contentTypeForKey(String key) {
        if (key.endsWith(".png")) return "image/png";
        if (key.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (IOException | RuntimeException e) {
            log.warn("Não consegui apagar a foto antiga {}", key, e);
        }
    }

    private static ResponseStatusException unprocessable(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
