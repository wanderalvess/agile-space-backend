package com.agilespace.backend.controller;

import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.AuthResponseDto;
import com.agilespace.backend.dto.OnboardingRosterDto;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.AuthService;
import com.agilespace.backend.service.OnboardingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Superfície do onboarding "reconhecer antes de perguntar".
 *
 * Tudo aqui exige sessão (o JwtAuthenticationFilter já barra o resto) e devolve
 * roster sem e-mail completo — ver OnboardingRosterDto.
 */
@RestController
@RequestMapping("/api/onboarding")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class OnboardingController {

    private final OnboardingService onboardingService;
    private final AuthService authService;
    private final UserRepository userRepository;

    private User currentUser(HttpServletRequest request) {
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida ou expirada"));
    }

    /** "É você?" — linhas do roster com o nome da conta logada, sem vínculo ainda. */
    @GetMapping("/suggestions")
    public ResponseEntity<List<OnboardingRosterDto>> suggestions(HttpServletRequest request) {
        return ResponseEntity.ok(onboardingService.suggestionsFor(currentUser(request)));
    }

    /** Busca por time ou por pessoa. */
    @GetMapping("/search")
    public ResponseEntity<List<OnboardingRosterDto>> search(
            @RequestParam(name = "q", required = false, defaultValue = "") String query,
            HttpServletRequest request) {
        return ResponseEntity.ok(onboardingService.search(query, currentUser(request)));
    }

    /** Roster completo de um projeto, pra pessoa se achar na lista. */
    @GetMapping("/roster/{projectKey}")
    public ResponseEntity<OnboardingRosterDto> roster(@PathVariable String projectKey, HttpServletRequest request) {
        return ResponseEntity.ok(onboardingService.roster(projectKey, currentUser(request)));
    }

    /**
     * "Sou eu": liga a conta à linha do roster e já move o usuário pro projeto,
     * devolvendo a sessão atualizada (mesmo contrato de /projects/{key}/join).
     */
    @PostMapping("/claim/{memberId}")
    public ResponseEntity<AuthResponseDto> claim(@PathVariable String memberId, HttpServletRequest request) {
        User user = currentUser(request);
        String projectKey = onboardingService.claim(memberId, user);
        return ResponseEntity.ok(authService.switchActiveProject(user.getId(), projectKey));
    }
}
