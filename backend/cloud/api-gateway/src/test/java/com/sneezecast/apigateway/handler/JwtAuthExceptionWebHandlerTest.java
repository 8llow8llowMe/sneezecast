package com.sneezecast.apigateway.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sneezecast.apigateway.jwt.exception.JwtErrorCode;
import com.sneezecast.apigateway.jwt.exception.JwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/**
 * {@link JwtAuthExceptionWebHandler} 단위 검증.
 *
 * <p>고정하는 것은 셋이다.
 * <ul>
 *   <li><b>봉투와 상태</b> — 사유마다 제 HTTP 상태로, 공통 응답 봉투({@code dataHeader}/{@code dataBody})에 담겨 나간다
 *   <li><b>{@code resultCode} 는 {@code SECURITY_00x}</b> — 프론트가 auth-service 에서 이미 보는 코드와 같다
 *   <li><b>내 것이 아닌 예외는 건드리지 않는다</b> — 기본 핸들러가 볼 수 있게 그대로 다시 던진다.
 *       이게 깨지면 JWT 와 무관한 오류의 응답까지 이 클래스가 삼킨다
 * </ul>
 */
class JwtAuthExceptionWebHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JwtAuthExceptionWebHandler handler = new JwtAuthExceptionWebHandler(objectMapper);

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/reports").build());
    }

    @Test
    @DisplayName("만료 토큰은 401 + 봉투 + SECURITY_002 다 — 500 이면 클라이언트가 재발급을 걸지 않는다")
    void expiredTokenBecomesUnauthorizedEnvelope() {
        MockServerWebExchange exchange = exchange();

        handler.handle(exchange, new JwtException(JwtErrorCode.TOKEN_EXPIRED)).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(body(exchange))
            .contains("\"success\":false")
            .contains("\"resultCode\":\"SECURITY_002\"")
            .contains("토큰이 만료되었습니다.")
            .contains("\"dataBody\":null");
    }

    @Test
    @DisplayName("형식이 깨진 토큰은 401 + 봉투 + SECURITY_005 다")
    void malformedTokenBecomesUnauthorizedEnvelope() {
        MockServerWebExchange exchange = exchange();

        handler.handle(exchange, new JwtException(JwtErrorCode.TOKEN_MALFORMED)).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(body(exchange)).contains("\"resultCode\":\"SECURITY_005\"");
    }

    @Test
    @DisplayName("블랙리스트 조회 불가는 503 + 봉투 + SECURITY_008 이다 — 의도한 실패와 진짜 장애를 가른다")
    void verificationUnavailableBecomesServiceUnavailableEnvelope() {
        MockServerWebExchange exchange = exchange();

        handler.handle(exchange, new JwtException(JwtErrorCode.TOKEN_VERIFICATION_UNAVAILABLE)).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(body(exchange)).contains("\"resultCode\":\"SECURITY_008\"");
    }

    @ParameterizedTest
    @EnumSource(JwtErrorCode.class)
    @DisplayName("모든 사유가 제 상태와 제 코드로 나간다 — 500 으로 접히는 사유가 하나도 없어야 한다")
    void everyReasonCarriesItsOwnStatusAndCode(JwtErrorCode errorCode) {
        MockServerWebExchange exchange = exchange();

        handler.handle(exchange, new JwtException(errorCode)).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(errorCode.getHttpStatus());
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(body(exchange))
            .contains("\"resultCode\":\"" + errorCode.getResultCode() + "\"")
            .contains(errorCode.getMessage());
    }

    @Test
    @DisplayName("JwtException 이 아닌 예외는 그대로 다시 던진다 — 기본 핸들러의 몫을 뺏지 않는다")
    void nonJwtExceptionIsPassedOn() {
        MockServerWebExchange exchange = exchange();
        RuntimeException other = new IllegalStateException("업스트림 라우팅 실패");

        assertThatThrownBy(() -> handler.handle(exchange, other).block()).isSameAs(other);

        // 응답에 손대지 않았다 — 상태도 본문도 이 핸들러가 정하지 않는다
        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(exchange.getResponse().getHeaders().getContentType()).isNull();
    }

    @Test
    @DisplayName("이미 나간 응답에는 덧씌우지 않고 넘긴다")
    void committedResponseIsLeftAlone() {
        MockServerWebExchange exchange = exchange();
        exchange.getResponse().setComplete().block();
        JwtException expired = new JwtException(JwtErrorCode.TOKEN_EXPIRED);

        assertThatThrownBy(() -> handler.handle(exchange, expired).block()).isSameAs(expired);
    }

    private static String body(MockServerWebExchange exchange) {
        return exchange.getResponse().getBodyAsString().block();
    }
}
