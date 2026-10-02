package com.sneezecast.apigateway.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sneezecast.apigateway.exception.GatewayErrorCode;
import com.sneezecast.apigateway.exception.GatewayException;
import com.sneezecast.apigateway.jwt.exception.JwtErrorCode;
import com.sneezecast.apigateway.jwt.exception.JwtException;
import io.netty.channel.ConnectTimeoutException;
import java.net.ConnectException;
import java.net.URI;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link GatewayExceptionWebHandler} 단위 검증.
 *
 * <ul>
 *   <li><b>매핑</b> — 게이트웨이가 실제로 내는 예외 모양(라우트 없음 · 인스턴스 없음 · 응답 타임아웃 · 연결 실패)마다
 *       제 상태와 {@code GATEWAY_00x} 봉투로 나간다</li>
 *   <li><b>내 것이 아닌 예외는 건드리지 않는다</b> — 매핑 밖 상태 · 다른 예외 · JWT 예외는 그대로 다시 던진다</li>
 * </ul>
 */
class GatewayExceptionWebHandlerTest {

    private final GatewayExceptionWebHandler handler = new GatewayExceptionWebHandler(new ObjectMapper());

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(GatewayExceptionWebHandler.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    static Stream<Arguments> mappedExceptions() {
        return Stream.of(
            Arguments.of(new GatewayException(GatewayErrorCode.PATH_NOT_ALLOWED), GatewayErrorCode.PATH_NOT_ALLOWED),
            Arguments.of(new ResponseStatusException(HttpStatus.NOT_FOUND), GatewayErrorCode.API_NOT_FOUND),
            Arguments.of(new NoResourceFoundException("/api/v1/unknown"), GatewayErrorCode.API_NOT_FOUND),
            Arguments.of(NotFoundException.create(false, "Unable to find instance for surveillance-service"),
                GatewayErrorCode.SERVICE_UNAVAILABLE),
            Arguments.of(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Response took longer than timeout"),
                GatewayErrorCode.UPSTREAM_TIMEOUT),
            Arguments.of(new ConnectException("Connection refused"), GatewayErrorCode.SERVICE_UNAVAILABLE),
            Arguments.of(new ConnectTimeoutException("connection timed out"), GatewayErrorCode.SERVICE_UNAVAILABLE)
        );
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("mappedExceptions")
    @DisplayName("게이트웨이 자체 오류는 제 상태 + 봉투 + GATEWAY_00x 로 나간다")
    void mapsGatewayErrorsToEnvelope(Throwable error, GatewayErrorCode expected) {
        MockServerWebExchange exchange = exchange("/api/v1/reports/me");

        handler.handle(exchange, error).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(expected.getHttpStatus());
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(body(exchange))
            .contains("\"success\":false")
            .contains("\"resultCode\":\"" + expected.getResultCode() + "\"")
            .contains(expected.getMessage())
            .contains("\"dataBody\":null");
    }

    @ParameterizedTest
    @EnumSource(GatewayErrorCode.class)
    @DisplayName("모든 GatewayException 사유가 제 상태와 제 코드로 나간다")
    void everyReasonCarriesItsOwnStatusAndCode(GatewayErrorCode errorCode) {
        MockServerWebExchange exchange = exchange("/api/v1/reports");

        handler.handle(exchange, new GatewayException(errorCode)).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(errorCode.getHttpStatus());
        assertThat(body(exchange)).contains("\"resultCode\":\"" + errorCode.getResultCode() + "\"");
    }

    static Stream<Throwable> foreignExceptions() {
        return Stream.of(
            new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE),
            new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR),
            new ResponseStatusException(HttpStatus.BAD_GATEWAY),
            new IllegalStateException("업스트림 라우팅 실패"),
            new JwtException(JwtErrorCode.TOKEN_EXPIRED)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("foreignExceptions")
    @DisplayName("매핑 밖의 상태 · 관계없는 예외 · JWT 예외는 그대로 다시 던지고 응답에 손대지 않는다")
    void foreignExceptionIsPassedOn(Throwable error) {
        MockServerWebExchange exchange = exchange("/api/v1/reports");

        assertThatThrownBy(() -> handler.handle(exchange, error).block()).isSameAs(error);

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(exchange.getResponse().getHeaders().getContentType()).isNull();
    }

    @Test
    @DisplayName("이미 나간 응답에는 덧씌우지 않고 넘긴다")
    void committedResponseIsLeftAlone() {
        MockServerWebExchange exchange = exchange("/api/v1/reports");
        exchange.getResponse().setComplete().block();
        ResponseStatusException timeout = new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT);

        assertThatThrownBy(() -> handler.handle(exchange, timeout).block()).isSameAs(timeout);
    }

    @Test
    @DisplayName("봉투 직렬화에 실패하면 상태 · 헤더를 정하지 않은 채 원래 예외를 넘긴다")
    void serializationFailureLeavesResponseUntouched() throws JsonProcessingException {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {
        });
        GatewayExceptionWebHandler failingHandler = new GatewayExceptionWebHandler(failingMapper);
        MockServerWebExchange exchange = exchange("/api/v1/reports");
        GatewayException rejected = new GatewayException(GatewayErrorCode.PATH_NOT_ALLOWED);

        assertThatThrownBy(() -> failingHandler.handle(exchange, rejected).block()).isSameAs(rejected);

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(exchange.getResponse().getHeaders().getContentType()).isNull();
    }

    @Test
    @DisplayName("4xx 는 WARN, 5xx 는 ERROR 로 남기고 쿼리스트링은 남기지 않는다")
    void logsClientErrorsAsWarnAndServerErrorsAsError() {
        handler.handle(exchange("/api/v1/unknown?token=secret"), new ResponseStatusException(HttpStatus.NOT_FOUND)).block();
        handler.handle(exchange("/api/v1/reports/me"), new ConnectException("Connection refused: /172.18.0.5:3100")).block();

        assertThat(appender.list).hasSize(2);
        ILoggingEvent notFound = appender.list.get(0);
        assertThat(notFound.getLevel()).isEqualTo(Level.WARN);
        assertThat(notFound.getFormattedMessage())
            .isEqualTo("gateway error errorCode=API_NOT_FOUND status=404 path=/api/v1/unknown");

        ILoggingEvent unavailable = appender.list.get(1);
        assertThat(unavailable.getLevel()).isEqualTo(Level.ERROR);
        assertThat(unavailable.getFormattedMessage())
            .startsWith("gateway error errorCode=SERVICE_UNAVAILABLE status=503 path=/api/v1/reports/me")
            .contains("Connection refused");
        assertThat(unavailable.getThrowableProxy()).as("스택트레이스는 남기지 않는다").isNull();
    }

    @Test
    @DisplayName("로그의 경로는 원문 그대로 200자에서 자른다")
    void truncatesLoggedPath() {
        String longPath = "/api/v1/" + "a".repeat(400);

        handler.handle(exchange(longPath), new ResponseStatusException(HttpStatus.NOT_FOUND)).block();

        assertThat(appender.list.get(0).getFormattedMessage())
            .endsWith("path=" + longPath.substring(0, GatewayExceptionWebHandler.MAX_LOGGED_PATH_LENGTH));
    }

    private static MockServerWebExchange exchange(String rawUri) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, URI.create(rawUri)).build());
    }

    private static String body(MockServerWebExchange exchange) {
        return exchange.getResponse().getBodyAsString().block();
    }
}
