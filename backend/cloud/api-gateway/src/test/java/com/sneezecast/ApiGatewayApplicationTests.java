package com.sneezecast;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.reactive.context.ReactiveWebApplicationContext;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.NettyRoutingFilter;
import org.springframework.cloud.gateway.filter.ReactiveLoadBalancerClientFilter;
import org.springframework.cloud.gateway.filter.WebsocketRoutingFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * 기본 프로파일(dev)로 게이트웨이가 <b>리액티브 웹 앱으로</b> 뜨고, 띄운 상태에서 라우팅 · 필터 순서가 의도대로인지 본다.
 *
 * <p>local 프로파일이 없으므로 dev 설정을 그대로 쓰고, 배포 때 env 로 들어오는 값만 여기서 채운다. 외부에는 붙지 않는다 —
 * Eureka 클라이언트는 끄고, Redis 는 연결을 첫 명령까지 미루므로 접속하지 않는다.
 *
 * <p>게이트웨이 자체 오류가 봉투로 나가는지는 띄운 서버에 실제 HTTP 로 보낸다 — 필터 · 핸들러 순서와 서버의 URI 해석까지
 * 함께 걸려야 의미가 있다. 경로는 {@link URI} 로 넘겨 클라이언트가 {@code %} 를 다시 인코딩하지 않게 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.profiles.active=dev",
    "eureka.client.enabled=false",
    "API_GATEWAY_PORT=0",
    "AUTH_SERVICE_APP_NAME=auth-service",
    "SURVEILLANCE_SERVICE_APP_NAME=surveillance-service",
    "SERVICE_DISCOVERY_HOSTNAME=localhost",
    "SERVICE_DISCOVERY_PORT=8761",
    "JWT_ACCESS_KEY=sneezecast-gateway-context-test-access-secret-key-0123456789-0123456789",
    "REDIS_MASTER_NAME=mymaster",
    "REDIS_SENTINEL_NODES=localhost:26379",
    "REDIS_PASSWORD=",
    "GATEWAY_TRUSTED_PROXIES=192.168.0.12"
})
class ApiGatewayApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private RouteLocator routeLocator;

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("서블릿 스택이 섞여도 리액티브 웹 앱으로 뜬다 — 아니면 게이트웨이가 포트를 열지 않는다")
    void startsAsReactiveWebApplication() {
        assertThat(context).isInstanceOf(ReactiveWebApplicationContext.class);
    }

    @Test
    @DisplayName("dev 프로파일의 라우트가 서비스 이름으로 풀린다")
    void resolvesDevRoutes() {
        assertThat(routes()).extracting(route -> route.getUri().toString())
            .contains("lb://auth-service", "lb://surveillance-service");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/internal/v1/districts/1111051500", "/internal/v1/reporters/1", "/internal/v1/notifications/broadcasts"})
    @DisplayName("/internal/** 요청은 어떤 라우트에도 매칭되지 않는다")
    void internalPathMatchesNoRoute(String path) {
        assertThat(matchingRouteIds(path)).isEmpty();
    }

    @Test
    @DisplayName("공개 경로는 라우트에 매칭된다 — 위 매칭 검사가 헛돌지 않는다는 대조군")
    void publicPathMatchesItsRoute() {
        assertThat(matchingRouteIds("/api/v1/reports/me")).containsExactly("surveillance-service-reports");
    }

    @Test
    @DisplayName("JWT 필터는 로드밸런서 · 라우팅 필터보다 먼저 돈다 — 뒤에 서면 검증 전에 요청이 업스트림으로 나간다")
    void jwtFilterRunsBeforeRouting() {
        GlobalFilter jwtFilter = context.getBean("jwtAuthGlobalFilter", GlobalFilter.class);
        assertThat(jwtFilter).as("순서는 Ordered 로만 읽힌다").isInstanceOf(Ordered.class);
        int jwtOrder = ((Ordered) jwtFilter).getOrder();

        assertThat(jwtOrder).isLessThan(context.getBean(ReactiveLoadBalancerClientFilter.class).getOrder());
        assertThat(jwtOrder).isLessThan(context.getBean(NettyRoutingFilter.class).getOrder());
        assertThat(context.getBeansOfType(WebsocketRoutingFilter.class)).as("WebSocket 라우팅은 끈다").isEmpty();
    }

    @Test
    @DisplayName("업스트림 timeout 과 CORS 중복 헤더 정리가 dev 설정대로 걸린다")
    void bindsUpstreamTimeoutsAndDefaultFilters() {
        HttpClientProperties httpClient = context.getBean(HttpClientProperties.class);
        assertThat(httpClient.getConnectTimeout()).isEqualTo(2000);
        assertThat(httpClient.getResponseTimeout()).isEqualTo(Duration.ofSeconds(10));

        assertThat(context.getBean(GatewayProperties.class).getDefaultFilters())
            .extracting(filter -> filter.getName())
            .contains("DedupeResponseHeader");
    }

    @Test
    @DisplayName("라우트가 없는 경로는 404 + 봉투 + GATEWAY_002 다")
    void unroutedPathYieldsNotFoundEnvelope() {
        expectEnvelope("/api/v1/unknown-feature/1", HttpStatus.NOT_FOUND, "GATEWAY_002");
    }

    @Test
    @DisplayName("인코딩한 점으로 라우트 밖을 노리는 경로는 400 + 봉투 + GATEWAY_001 이다 — 라우트 매칭 전에 막힌다")
    void encodedTraversalYieldsBadRequestEnvelope() {
        expectEnvelope("/api/v1/auth/%2e%2e/%2e%2e/internal/v1/reporters/1", HttpStatus.BAD_REQUEST, "GATEWAY_001");
    }

    @Test
    @DisplayName("matrix 파라미터로 마디를 숨긴 경로도 400 + GATEWAY_001 이다")
    void matrixParameterTraversalYieldsBadRequestEnvelope() {
        expectEnvelope("/api/v1/auth/..;/..;/internal/v1/reporters/1", HttpStatus.BAD_REQUEST, "GATEWAY_001");
    }

    @Test
    @DisplayName("라우트는 있는데 서비스 인스턴스가 없으면 503 + 봉투 + GATEWAY_003 이다 — 재기동 중 화면이 재시도로 다룬다")
    void missingInstanceYieldsServiceUnavailableEnvelope() {
        expectEnvelope("/api/v1/districts/1111051500", HttpStatus.SERVICE_UNAVAILABLE, "GATEWAY_003");
    }

    private void expectEnvelope(String rawPath, HttpStatus status, String resultCode) {
        WebTestClient.bindToServer().responseTimeout(Duration.ofSeconds(10)).build()
            .get().uri(URI.create("http://localhost:" + port + rawPath))
            .exchange()
            .expectStatus().isEqualTo(status)
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody()
            .jsonPath("$.dataHeader.success").isEqualTo(false)
            .jsonPath("$.dataHeader.resultCode").isEqualTo(resultCode)
            .jsonPath("$.dataBody").doesNotExist();
    }

    private List<Route> routes() {
        return routeLocator.getRoutes().collectList().block();
    }

    private List<String> matchingRouteIds(String path) {
        return routes().stream()
            .filter(route -> Boolean.TRUE.equals(
                Mono.from(route.getPredicate().apply(MockServerWebExchange.from(MockServerHttpRequest.get(path).build()))).block()))
            .map(Route::getId)
            .toList();
    }
}
