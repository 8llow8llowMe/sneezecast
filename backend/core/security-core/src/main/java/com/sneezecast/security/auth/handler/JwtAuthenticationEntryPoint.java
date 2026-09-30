package com.sneezecast.security.auth.handler;

import com.sneezecast.security.common.exception.SecurityErrorCode;
import com.sneezecast.security.common.handler.SecurityErrorResponseWriter;
import com.sneezecast.security.common.handler.SecurityExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * 토큰 없이 인증이 필요한 API 를 부른 경우의 응답.
 *
 * <p>토큰이 <b>있는데 틀린</b> 경우는 {@code JwtAuthFilter} 가 파싱 단계에서 401 을 쓴다. 토큰이 <b>없으면</b>
 * 필터는 그냥 통과시키고, {@code @PreAuthorize("isAuthenticated()")} 가 익명 주체를 거부한 뒤
 * {@code ExceptionTranslationFilter} 가 이 진입점을 부른다. 등록하지 않으면 Spring 기본
 * {@code Http403ForbiddenEntryPoint} 가 <b>래퍼 없는 403</b> 을 내보낸다 — 인증 실패 갈래 중 유일하게
 * 봉투 밖으로 나가는 응답이 된다. 401 {@code SECURITY_001} 이면 FE 가 "재발급 1회 → 로그인 유도" 로
 * 다른 토큰 오류와 같은 길을 탄다.
 */
public class JwtAuthenticationEntryPoint extends SecurityExceptionHandler implements AuthenticationEntryPoint {

    public JwtAuthenticationEntryPoint(SecurityErrorResponseWriter errorResponseWriter) {
        super(errorResponseWriter);
    }

    @Override
    public void commence(
        HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException {

        sendErrorResponse(response, SecurityErrorCode.UNAUTHORIZED, authException.getMessage());
    }
}
