package com.agilespace.backend.config;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.repository.AuditLogRepository;
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
                    .performedBy(request.getUserPrincipal() != null ? request.getUserPrincipal().getName() : "anonymous")
                    .details("Access to admin endpoint from " + request.getRemoteAddr())
                    .createdAt(LocalDateTime.now())
                    .build();
            auditLogRepository.save(auditLog);
        }
        return true;
    }
}
