package com.agilespace.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

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
}
