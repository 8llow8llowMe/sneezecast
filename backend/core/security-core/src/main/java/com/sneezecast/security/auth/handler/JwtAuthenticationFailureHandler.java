package com.sneezecast.security.auth.handler;

import com.sneezecast.security.common.exception.SecurityErrorCode;
import com.sneezecast.security.common.exception.SecurityJwtException;
import com.sneezecast.security.common.handler.AuthenticationFailureHandler;
import com.sneezecast.security.common.handler.SecurityErrorResponseWriter;
import com.sneezecast.security.common.handler.SecurityExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public class JwtAuthenticationFailureHandler extends SecurityExceptionHandler implements AuthenticationFailureHandler {

    public JwtAuthenticationFailureHandler(SecurityErrorResponseWriter errorResponseWriter) {
        super(errorResponseWriter);
    }

    @Override
    public boolean handleAuthenticationFailure(
        HttpServletRequest request, HttpServletResponse response, Throwable exception) throws IOException {

        if (!supports(exception)) {
            return false;
        }

        SecurityJwtException jwtException = (SecurityJwtException) exception;
        SecurityErrorCode errorCode = jwtException.getErrorCode();

        sendErrorResponse(response, errorCode, describe(jwtException));

        return true;
    }

    @Override
    public boolean supports(Throwable exception) {
        return exception instanceof SecurityJwtException;
    }

    /**
     * 로그용. 같은 TOKEN_INVALID 라도 어느 검사(형식·디코딩·alg·클레임)에 걸렸는지는 cause 종류가 말해 준다.
     * cause 의 메시지가 아니라 <b>클래스 이름만</b> 남긴다 — 토큰 조각이나 클레임 값이 로그로 새지 않게 한다.
     */
    private static String describe(SecurityJwtException exception) {
        Throwable cause = exception.getCause();
        if (cause == null) {
            return exception.getMessage();
        }
        return exception.getMessage() + " cause=" + cause.getClass().getSimpleName();
    }
}
