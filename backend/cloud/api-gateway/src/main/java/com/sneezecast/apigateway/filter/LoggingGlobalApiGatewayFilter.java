package com.sneezecast.apigateway.filter;

import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 요청 · 응답 접근 로그. 검색할 수 있게 영어 key=value 로 남긴다.
 *
 * <p>{@code Authorization} 은 값 없이 <b>존재 여부만</b> 찍는다. 토큰은 남은 수명 동안 그대로 쓸 수 있는 자격증명이다.
 * 같은 이유로 쿼리스트링의 {@code token=} 값도 가린다.
 */
@Slf4j
@Component
public class LoggingGlobalApiGatewayFilter implements GlobalFilter, Ordered {

    private static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final String REQUEST_START_TIME = "requestStartTime";
    private static final long SLOW_REQUEST_THRESHOLD_MS = 3000;
    private static final String UNKNOWN = "unknown";

    /**
     * 쿼리스트링의 {@code token=} 값. 지금 계약에는 토큰을 쿼리로 받는 API 가 없지만, 그런 변경이 오면 평문으로 로그에
     * 쌓이는 <b>회귀 경로</b>라 미리 막아 둔다.
     */
    private static final Pattern TOKEN_QUERY_PARAMETER = Pattern.compile("(?i)(^|[?&])token=[^&#]*");
    private static final String MASKED_TOKEN_QUERY_PARAMETER = "$1token=***";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = getOrCreateRequestId(exchange.getRequest());
        exchange.getAttributes().put(REQUEST_ID_HEADER, requestId);

        long startTime = System.currentTimeMillis();
        exchange.getAttributes().put(REQUEST_START_TIME, startTime);

        logRequest(exchange, requestId);

        return chain.filter(exchange)
            .doFinally(signalType -> logResponse(exchange, requestId, startTime));
    }

    private String getOrCreateRequestId(ServerHttpRequest request) {
        String requestId = request.getHeaders().getFirst(REQUEST_ID_HEADER);
        return (requestId != null) ? requestId : UUID.randomUUID().toString();
    }

    private void logRequest(ServerWebExchange exchange, String requestId) {
        ServerHttpRequest request = exchange.getRequest();
        String query = request.getURI().getQuery();

        log.info("gateway request requestId={} method={} uri={} clientIp={} userAgent={} hasAuthorization={} query={}",
            requestId, request.getMethod(), maskTokenQueryParameter(request.getURI().toString()), getClientIp(request),
            getUserAgent(request), request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION),
            query != null ? maskTokenQueryParameter(query) : "none"
        );
    }

    private void logResponse(ServerWebExchange exchange, String requestId, long startTime) {
        ServerHttpResponse response = exchange.getResponse();
        HttpStatusCode statusCode = response.getStatusCode();

        long duration = System.currentTimeMillis() - startTime;
        int status = statusCode != null ? statusCode.value() : 0;
        String path = exchange.getRequest().getPath().toString();
        String contentLength = response.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH);

        log.info("gateway response requestId={} status={} durationMs={} path={} contentLength={}",
            requestId, status, duration, path, contentLength != null ? contentLength : UNKNOWN
        );

        if (status >= 400) {
            log.warn("gateway error response requestId={} status={} path={} durationMs={} clientIp={}",
                requestId, status, path, duration, getClientIp(exchange.getRequest())
            );
        }

        if (duration > SLOW_REQUEST_THRESHOLD_MS) {
            log.warn("gateway slow request requestId={} durationMs={} thresholdMs={} path={}",
                requestId, duration, SLOW_REQUEST_THRESHOLD_MS, path
            );
        }
    }

    private String maskTokenQueryParameter(String uriOrQuery) {
        return TOKEN_QUERY_PARAMETER.matcher(uriOrQuery).replaceAll(MASKED_TOKEN_QUERY_PARAMETER);
    }

    private String getClientIp(ServerHttpRequest request) {
        String forwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isEmpty()) {
            return forwardedFor.split(",")[0].trim();
        }

        String realIp = request.getHeaders().getFirst("X-Real-IP");
        if (realIp != null) {
            return realIp;
        }

        return request.getRemoteAddress() != null
            ? request.getRemoteAddress().getAddress().getHostAddress()
            : UNKNOWN;
    }

    private String getUserAgent(ServerHttpRequest request) {
        String userAgent = request.getHeaders().getFirst(HttpHeaders.USER_AGENT);
        if (userAgent == null) {
            return UNKNOWN;
        }

        // 너무 길면 자르기(Loki 수집용)
        return userAgent.length() > 100 ? userAgent.substring(0, 100) + "..." : userAgent;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
