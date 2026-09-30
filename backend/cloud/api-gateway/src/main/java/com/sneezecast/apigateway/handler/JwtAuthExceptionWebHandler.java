package com.sneezecast.apigateway.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sneezecast.apigateway.jwt.exception.JwtErrorCode;
import com.sneezecast.apigateway.jwt.exception.JwtException;
import com.sneezecast.common.dto.Response;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * 게이트웨이의 {@link JwtException} 을 <b>공통 응답 봉투</b>와 제 HTTP 상태로 바꾼다.
 *
 * <h2>없으면 무슨 일이 일어나나</h2>
 *
 * <p>{@code JwtAuthApiGatewayFilter} 가 던지는 {@code JwtException} 을 <b>아무도 받지 않아</b>
 * WebFlux 기본 오류 처리로 흘러가 <b>전부 500</b> 이 된다. 액세스 토큰은 금방 만료되므로 이것은
 * 예외 경로가 아니라 일상 경로다:
 *
 * <ul>
 *   <li><b>세션이 스스로 복구되지 않는다</b> — 클라이언트는 401 일 때만 재발급을 시도한다.
 *       500 에는 재발급이 걸리지 않아 토큰이 갱신되지 않는다</li>
 *   <li><b>화면이 "잠시 후 다시 시도" 를 준다</b> — 500 은 일시 장애로 분류되는데 재시도해도 계속 500 이다</li>
 *   <li><b>공개 화면까지 막힌다</b> — 미로그인에 200 인 공개 API 도 만료 토큰을 실으면 500 이 된다</li>
 *   <li><b>Redis 장애가 500 폭풍이 된다</b> — 503 을 의도한 {@code TOKEN_VERIFICATION_UNAVAILABLE}
 *       까지 500 으로 나가 의도한 실패와 진짜 장애가 구분되지 않는다</li>
 * </ul>
 *
 * <h2>{@code ErrorWebExceptionHandler} 가 아니라 {@link WebExceptionHandler} 인 이유</h2>
 *
 * <p>부트의 {@code ErrorWebFluxAutoConfiguration} 은 기본 핸들러를
 * {@code @ConditionalOnMissingBean(ErrorWebExceptionHandler.class)} 로 건다. 그래서
 * <b>{@code ErrorWebExceptionHandler} 를 빈으로 올리면 기본 핸들러가 통째로 사라진다</b> —
 * JWT 와 무관한 모든 오류(라우팅 실패, 업스트림 타임아웃 …)의 응답까지 이 클래스가 책임지게
 * 된다. 여기서 하려는 일은 그게 아니므로 상위 인터페이스인 {@code WebExceptionHandler} 로
 * 등록하고, <b>내 것이 아닌 예외는 그대로 다시 던져</b> 기본 핸들러에게 넘긴다.
 *
 * <p>{@link Order} 가 {@code -2} 인 것은 기본 핸들러가 {@code -1} 이기 때문이다 —
 * {@code ExceptionHandlingWebHandler} 는 정렬 순서대로 {@code onErrorResume} 을 걸므로 이쪽이
 * 먼저 봐야 한다.
 */
@Slf4j
@Order(-2)
@Component
@RequiredArgsConstructor
public class JwtAuthExceptionWebHandler implements WebExceptionHandler {

    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (!(ex instanceof JwtException jwtException)) {
            return Mono.error(ex);
        }

        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            // 이미 나간 응답에 상태를 덧씌울 수 없다. 기본 핸들러가 로깅하도록 넘긴다.
            return Mono.error(ex);
        }

        JwtErrorCode errorCode = jwtException.getErrorCode();
        byte[] body;
        try {
            // 상태·헤더보다 먼저 직렬화한다 — 실패하면 응답을 건드리지 않은 채 넘길 수 있어야 한다.
            body = objectMapper.writeValueAsString(Response.fail(errorCode.getResultCode(), errorCode.getMessage()))
                .getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException serializationFailure) {
            log.error("jwt error envelope serialization failed errorCode={}", errorCode.name(), serializationFailure);
            return Mono.error(ex);
        }

        logRejection(exchange, errorCode);

        response.setStatusCode(errorCode.getHttpStatus());
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 만료·형식 오류는 <b>정상 운영 중에도 계속 일어나는 일</b>이라 WARN 이다. 5xx 로 나가는
     * 검증 불가(Redis 장애)만 ERROR 로 띄운다 — 그것만 사람이 봐야 하는 신호다.
     *
     * <p>토큰 자체는 남기지 않는다. 사유와 경로면 추적에 충분하고, 토큰은 남은 수명 동안
     * 그대로 쓸 수 있는 자격증명이다.
     */
    private void logRejection(ServerWebExchange exchange, JwtErrorCode errorCode) {
        String path = exchange.getRequest().getPath().value();
        if (errorCode.getHttpStatus().is5xxServerError()) {
            log.error("jwt verification unavailable errorCode={} status={} path={}",
                errorCode.name(), errorCode.getHttpStatus().value(), path);
            return;
        }
        log.warn("jwt rejected errorCode={} status={} path={}",
            errorCode.name(), errorCode.getHttpStatus().value(), path);
    }
}
