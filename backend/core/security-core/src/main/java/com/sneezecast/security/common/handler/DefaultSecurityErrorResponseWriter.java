package com.sneezecast.security.common.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sneezecast.common.dto.Response;
import com.sneezecast.security.common.exception.SecurityErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;

/**
 * 시큐리티 실패(401 · 403) 응답을 공통 응답 봉투로 쓴다. <b>기본값이 곧 옳은 계약이다.</b>
 *
 * <p>서비스마다 {@code @Primary} 로 같은 writer 를 복사해 덮는 구조로 두면, 새 서비스가 그 복사를 잊었을 때
 * 그 서비스의 401/403 만 봉투 밖으로 나간다. 프론트는 봉투가 없는 응답의 {@code resultCode} 를 읽지 못하므로
 * 사용자는 서버가 준 사유 대신 일반 오류를 본다. 그래서 덮을 필요가 없도록 기본을 봉투로 둔다.
 *
 * <p><b>{@code detail} 은 응답에 싣지 않는다.</b> 토큰이 왜 거부됐는지의 내부 사정이라
 * 인증되지 않은 호출자에게 줄 정보가 아니다.
 */
@RequiredArgsConstructor
public class DefaultSecurityErrorResponseWriter implements SecurityErrorResponseWriter {

    private final ObjectMapper objectMapper;

    @Override
    public void write(HttpServletResponse response, SecurityErrorCode errorCode, String detail) throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        Response<Void> body = Response.fail(errorCode.getCode(), errorCode.getMessage());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
