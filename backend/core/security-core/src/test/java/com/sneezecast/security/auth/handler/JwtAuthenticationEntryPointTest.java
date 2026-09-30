package com.sneezecast.security.auth.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sneezecast.security.common.handler.DefaultSecurityErrorResponseWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;

class JwtAuthenticationEntryPointTest {

    @Test
    @DisplayName("토큰 없이 인증 API 를 부르면 401 SECURITY_001 JSON — Spring 기본 403 빈 응답을 대신한다")
    void writesUnauthorizedJson() throws Exception {
        JwtAuthenticationEntryPoint entryPoint =
            new JwtAuthenticationEntryPoint(new DefaultSecurityErrorResponseWriter(new ObjectMapper()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(new MockHttpServletRequest("GET", "/api/v1/members/me"), response,
            new InsufficientAuthenticationException("Full authentication is required"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains("SECURITY_001");
    }
}
