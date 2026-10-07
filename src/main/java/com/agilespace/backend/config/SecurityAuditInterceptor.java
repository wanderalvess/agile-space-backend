package com.agilespace.backend.config;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SecurityAuditInterceptor implements HandlerInterceptor {

    private final AuditLogRepository auditLogRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/admin")) {
            AuditLog auditLog = AuditLog.builder()
                    .id(UUID.randomUUID().toString())
                    .action(request.getMethod() + " " + uri)
                    .performedBy(resolvePerformedBy(request))
                    .details("Access to admin endpoint from " + request.getRemoteAddr())
                    .createdAt(LocalDateTime.now())
                    .build();
            auditLogRepository.save(auditLog);
        }
        return true;
    }

    /**
     * /api/admin/** já exige JWT válido com role=ADMIN antes de chegar aqui (ver
     * JwtAuthenticationFilter, que roda antes deste interceptor e rejeita com
     * 401/403 sem nunca chamar preHandle). Não há Spring Security nesta aplicação,
     * então request.getUserPrincipal() é sempre null; a identidade real vem dos
     * atributos que o filtro JWT expõe no request. O fallback "anonymous" é uma
     * rede de segurança que não deveria disparar em uso normal.
     */
    private static String resolvePerformedBy(HttpServletRequest request) {
        String email = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_EMAIL);
        if (email != null && !email.isBlank()) {
            return email;
        }
        String userId = (String) request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        if (userId != null && !userId.isBlank()) {
            return userId;
        }
        return "anonymous";
    }
}
