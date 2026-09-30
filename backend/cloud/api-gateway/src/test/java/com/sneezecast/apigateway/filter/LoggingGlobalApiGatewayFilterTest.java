package com.sneezecast.apigateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 자격증명이 게이트웨이 접근 로그에 평문으로 남지 않는지 고정한다.
 *
 * <p>실제로 나가는 로그 문자열을 본다 — 헬퍼만 따로 보면 "두 로그 중 한쪽에만 걸었다" 를 놓친다.
 */
class LoggingGlobalApiGatewayFilterTest {

    private static final String TOKEN = "b3RoZXJfdG9rZW5fZXhhbXBsZV92YWx1ZV8wMTIzNDU2Nzg";

    private LoggingGlobalApiGatewayFilter filter;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        filter = new LoggingGlobalApiGatewayFilter();
        logger = (Logger) LoggerFactory.getLogger(LoggingGlobalApiGatewayFilter.class);
        appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("쿼리스트링의 token= 값은 가리고 다른 파라미터는 남긴다 — 지금 계약엔 없지만 장래 회귀 경로다")
    void masksTokenQueryParameter() {
        String rendered = runFilterAndRenderLogs(MockServerHttpRequest.get("/api/v1/reports?token=" + TOKEN + "&trace=abc"));

        assertThat(rendered).doesNotContain(TOKEN);
        assertThat(rendered).contains("token=***");
        // 다른 파라미터는 남는다 — 장애 추적에 필요하다.
        assertThat(rendered).contains("trace=abc");
    }

    @Test
    @DisplayName("Authorization 은 값 없이 존재 여부만 남는다")
    void logsOnlyPresenceOfAuthorization() {
        String rendered = runFilterAndRenderLogs(
            MockServerHttpRequest.get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));

        assertThat(rendered).doesNotContain(TOKEN);
        assertThat(rendered).contains("hasAuthorization=true");
    }

    @Test
    @DisplayName("가릴 것이 없는 경로는 건드리지 않는다")
    void leavesOtherPathsUntouched() {
        String rendered = runFilterAndRenderLogs(MockServerHttpRequest.get("/api/v1/districts/1111051500"));

        assertThat(rendered).contains("/api/v1/districts/1111051500");
        assertThat(rendered).doesNotContain("***");
    }

    private String runFilterAndRenderLogs(MockServerHttpRequest.BaseBuilder<?> request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request.build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        List<ILoggingEvent> events = appender.list;
        assertThat(events).as("요청 로그와 응답 로그가 모두 남아야 한다").hasSizeGreaterThanOrEqualTo(2);
        return events.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (left, right) -> left + "\n" + right);
    }
}
