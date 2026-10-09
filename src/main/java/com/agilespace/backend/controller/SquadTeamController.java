package com.agilespace.backend.controller;

import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.SquadTeamService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Pessoas do time: adicionar, trocar o papel e remover (Agile Master/People Lead da equipe ou admin). */
@RestController
@RequestMapping("/api/squads/{squadId}/team")
@RequiredArgsConstructor
public class SquadTeamController {

    private final SquadTeamService teamService;
    private final UserRepository userRepository;

    public record RoleRequest(String roleName) {}

    private User caller(HttpServletRequest request) {
        String id = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        User u = id != null ? userRepository.findById(id).orElse(null) : null;
        if (u == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida.");
        }
        return u;
    }

    /** A tela usa para decidir se mostra os botões (o servidor valida de novo em cada ação). */
    @GetMapping("/can-manage")
    public ResponseEntity<java.util.Map<String, Boolean>> canManage(@PathVariable String squadId, HttpServletRequest request) {
        return ResponseEntity.ok(java.util.Map.of("canManage", teamService.canManageTeam(squadId, caller(request))));
    }

    @GetMapping("/candidates")
    public ResponseEntity<List<SquadTeamService.Candidate>> candidates(
            @PathVariable String squadId, @RequestParam String q, HttpServletRequest request) {
        return ResponseEntity.ok(teamService.searchCandidates(squadId, caller(request), q));
    }

    @PostMapping("/members")
    public ResponseEntity<SquadMember> add(
            @PathVariable String squadId, @RequestBody SquadTeamService.AddRequest body, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(teamService.addMember(squadId, caller(request), body));
    }

    @PatchMapping("/members/{jiraAccountId}")
    public ResponseEntity<SquadMember> changeRole(
            @PathVariable String squadId, @PathVariable String jiraAccountId,
            @RequestBody RoleRequest body, HttpServletRequest request) {
        return ResponseEntity.ok(teamService.changeRole(squadId, caller(request), jiraAccountId, body.roleName()));
    }

    @DeleteMapping("/members/{jiraAccountId}")
    public ResponseEntity<Void> remove(
            @PathVariable String squadId, @PathVariable String jiraAccountId, HttpServletRequest request) {
        teamService.removeMember(squadId, caller(request), jiraAccountId);
        return ResponseEntity.noContent().build();
    }
}
