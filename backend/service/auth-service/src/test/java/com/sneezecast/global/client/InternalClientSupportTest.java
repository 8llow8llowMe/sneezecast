package com.sneezecast.global.client;

import static com.sneezecast.global.client.FeignExceptionFixtures.DISTRICT_NOT_FOUND_BODY;
import static com.sneezecast.global.client.FeignExceptionFixtures.SPRING_DEFAULT_NOT_FOUND_BODY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sneezecast.common.dto.Response;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.codec.DecodeException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 서킷 집계와 예외 두 갈래(4xx 거절 / 그 밖의 장애)를 실제 {@link CircuitBreakerRegistry} 로 본다. 창은 작게(실패 2건이면 열림) 줄였고 판정은
 * yml 과 같다(4xx 무시).
 */
class InternalClientSupportTest {

    private static final String TARGET = InternalClientSupport.SURVEILLANCE_SERVICE;

    private CircuitBreakerRegistry registry;
    private InternalClientSupport support;

    @BeforeEach
    void setUp() {
        registry = FeignExceptionFixtures.smallRegistry();
        support = new InternalClientSupport(registry, new ObjectMapper());
    }

    @Test
    @DisplayName("200 이면 봉투를 벗겨 dataBody 를 돌려주고 성공으로 센다")
    void unwrapsDataBody() {
        String body = support.requestAndUnwrap(TARGET, () -> Response.success("역삼1동"));

        assertThat(body).isEqualTo("역삼1동");
        assertThat(circuit().getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("응답 본문이 없으면 null 이다 — 판단은 호출 어댑터가 한다")
    void nullResponseIsNull() {
        assertThat(support.<String>requestAndUnwrap(TARGET, () -> null)).isNull();
    }

    @Test
    @DisplayName("4xx 는 상태와 봉투의 resultCode 를 보존해 거절 예외로 올리고, 서킷에 실패로 세지 않는다")
    void clientErrorIsRejectedAndNotCounted() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> support.requestAndUnwrap(TARGET, () -> {
                throw FeignExceptionFixtures.status(404, DISTRICT_NOT_FOUND_BODY);
            })).isInstanceOfSatisfying(InternalClientRejectedException.class, exception -> {
                assertThat(exception.getTargetService()).isEqualTo(TARGET);
                assertThat(exception.getStatus()).isEqualTo(404);
                assertThat(exception.getResultCode()).isEqualTo("DISTRICT_001");
                assertThat(exception.getCause()).isInstanceOf(FeignException.FeignClientException.class);
            });
        }

        assertThat(circuit().getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {SPRING_DEFAULT_NOT_FOUND_BODY, "<html>Not Found</html>", ""})
    @DisplayName("봉투가 아닌 4xx 본문이면 resultCode 가 null 이다 — '리소스 없음' 과 '경로 없음(옛 버전)' 을 가를 수 있다")
    void nonEnvelopeBodyHasNoResultCode(String body) {
        assertThatThrownBy(() -> support.requestAndUnwrap(TARGET, () -> {
            throw FeignExceptionFixtures.status(404, body);
        })).isInstanceOfSatisfying(InternalClientRejectedException.class, exception -> assertThat(exception.getResultCode()).isNull());
    }

    @Test
    @DisplayName("5xx 는 장애 예외로 올리고 서킷에 실패로 센다")
    void serverErrorIsUnavailableAndCounted() {
        assertThatThrownBy(() -> support.requestAndUnwrap(TARGET, () -> {
            throw FeignExceptionFixtures.status(500, "{}");
        })).isInstanceOfSatisfying(InternalServiceUnavailableException.class, exception -> {
            assertThat(exception.getTargetService()).isEqualTo(TARGET);
            assertThat(exception.getCause()).isInstanceOf(FeignException.FeignServerException.class);
        });

        assertThat(circuit().getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("read timeout 은 장애 예외로 올리고 서킷에 실패로 센다")
    void timeoutIsUnavailableAndCounted() {
        assertThatThrownBy(() -> support.requestAndUnwrap(TARGET, () -> {
            throw FeignExceptionFixtures.readTimeout();
        })).isInstanceOf(InternalServiceUnavailableException.class);

        assertThat(circuit().getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("200 응답을 해석하지 못하면(DecodeException) 장애로 본다 — 4xx 로 오인해 서킷에서 빠지지 않는다")
    void decodeFailureIsUnavailable() {
        Request request = Request.create(Request.HttpMethod.GET, "http://surveillance-service/x", Map.of(), null, StandardCharsets.UTF_8, new RequestTemplate());

        assertThatThrownBy(() -> support.requestAndUnwrap(TARGET, () -> {
            throw new DecodeException(200, "broken", request);
        })).isInstanceOf(InternalServiceUnavailableException.class);
        assertThat(circuit().getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("실패가 쌓여 서킷이 열리면 호출하지 않고 장애 예외(원인 CallNotPermittedException)로 끝낸다")
    void openCircuitShortCircuits() {
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> support.requestAndUnwrap(TARGET, () -> {
                throw FeignExceptionFixtures.status(503, "");
            })).isInstanceOf(InternalServiceUnavailableException.class);
        }
        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> support.requestAndUnwrap(TARGET, () -> {
            calls.incrementAndGet();
            return Response.success("never");
        })).isInstanceOfSatisfying(InternalServiceUnavailableException.class,
            exception -> assertThat(exception.getCause()).isInstanceOf(CallNotPermittedException.class));
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("서킷은 대상 논리명별로 따로 센다")
    void circuitsArePerTarget() {
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> support.requestAndUnwrap("other-service", () -> {
                throw FeignExceptionFixtures.status(500, "");
            })).isInstanceOf(InternalServiceUnavailableException.class);
        }

        assertThat(registry.circuitBreaker("other-service").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private CircuitBreaker circuit() {
        return registry.circuitBreaker(TARGET);
    }
}
