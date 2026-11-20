package com.agilespace.backend.service;

import com.agilespace.backend.domain.ShowcaseJqlQuery;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.ShowcaseJqlQueryDto;
import com.agilespace.backend.repository.ShowcaseJqlQueryRepository;
import com.agilespace.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Consultas JQL da Review salvas por squad (V81, docs/ceremony-review-api.md). O servidor SÓ guarda o texto: nunca executa
 * JQL (a consulta ao Jira continua no navegador). A autorização de leitura da squad é feita pelo controller
 * ({@code SquadAccessService.requireSquadReadAccess}); aqui ficam privacidade, limites e quem apaga.
 */
@Service
@RequiredArgsConstructor
public class ShowcaseJqlService {

    static final int MAX_NAME = 120;
    static final int MAX_JQL = 4000;
    static final int MAX_LIST = 200;
    static final int MAX_PER_OWNER_PER_SQUAD = 30;

    private final ShowcaseJqlQueryRepository repository;
    private final UserRepository userRepository;
    private final ShowcaseAccessPolicy accessPolicy;

    static String normalizeSquad(String squadId) {
        if (squadId == null || squadId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "squadId é obrigatório.");
        }
        return squadId.trim().toUpperCase(Locale.ROOT);
    }

    @Transactional(readOnly = true)
    public List<ShowcaseJqlQueryDto> list(String squadId, String callerId) {
        String squad = normalizeSquad(squadId);
        List<ShowcaseJqlQuery> rows = repository.findVisible(squad, callerId, PageRequest.of(0, MAX_LIST));
        return toDtos(rows, callerId);
    }

    @Transactional
    public ShowcaseJqlQueryDto create(String squadId, String name, String jql, String visibility, String callerId) {
        String squad = normalizeSquad(squadId);
        String cleanName = name == null ? "" : name.trim();
        String cleanJql = jql == null ? "" : jql.trim();
        if (cleanName.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dê um nome à consulta.");
        }
        if (cleanName.length() > MAX_NAME) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O nome da consulta tem no máximo " + MAX_NAME + " caracteres.");
        }
        if (cleanJql.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A consulta JQL não pode ficar vazia.");
        }
        if (cleanJql.length() > MAX_JQL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A consulta JQL tem no máximo " + MAX_JQL + " caracteres.");
        }
        String vis = parseVisibility(visibility);
        if (repository.existsBySquadIdAndCreatedByAndNameIgnoreCase(squad, callerId, cleanName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Você já tem uma consulta com esse nome nesta squad.");
        }
        if (repository.countBySquadIdAndCreatedBy(squad, callerId) >= MAX_PER_OWNER_PER_SQUAD) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Você chegou ao limite de " + MAX_PER_OWNER_PER_SQUAD + " consultas salvas nesta squad. Apague alguma antes.");
        }
        ShowcaseJqlQuery saved = repository.save(ShowcaseJqlQuery.builder()
                .id(UUID.randomUUID().toString()).squadId(squad).name(cleanName).jql(cleanJql).visibility(vis)
                .createdBy(callerId).createdAt(LocalDateTime.now()).build());
        return toDtos(List.of(saved), callerId).get(0);
    }

    /** Criador; em consulta SQUAD também a liderança da squad (vínculo) e o ADMIN. PRIVATE de outra pessoa: 404 (ADMIN pode). */
    @Transactional
    public void delete(String id, String callerId) {
        ShowcaseJqlQuery q = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Consulta não encontrada."));
        boolean mine = callerId != null && callerId.equals(q.getCreatedBy());
        if (!mine) {
            boolean isPrivate = ShowcaseJqlQuery.VISIBILITY_PRIVATE.equals(q.getVisibility());
            if (isPrivate && !isAdmin(callerId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Consulta não encontrada.");
            }
            if (!accessPolicy.isAdminOrSquadLeader(q.getSquadId(), callerId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Só quem criou a consulta, a liderança da squad ou um administrador pode apagá-la.");
            }
        }
        repository.delete(q);
    }

    private boolean isAdmin(String userId) {
        return userId != null && userRepository.findById(userId).map(u -> "ADMIN".equalsIgnoreCase(u.getRole())).orElse(false);
    }

    private static String parseVisibility(String visibility) {
        if (visibility == null || visibility.isBlank()) {
            return ShowcaseJqlQuery.VISIBILITY_PRIVATE;
        }
        String v = visibility.trim().toUpperCase(Locale.ROOT);
        if (!Set.of(ShowcaseJqlQuery.VISIBILITY_SQUAD, ShowcaseJqlQuery.VISIBILITY_PRIVATE).contains(v)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "visibility deve ser SQUAD ou PRIVATE.");
        }
        return v;
    }

    private List<ShowcaseJqlQueryDto> toDtos(List<ShowcaseJqlQuery> rows, String callerId) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<String> authorIds = rows.stream().map(ShowcaseJqlQuery::getCreatedBy).collect(Collectors.toSet());
        Map<String, String> names = new HashMap<>();
        for (User u : userRepository.findAllById(authorIds)) {
            names.put(u.getId(), u.getName());
        }
        // Uma checagem de liderança por squad por chamada (as linhas de uma listagem são todas da mesma squad).
        Map<String, Boolean> leaderBySquad = new HashMap<>();
        return rows.stream().map(q -> {
            boolean mine = callerId != null && callerId.equals(q.getCreatedBy());
            boolean canDelete = mine;
            if (!mine && ShowcaseJqlQuery.VISIBILITY_SQUAD.equals(q.getVisibility())) {
                canDelete = leaderBySquad.computeIfAbsent(q.getSquadId(), s -> accessPolicy.isAdminOrSquadLeader(s, callerId));
            }
            return new ShowcaseJqlQueryDto(q.getId(), q.getSquadId(), q.getName(), q.getJql(), q.getVisibility(),
                    q.getCreatedBy(), names.get(q.getCreatedBy()), q.getCreatedAt(), mine, canDelete);
        }).toList();
    }
}
