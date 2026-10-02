package com.sneezecast.apigateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.apigateway.exception.GatewayErrorCode;
import com.sneezecast.apigateway.exception.GatewayException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 경로 우회 거부 필터.
 *
 * <p>요청은 {@code MockServerHttpRequest.method(method, URI)} 로 원문 그대로 만든다 — {@code get(String)} 은 {@code %} 를
 * 다시 인코딩해 {@code %2e} 가 {@code %252e} 로 바뀌므로, 검사하려던 모양이 아닌 것을 보게 된다.
 */
class PathTraversalRejectWebFilterTest {

    private final PathTraversalRejectWebFilter filter = new PathTraversalRejectWebFilter();

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "/api/v1/auth/../internal/x",
        "/api/v1/auth/%2e%2e/internal/x",
        "/api/v1/auth/%2E%2E/internal/x",
        "/api/v1/auth/..%2finternal/x",
        "/api/v1/auth/%2F..%2Finternal",
        "/api/v1/auth/..;/..;/internal/x",
        "/api/v1/auth/%252e%252e/internal/x",
        "/api/v1/auth/login%00",
        "/api/v1/auth/..%5cinternal",
        "/api/v1/auth/login;jsessionid=x",
        "/api/v1/auth/%3B/internal"
    })
    @DisplayName("업스트림이 정규화하면 라우트 밖으로 풀리는 경로는 400 GATEWAY_001 로 막고 체인에 넘기지 않는다")
    void rejectsTraversalSequences(String rawPath) {
        AtomicBoolean reachedChain = new AtomicBoolean();

        assertThatThrownBy(() -> run(rawPath, reachedChain))
            .isInstanceOfSatisfying(GatewayException.class,
                rejected -> assertThat(rejected.getErrorCode()).isEqualTo(GatewayErrorCode.PATH_NOT_ALLOWED));
        assertThat(reachedChain).as("거부한 요청은 라우팅까지 가지 않는다").isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "/api/v1/auth/login",
        "/api/v1/districts/1111051500",
        "/api/v1/auth/a.b",
        "/api/v1/districts/%ED%95%9C",
        "/"
    })
    @DisplayName("공개 API 의 보통 경로 · 점 하나 · 한글 인코딩은 통과한다")
    void passesOrdinaryPaths(String rawPath) {
        AtomicBoolean reachedChain = new AtomicBoolean();

        run(rawPath, reachedChain);

        assertThat(reachedChain).isTrue();
    }

    @Test
    @DisplayName("쿼리스트링은 보지 않는다 — 경로 해석에 쓰이지 않는다")
    void ignoresQueryString() {
        AtomicBoolean reachedChain = new AtomicBoolean();

        run("/api/v1/districts?name=..%2f%3b", reachedChain);

        assertThat(reachedChain).isTrue();
    }

    private void run(String rawUri, AtomicBoolean reachedChain) {
        MockServerHttpRequest request = MockServerHttpRequest.method(HttpMethod.GET, URI.create(rawUri)).build();
        filter.filter(MockServerWebExchange.from(request), exchange -> {
            reachedChain.set(true);
            return Mono.empty();
        }).block();
    }
}
