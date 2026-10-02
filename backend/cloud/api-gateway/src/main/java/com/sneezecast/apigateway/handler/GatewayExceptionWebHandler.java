package com.sneezecast.apigateway.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sneezecast.apigateway.exception.GatewayErrorCode;
import com.sneezecast.apigateway.exception.GatewayException;
import java.net.ConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * 게이트웨이 자체 오류를 <b>공통 응답 봉투</b>와 {@code GATEWAY_00x} 로 바꾼다. 프론트는 봉투가 없는 응답을 일시 장애로만
 * 다루므로, "없는 API" 와 "잠시 후 재시도" 를 코드로 가를 수 있게 한다.
 *
 * <ul>
 *   <li>{@link GatewayException} — 그 코드 (경로 우회 거부 400)</li>
 *   <li>{@link ResponseStatusException} 404 — 라우트 없음 ({@code NoResourceFoundException} 포함) → {@code API_NOT_FOUND}</li>
 *   <li>{@link ResponseStatusException} 503 — 로드밸런서가 인스턴스를 못 찾음 ({@code NotFoundException}) → {@code SERVICE_UNAVAILABLE}</li>
 *   <li>{@link ResponseStatusException} 504 — {@code NettyRoutingFilter} 의 응답 타임아웃 → {@code UPSTREAM_TIMEOUT}</li>
 *   <li>{@link ConnectException} (Netty {@code AnnotatedConnectException} · {@code ConnectTimeoutException} 포함) →
 *       {@code SERVICE_UNAVAILABLE}. 게이트웨이는 이것을 상태로 바꾸지 않아 그대로 두면 500 이 된다. 요청이 업스트림에
 *       <b>닿지 않았으므로</b> 재시도해도 안전하다 — 주로 서비스가 재기동하는 동안 Eureka lease 가 만료되기 전 옛 주소로
 *       보낸 구간에 생긴다.</li>
 * </ul>
 *
 * <p>그 밖의 상태(413 등)와 다른 예외는 다시 던져 기본 핸들러의 형식을 유지한다. {@code ErrorWebExceptionHandler} 가 아니라
 * {@link WebExceptionHandler} 로 올리는 이유와 {@code @Order(-2)} 의 이유는 {@link JwtAuthExceptionWebHandler} 와 같다.
 * 두 핸들러는 같은 순서지만 받는 예외 타입이 겹치지 않아 둘 사이의 순서는 결과를 바꾸지 않는다.
 */
@Slf4j
@Order(-2)
@Component
@RequiredArgsConstructor
public class GatewayExceptionWebHandler implements WebExceptionHandler {

    /** 로그에 남길 경로 상한. 스캐너가 보내는 긴 경로가 로그 한 줄을 부풀리지 않게 한다. */
    static final int MAX_LOGGED_PATH_LENGTH = 200;

    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        GatewayErrorCode errorCode = resolve(ex);
        if (errorCode == null) {
            return Mono.error(ex);
        }

        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            // 이미 나간 응답에 상태를 덧씌울 수 없다. 기본 핸들러가 로깅하도록 넘긴다.
            return Mono.error(ex);
        }

        byte[] body;
        try {
            // 상태·헤더보다 먼저 직렬화한다 — 실패하면 응답을 건드리지 않은 채 넘길 수 있어야 한다.
            body = ErrorEnvelopeWriter.serialize(objectMapper, errorCode.getResultCode(), errorCode.getMessage());
        } catch (JsonProcessingException serializationFailure) {
            log.error("gateway error envelope serialization failed errorCode={}", errorCode.name(), serializationFailure);
            return Mono.error(ex);
        }

        logError(exchange, errorCode, ex);

        return ErrorEnvelopeWriter.write(response, errorCode.getHttpStatus(), body);
    }

    /** 이 핸들러가 맡는 예외면 그 코드, 아니면 {@code null}. */
    private static GatewayErrorCode resolve(Throwable ex) {
        if (ex instanceof GatewayException gatewayException) {
            return gatewayException.getErrorCode();
        }
        if (ex instanceof ResponseStatusException statusException) {
            return switch (statusException.getStatusCode().value()) {
                case 404 -> GatewayErrorCode.API_NOT_FOUND;
                case 503 -> GatewayErrorCode.SERVICE_UNAVAILABLE;
                case 504 -> GatewayErrorCode.UPSTREAM_TIMEOUT;
                default -> null;
            };
        }
        if (ex instanceof ConnectException) {
            return GatewayErrorCode.SERVICE_UNAVAILABLE;
        }
        return null;
    }

    /**
     * 4xx(경로 거부 · 없는 API)는 스캐너 · 오타로 늘 생기는 일이라 WARN, 5xx 는 서비스 상태 문제라 ERROR 다.
     *
     * <p>스택트레이스는 남기지 않는다 — 원인이 예외 종류로 이미 정해진다. 연결 실패만 어느 업스트림 주소였는지 메시지를
     * 남긴다. 헤더 · 토큰 · 쿼리스트링은 남기지 않는다.
     */
    private static void logError(ServerWebExchange exchange, GatewayErrorCode errorCode, Throwable ex) {
        int status = errorCode.getHttpStatus().value();
        String path = truncate(exchange.getRequest().getURI().getRawPath());
        if (!errorCode.getHttpStatus().is5xxServerError()) {
            log.warn("gateway error errorCode={} status={} path={}", errorCode.name(), status, path);
            return;
        }
        if (ex instanceof ConnectException) {
            log.error("gateway error errorCode={} status={} path={} reason={}",
                errorCode.name(), status, path, ex.getMessage());
            return;
        }
        log.error("gateway error errorCode={} status={} path={}", errorCode.name(), status, path);
    }

    private static String truncate(String path) {
        if (path == null) {
            return "";
        }
        return path.length() > MAX_LOGGED_PATH_LENGTH ? path.substring(0, MAX_LOGGED_PATH_LENGTH) : path;
    }
}
