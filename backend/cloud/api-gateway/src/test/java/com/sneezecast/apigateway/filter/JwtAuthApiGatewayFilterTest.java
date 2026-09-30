package com.sneezecast.apigateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sneezecast.apigateway.handler.JwtAuthExceptionWebHandler;
import com.sneezecast.apigateway.jwt.AccessTokenBlacklistChecker;
import com.sneezecast.apigateway.jwt.JwtVerifier;
import com.sneezecast.apigateway.jwt.properties.JwtVerificationProperties;
import com.sneezecast.redis.properties.RedisProperties;
import com.sneezecast.redis.properties.enums.RedisMode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Jwts.SIG;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 게이트웨이의 토큰 거부가 <b>실제로 나가는 응답</b>까지 검증한다.
 *
 * <p>필터 · 블랙리스트 확인 · 예외 핸들러를 실제 체인과 같은 모양으로 엮는다 —
 * {@code ExceptionHandlingWebHandler} 가 핸들러를 {@code onErrorResume} 으로 거는 그 자리다.
 * 필터만 따로 보면 "예외를 던졌다" 까지밖에 못 말하는데, 결함은 <b>그 예외가 응답이 될 때</b> 일어난다 —
 * 받는 쪽이 없거나 잡지 못한 예외가 있으면 500 으로 나간다. Redis 만 mock 이다.
 *
 * <p>토큰은 auth-service 가 발급하는 모양(security-core {@code JwtAuthProvider}: HS512, {@code sub} · {@code role} ·
 * {@code scope} · {@code jti})으로 만든다.
 */
class JwtAuthApiGatewayFilterTest {

    private static final String ACCESS_SECRET = "sneezecast-gateway-test-access-secret-key-0123456789-0123456789-0123456789";
    private static final String OTHER_SECRET = "sneezecast-gateway-test-other-secret-key-9876543210-9876543210-9876543210";
    private static final String MEMBER_ID_HEADER = "X-Authenticated-Member-Id";
    private static final String LEGACY_MEMBER_ID_HEADER = "X-Member-Id";
    private static final String MEMBER_ID = "1234567890123456789";
    private static final String TOKEN_ID = "token-id";
    private static final String BLACKLIST_KEY = "sneezecast:auth:accessTokenBlacklist:" + TOKEN_ID;

    private RedisTemplate<String, Object> redisTemplate;

    /** 체인까지 도달한 요청. 도달하지 않았으면 null 이다. */
    private AtomicReference<ServerWebExchange> forwarded;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        forwarded = new AtomicReference<>();
    }

    @Test
    @DisplayName("만료 토큰은 401 + 봉투 + SECURITY_002 다 — 500 이면 클라이언트가 재발급을 걸지 않는다")
    void expiredTokenYieldsUnauthorizedEnvelope() {
        MockServerWebExchange exchange = dispatch(bearer(token(ACCESS_SECRET, Duration.ofHours(-26))));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(body(exchange))
            .contains("\"success\":false")
            .contains("\"resultCode\":\"SECURITY_002\"");
        assertThat(forwarded.get()).as("거부된 토큰은 업스트림까지 가지 않는다").isNull();
    }

    @Test
    @DisplayName("형식이 깨진 토큰은 401 + 봉투 + SECURITY_005 다")
    void malformedTokenYieldsUnauthorizedEnvelope() {
        MockServerWebExchange exchange = dispatch("Bearer garbage");

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(body(exchange)).contains("\"resultCode\":\"SECURITY_005\"");
        assertThat(forwarded.get()).isNull();
    }

    @Test
    @DisplayName("서명이 다른 토큰은 401 + 봉투 + SECURITY_004 다 — 시크릿 로테이션이 전면 500 이 되지 않는다")
    void wrongSignatureYieldsUnauthorizedEnvelope() {
        MockServerWebExchange exchange = dispatch(bearer(token(OTHER_SECRET, Duration.ofHours(1))));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(body(exchange)).contains("\"resultCode\":\"SECURITY_004\"");
        assertThat(forwarded.get()).isNull();
    }

    @Test
    @DisplayName("서명 없는 토큰(alg=none)은 401 + 봉투 + SECURITY_003 이다 — jjwt 의 다른 예외 종류가 500 으로 새지 않는다")
    void unsignedTokenYieldsUnauthorizedEnvelope() {
        String unsigned = Jwts.builder().subject(MEMBER_ID).claim("role", "ADMIN").compact();

        MockServerWebExchange exchange = dispatch(bearer(unsigned));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(body(exchange)).contains("\"resultCode\":\"SECURITY_003\"");
        assertThat(forwarded.get()).isNull();
    }

    @Test
    @DisplayName("블랙리스트에 오른 토큰은 401 + 봉투 + SECURITY_007 이다")
    void revokedTokenYieldsUnauthorizedEnvelope() {
        when(redisTemplate.hasKey(BLACKLIST_KEY)).thenReturn(true);

        MockServerWebExchange exchange = dispatch(bearer(token(ACCESS_SECRET, Duration.ofHours(1))));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(body(exchange)).contains("\"resultCode\":\"SECURITY_007\"");
        assertThat(forwarded.get()).isNull();
    }

    @Test
    @DisplayName("소문자 bearer 로 보내도 폐기 토큰은 401 + SECURITY_007 이다 — 서비스는 scheme 대소문자를 무시하므로 여기서 놓치면 블랙리스트가 우회된다")
    void lowercaseBearerSchemeIsStillVerified() {
        when(redisTemplate.hasKey(BLACKLIST_KEY)).thenReturn(true);

        MockServerWebExchange exchange = dispatch("bearer " + token(ACCESS_SECRET, Duration.ofHours(1)));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(body(exchange)).contains("\"resultCode\":\"SECURITY_007\"");
        assertThat(forwarded.get()).isNull();
    }

    @ParameterizedTest(name = "Authorization: \"{0}\"")
    @ValueSource(strings = {"Basic dXNlcjpwYXNzd29yZA==", "Bearer", "Bearer ", "Token abc"})
    @DisplayName("Authorization 이 있는데 Bearer 토큰으로 읽히지 않으면 401 + SECURITY_005 다 — 토큰 없음으로 통과시키면 검증 없이 헤더가 업스트림에 간다")
    void nonBearerAuthorizationIsRejected(String authorization) {
        MockServerWebExchange exchange = dispatch(authorization);

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(body(exchange)).contains("\"resultCode\":\"SECURITY_005\"");
        assertThat(forwarded.get()).isNull();
    }

    @Test
    @DisplayName("jti 가 없는 토큰은 401 + SECURITY_003 이다 — 폐기 여부를 확인할 수 없는 토큰은 받지 않는다")
    void tokenWithoutIdIsRejected() {
        String withoutId = Jwts.builder()
            .subject(MEMBER_ID)
            .claim("role", "USER")
            .expiration(Date.from(Instant.now().plus(Duration.ofHours(1))))
            .signWith(Keys.hmacShaKeyFor(ACCESS_SECRET.getBytes(StandardCharsets.UTF_8)), SIG.HS512)
            .compact();

        MockServerWebExchange exchange = dispatch(bearer(withoutId));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(body(exchange)).contains("\"resultCode\":\"SECURITY_003\"");
        assertThat(forwarded.get()).isNull();
    }

    @Test
    @DisplayName("블랙리스트 조회는 이벤트 루프가 아닌 boundedElastic 스레드에서 한다 — 블로킹 Redis 호출이 Netty 스레드를 묶지 않는다")
    void blacklistLookupRunsOffTheEventLoop() {
        AtomicReference<String> lookupThread = new AtomicReference<>();
        when(redisTemplate.hasKey(anyString())).thenAnswer(invocation -> {
            lookupThread.set(Thread.currentThread().getName());
            return false;
        });

        dispatch(bearer(token(ACCESS_SECRET, Duration.ofHours(1))));

        assertThat(forwarded.get()).isNotNull();
        assertThat(lookupThread.get()).startsWith("boundedElastic");
    }

    @Test
    @DisplayName("블랙리스트 조회가 타임아웃이면 503 + 봉투 + SECURITY_008 이다 — 연결 실패가 아닌 Redis 오류도 500 이 되지 않는다")
    void blacklistLookupTimeoutYieldsServiceUnavailableEnvelope() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new QueryTimeoutException("redis command timed out"));

        MockServerWebExchange exchange = dispatch(bearer(token(ACCESS_SECRET, Duration.ofHours(1))));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(body(exchange)).contains("\"resultCode\":\"SECURITY_008\"");
        assertThat(forwarded.get()).isNull();
    }

    @Test
    @DisplayName("fail-open 이면 블랙리스트 조회 실패에도 통과시킨다")
    void blacklistLookupFailureWithFailOpenPassesThrough() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new QueryTimeoutException("redis command timed out"));

        MockServerWebExchange exchange = dispatch(bearer(token(ACCESS_SECRET, Duration.ofHours(1))), true);

        assertThat(forwarded.get()).isNotNull();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("토큰이 없으면 통과한다 — 이게 깨지면 미로그인 공개 API 가 전부 막힌다")
    void requestWithoutTokenPassesThrough() {
        MockServerWebExchange exchange = dispatch(null);

        assertThat(forwarded.get()).as("체인까지 도달해야 한다").isNotNull();
        assertThat(exchange.getResponse().getStatusCode()).as("게이트웨이가 상태를 정하지 않는다").isNull();
        assertThat(forwarded.get().getRequest().getHeaders().getFirst(MEMBER_ID_HEADER))
            .as("인증되지 않은 요청에 회원 헤더를 붙이지 않는다").isNull();
    }

    @Test
    @DisplayName("클라이언트가 보낸 회원 헤더는 토큰이 없어도 지워진다")
    void spoofedMemberHeaderIsRemoved() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/reports").header(MEMBER_ID_HEADER, "1").build();

        dispatch(MockServerWebExchange.from(request), false);

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getRequest().getHeaders().getFirst(MEMBER_ID_HEADER)).isNull();
    }

    @Test
    @DisplayName("정상 토큰에 위조 회원 헤더가 붙어 오면 sub 값 하나로 바뀌고 X-Member-Id 는 지워진다")
    void forgedMemberHeadersAreReplacedBySubject() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/reports")
            .header(HttpHeaders.AUTHORIZATION, bearer(token(ACCESS_SECRET, Duration.ofHours(1))))
            .header(MEMBER_ID_HEADER, "1")
            .header(LEGACY_MEMBER_ID_HEADER, "2")
            .build();

        dispatch(MockServerWebExchange.from(request), false);

        assertThat(forwarded.get()).isNotNull();
        HttpHeaders forwardedHeaders = forwarded.get().getRequest().getHeaders();
        assertThat(forwardedHeaders.get(MEMBER_ID_HEADER)).containsExactly(MEMBER_ID);
        assertThat(forwardedHeaders.containsKey(LEGACY_MEMBER_ID_HEADER)).isFalse();
    }

    @Test
    @DisplayName("정상 토큰은 회원 헤더를 달고 통과한다 — 봉투 변환이 정상 경로를 건드리지 않았다")
    void validTokenPassesThroughWithMemberHeader() {
        MockServerWebExchange exchange = dispatch(bearer(token(ACCESS_SECRET, Duration.ofHours(1))));

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getRequest().getHeaders().getFirst(MEMBER_ID_HEADER)).isEqualTo(MEMBER_ID);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    private MockServerWebExchange dispatch(String authorization) {
        return dispatch(authorization, false);
    }

    /** @param authorization {@code null} 이면 Authorization 헤더 자체를 붙이지 않는다 */
    private MockServerWebExchange dispatch(String authorization, boolean blacklistFailOpen) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/api/v1/reports");
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(request.build());
        dispatch(exchange, blacklistFailOpen);
        return exchange;
    }

    /** 필터 → (오류면) 예외 핸들러. {@code ExceptionHandlingWebHandler} 가 거는 것과 같은 결선이다. */
    private void dispatch(MockServerWebExchange exchange, boolean blacklistFailOpen) {
        JwtVerificationProperties jwtProperties = new JwtVerificationProperties(ACCESS_SECRET, blacklistFailOpen);
        RedisProperties redisProperties = new RedisProperties(RedisMode.SENTINEL, null, null, "mymaster", null, null, null, "sneezecast", null);
        AccessTokenBlacklistChecker blacklistChecker = new AccessTokenBlacklistChecker(redisTemplate, jwtProperties, redisProperties);
        JwtAuthApiGatewayFilter filter = new JwtAuthApiGatewayFilter(new JwtVerifier(jwtProperties), blacklistChecker);
        JwtAuthExceptionWebHandler handler = new JwtAuthExceptionWebHandler(new ObjectMapper());

        filter.apply(new JwtAuthApiGatewayFilter.Config())
            .filter(exchange, forwardedExchange -> {
                forwarded.set(forwardedExchange);
                return Mono.empty();
            })
            .onErrorResume(error -> handler.handle(exchange, error))
            .block();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    /**
     * auth-service 발급 모양의 access token.
     *
     * @param untilExpiry 음수면 이미 만료된 토큰이다
     */
    private static String token(String secret, Duration untilExpiry) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
            .id(TOKEN_ID)
            .subject(MEMBER_ID)
            .claim("role", "USER")
            .claim("scope", "report:write")
            .issuedAt(Date.from(now.minus(Duration.ofHours(27))))
            .expiration(Date.from(now.plus(untilExpiry)))
            .signWith(key, SIG.HS512)
            .compact();
    }

    private static String body(MockServerWebExchange exchange) {
        return exchange.getResponse().getBodyAsString().block();
    }
}
