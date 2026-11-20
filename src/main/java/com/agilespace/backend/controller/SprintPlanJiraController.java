package com.agilespace.backend.controller;

import com.agilespace.backend.dto.SprintPlanDtos.CopyFromPreviousRequest;
import com.agilespace.backend.dto.SprintPlanDtos.NextSprintView;
import com.agilespace.backend.dto.SprintPlanDtos.PlanView;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import com.agilespace.backend.service.SquadAccessService;
import com.agilespace.backend.service.sprintplan.SprintPlanCopyService;
import com.agilespace.backend.service.sprintplan.SprintPlanJiraService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Sprint Planner: copiar da sprint anterior e próxima sprint do Jira. Só liderança da squad ou ADMIN. */
@RestController
@RequestMapping("/api/squads/{squadId}/sprint-plans")
@RequiredArgsConstructor
public class SprintPlanJiraController {

    private final SprintPlanCopyService copyService;
    private final SprintPlanJiraService jiraService;
    private final SquadAccessService squadAccessService;

    @PostMapping("/{planId}/copy-from-previous")
    public PlanView copyFromPrevious(@PathVariable String squadId, @PathVariable String planId,
                                     @RequestBody(required = false) CopyFromPreviousRequest body, HttpServletRequest request) {
        requireLeadership(squadId, request);
        return copyService.copyFromPrevious(squadId, planId, body == null ? null : body.sourcePlanId(), userId(request));
    }

    @GetMapping("/next-sprint")
    public NextSprintView nextSprint(@PathVariable String squadId, HttpServletRequest request) {
        requireLeadership(squadId, request);
        return jiraService.nextSprint(squadId, userId(request));
    }

    private void requireLeadership(String squadId, HttpServletRequest request) {
        if (!squadAccessService.isSquadLeadership(squadId, userId(request),
                (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ROLE))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Só a liderança da squad ou um administrador pode alterar o plano da sprint.");
        }
    }

    private static String userId(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
    }
}
