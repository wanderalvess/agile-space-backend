package com.agilespace.backend.config;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SecurityAuditInterceptorTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @InjectMocks
    private SecurityAuditInterceptor interceptor;

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private MockHttpServletRequest adminRequest() {
        return new MockHttpServletRequest("GET", "/api/admin/stats");
    }

    @Test
    void usesEmailAttributeWhenPresent() throws Exception {
        MockHttpServletRequest request = adminRequest();
        request.setAttribute(JwtAuthenticationFilter.ATTR_USER_EMAIL, "admin@agilespace.com");
        request.setAttribute(JwtAuthenticationFilter.ATTR_USER_ID, "user-123");

        interceptor.preHandle(request, response, new Object());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertEquals("admin@agilespace.com", captor.getValue().getPerformedBy());
    }

    @Test
    void fallsBackToUserIdWhenEmailAttributeIsAbsent() throws Exception {
        MockHttpServletRequest request = adminRequest();
        request.setAttribute(JwtAuthenticationFilter.ATTR_USER_ID, "user-123");

        interceptor.preHandle(request, response, new Object());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertEquals("user-123", captor.getValue().getPerformedBy());
    }

    @Test
    void fallsBackToAnonymousWhenNoAuthAttributesArePresent() throws Exception {
        MockHttpServletRequest request = adminRequest();

        interceptor.preHandle(request, response, new Object());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertEquals("anonymous", captor.getValue().getPerformedBy());
    }
}
