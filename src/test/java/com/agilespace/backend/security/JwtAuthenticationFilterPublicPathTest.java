package com.agilespace.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterPublicPathTest {

    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(mock(JwtTokenUtil.class));

    private MockHttpServletResponse call(String method, String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        if (response.getStatus() == 200) assertNotNull(chain.getRequest());
        return response;
    }

    @Test
    void publicPromptHubReadPassesWithoutToken() throws Exception {
        assertEquals(200, call("GET", "/api/public/prompt-hub/items").getStatus());
        assertEquals(200, call("GET", "/api/public/prompt-hub/items/3f1c0e9a-0000-0000-0000-000000000000").getStatus());
    }

    @Test
    void restOfPromptApiStillRequiresToken() throws Exception {
        assertEquals(401, call("GET", "/api/prompts").getStatus());
        assertEquals(401, call("GET", "/api/prompts/collections").getStatus());
        assertEquals(401, call("POST", "/api/prompts").getStatus());
        assertEquals(401, call("DELETE", "/api/prompts/3f1c0e9a-0000-0000-0000-000000000000").getStatus());
    }

    @Test
    void joltTransformAndEngineInfoRequireLogin() throws Exception {
        assertEquals(401, call("POST", "/api/jolt/transform").getStatus());
        assertEquals(401, call("GET", "/api/jolt/engine-info").getStatus());
        assertEquals(401, call("POST", "/api/jolt/generate").getStatus());
    }

    @Test
    void joltTransformPassesWithValidToken() throws Exception {
        JwtTokenUtil tokenUtil = mock(JwtTokenUtil.class);
        when(tokenUtil.validateAndExtractClaims(anyString())).thenReturn(
                new ObjectMapper().readTree("{\"sub\":\"u1\",\"role\":\"MEMBER\",\"email\":\"a@b.c\"}"));
        JwtAuthenticationFilter authenticated = new JwtAuthenticationFilter(tokenUtil);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/jolt/transform");
        request.addHeader("Authorization", "Bearer token-valido");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        authenticated.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
        assertEquals("u1", request.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID));
    }
}
