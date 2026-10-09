package com.agilespace.backend.security;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Estado "vivo" da conta (ativa? qual papel de sistema?) para o filtro JWT.
 *
 * O JWT carrega o papel de quando foi emitido e vale 24h. Sem esta checagem, quem é desativado ou
 * rebaixado de ADMIN continua com o acesso antigo até o token expirar. O resultado fica em cache por
 * poucos segundos para não custar uma consulta por requisição; {@link #evict(String)} é chamado quando
 * o perfil é salvo, então a mudança feita pelo painel vale na hora no mesmo servidor.
 *
 * Usuário que não existe na tabela (token de outra origem) devolve vazio e o filtro mantém o que veio
 * no token — nunca bloqueia por falta de linha, só por conta explicitamente inativa.
 */
@Component
public class UserSessionGuard {

    static final long TTL_MILLIS = 30_000L;
    static final int MAX_ENTRIES = 5_000;

    public record State(boolean active, String role) {
    }

    private record Entry(State state, long loadedAt) {
    }

    private final UserRepository userRepository;
    private final ConcurrentMap<String, Entry> cache = new ConcurrentHashMap<>();

    public UserSessionGuard(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Optional<State> lookup(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        Entry cached = cache.get(userId);
        if (cached != null && now - cached.loadedAt() < TTL_MILLIS) {
            return Optional.ofNullable(cached.state());
        }
        Optional<User> user = userRepository.findById(userId);
        State state = user.map(u -> new State(u.isActive(), u.getRole())).orElse(null);
        if (cache.size() >= MAX_ENTRIES) {
            cache.clear();
        }
        cache.put(userId, new Entry(state, now));
        return Optional.ofNullable(state);
    }

    public void evict(String userId) {
        if (userId != null) {
            cache.remove(userId);
        }
    }
}
