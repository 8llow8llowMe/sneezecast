package com.sneezecast.domainlayer.member.adapter.out.client.feign;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import feign.optionals.OptionalDecoder;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.cloud.openfeign.support.ResponseEntityDecoder;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

/**
 * 파기 Feign 인터페이스가 실제 HTTP 에서 계약대로 나가고 읽히는지 본다 — {@code DELETE /internal/v1/reporters/{memberId}}, Authorization 헤더 없음,
 * 본문 없는 204 는 예외 없이 null 로 읽힌다(그것이 성공이다). 디코더는 Spring Cloud OpenFeign 기본 조합과 같다.
 */
class ReportPurgeClientDecodingTest {

    private static final long MEMBER_ID = 7350912846153L;

    private HttpServer server;
    private final AtomicReference<String> requestLine = new AtomicReference<>();
    private final AtomicReference<Boolean> authorizationPresent = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestLine.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            authorizationPresent.set(exchange.getRequestHeaders().containsKey("Authorization"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("204 는 null 로 읽히고, 요청은 토큰 없는 DELETE /internal/v1/reporters/{memberId} 다")
    void noContentDecodesToNull() {
        ReportPurgeClient client = Feign.builder()
            .contract(new SpringMvcContract())
            .decoder(new OptionalDecoder(new ResponseEntityDecoder(new SpringDecoder(HttpMessageConverters::new))))
            .target(ReportPurgeClient.class, "http://127.0.0.1:" + server.getAddress().getPort());

        assertThat(client.purgeReporter(MEMBER_ID)).isNull();
        assertThat(requestLine.get()).isEqualTo("DELETE /internal/v1/reporters/" + MEMBER_ID);
        assertThat(authorizationPresent.get()).isFalse();
    }
}
