package com.sneezecast.domainlayer.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.sneezecast.domainlayer.auth.application.model.NewRefreshSession;
import com.sneezecast.domainlayer.auth.application.model.OAuthLinkTicket;
import com.sneezecast.domainlayer.auth.application.model.OAuthSignupTicket;
import com.sneezecast.domainlayer.auth.application.model.RefreshRotation;
import com.sneezecast.domainlayer.auth.application.model.RefreshRotationResult;
import com.sneezecast.domainlayer.auth.application.model.RefreshRotationResult.Outcome;
import com.sneezecast.domainlayer.auth.application.model.SessionAccessToken;
import com.sneezecast.domainlayer.auth.application.port.out.query.RefreshSessionQueryResult;
import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;
import com.sneezecast.redis.properties.RedisProperties;
import com.sneezecast.redis.properties.enums.RedisMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * refresh 세션 Lua 스크립트를 실제 Redis 로 검증한다 — 원자 회전(0/1/2/3) · 저장과 기기 수 밀어내기 · 삭제 때 access 반환 · 목록의 만료 항목 정리.
 * mock 으로는 스크립트가 무엇을 하는지 알 수 없다. 같은 컨테이너로 로그인 IP 카운터의 되돌리기 스크립트({@link RedisLoginAttemptStoreAdapter})와 비밀번호
 * 재설정 토큰의 1회성 소비 스크립트({@link RedisPasswordResetTokenStoreAdapter})도 본다.
 *
 * <p><b>CI 가 아니고 Docker 도 없을 때만 건너뛴다</b>({@link #dockerUnavailableOutsideCi}). {@code @Testcontainers(disabledWithoutDocker = true)} 를
 * 쓰지 않는 이유 — 그 조건은 "Docker 에 붙지 못함" 을 모두 "Docker 없음" 으로 보고 건너뛴다. CI 에서 클라이언트 · 엔진 API 버전이 어긋나 연결이
 * 실패해도(예: Testcontainers 1.20 과 Docker Engine 29) 테스트가 조용히 빠지고 초록불이 된다. GitHub Actions 는 {@code CI=true} 를 넣으므로 CI 에서는
 * 늘 돌고, 연결이 안 되면 실패한다.
 */
@Testcontainers
@DisabledIf(value = "dockerUnavailableOutsideCi", disabledReason = "로컬에 Docker 가 없다 (CI 에서는 건너뛰지 않는다)")
class RedisRefreshSessionStoreAdapterIntegrationTest {

    private static final long MEMBER_ID = 42L;
    private static final Duration TTL = Duration.ofDays(14);
    private static final Duration GRACE = Duration.ofSeconds(10);
    private static final String INDEX_KEY = "sneezecast:auth:refreshSessions:42";

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate template;
    private RedisRefreshSessionStoreAdapter adapter;
    private Instant now;

    static boolean dockerUnavailableOutsideCi() {
        return System.getenv("CI") == null && !DockerClientFactory.instance().isDockerAvailable();
    }

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        template = new StringRedisTemplate(connectionFactory);
        template.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        RedisProperties properties = new RedisProperties(RedisMode.SENTINEL, null, null, "mymaster", null, null, "localhost:26379", "sneezecast", null);
        adapter = new RedisRefreshSessionStoreAdapter(template, properties);
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    @Test
    @DisplayName("저장하면 세션 해시에 jti · 기기 · 시각 · access 가 남고(refresh 원문 없음) 해시 · 인덱스 모두 TTL 이 걸린다")
    void saveStoresSessionWithTtl() {
        save("session-1", "refresh-1", now);

        Map<Object, Object> hash = template.opsForHash().entries(sessionKey("session-1"));
        assertThat(hash).containsEntry("tokenId", "refresh-1")
            .containsEntry("deviceLabel", "iPhone · Safari")
            .containsEntry("createdAt", String.valueOf(now.toEpochMilli()))
            .containsEntry("lastUsedAt", String.valueOf(now.toEpochMilli()))
            .containsEntry("accessTokenId", "access-session-1")
            .doesNotContainKeys("previousTokenId", "rotatedAt");
        assertThat(template.getExpire(sessionKey("session-1"))).isPositive();
        assertThat(template.getExpire(INDEX_KEY)).isPositive();
        assertThat(template.opsForZSet().score(INDEX_KEY, "session-1")).isEqualTo((double) now.toEpochMilli());
    }

    @Test
    @DisplayName("기기 상한을 넘으면 마지막 사용이 가장 오래된 세션부터 밀어내고 그 세션의 access 를 돌려준다 — 새 세션은 남는다")
    void saveEvictsOldestOverLimit() {
        assertThat(adapter.save(session("old", "r-old", now.minusSeconds(300)), TTL, 2)).isEmpty();
        assertThat(adapter.save(session("mid", "r-mid", now.minusSeconds(200)), TTL, 2)).isEmpty();

        List<SessionAccessToken> evicted = adapter.save(session("new", "r-new", now), TTL, 2);

        assertThat(evicted).containsExactly(new SessionAccessToken("access-old", now.plusSeconds(900)));

        assertThat(template.hasKey(sessionKey("old"))).isFalse();
        assertThat(template.opsForZSet().range(INDEX_KEY, 0, -1)).containsExactly("mid", "new");
        assertThat(adapter.findAll(MEMBER_ID)).extracting(RefreshSessionQueryResult::sessionId).containsExactly("new", "mid");
    }

    @Test
    @DisplayName("현재 jti 로 회전하면(1) jti · 직전 jti · 회전 시각 · 마지막 사용 · access 를 갱신하고 인덱스 score 도 올린다")
    void rotateWithCurrentJti() {
        save("session-1", "refresh-1", now);
        Instant rotatedAt = now.plusSeconds(60);

        RefreshRotationResult result = adapter.rotate(rotation("refresh-1", "refresh-2", rotatedAt), GRACE, TTL);

        assertThat(result.outcome()).isEqualTo(Outcome.ROTATED);
        Map<Object, Object> hash = template.opsForHash().entries(sessionKey("session-1"));
        assertThat(hash).containsEntry("tokenId", "refresh-2")
            .containsEntry("previousTokenId", "refresh-1")
            .containsEntry("rotatedAt", String.valueOf(rotatedAt.toEpochMilli()))
            .containsEntry("lastUsedAt", String.valueOf(rotatedAt.toEpochMilli()))
            .containsEntry("createdAt", String.valueOf(now.toEpochMilli()))
            .containsEntry("accessTokenId", "access-refresh-2");
        assertThat(template.opsForZSet().score(INDEX_KEY, "session-1")).isEqualTo((double) rotatedAt.toEpochMilli());
        assertThat(template.getExpire(sessionKey("session-1"))).isPositive();
    }

    @Test
    @DisplayName("회전 직후 유예 안에 직전 jti 가 오면(2) 동시 재발급 경합이다 — 세션을 건드리지 않는다")
    void rotateWithPreviousJtiWithinGrace() {
        save("session-1", "refresh-1", now);
        adapter.rotate(rotation("refresh-1", "refresh-2", now), GRACE, TTL);

        RefreshRotationResult result = adapter.rotate(rotation("refresh-1", "refresh-3", now.plusSeconds(5)), GRACE, TTL);

        assertThat(result.outcome()).isEqualTo(Outcome.CONCURRENT_ROTATION);
        assertThat(template.opsForHash().get(sessionKey("session-1"), "tokenId")).isEqualTo("refresh-2");
        assertThat(template.opsForHash().get(sessionKey("session-1"), "accessTokenId")).isEqualTo("access-refresh-2");
    }

    @Test
    @DisplayName("유예가 지난 뒤 직전 jti 가 오면(3) 재사용이다 — 세션을 지우고 인덱스에서 빼며, 그 세션의 마지막 access 를 돌려준다")
    void rotateWithPreviousJtiAfterGraceRevokesSession() {
        save("session-1", "refresh-1", now);
        adapter.rotate(rotation("refresh-1", "refresh-2", now), GRACE, TTL);

        RefreshRotationResult result = adapter.rotate(rotation("refresh-1", "refresh-3", now.plusSeconds(11)), GRACE, TTL);

        assertThat(result.outcome()).isEqualTo(Outcome.REUSE_DETECTED);
        assertThat(result.revokedAccessToken()).isEqualTo(new SessionAccessToken("access-refresh-2", now.plusSeconds(900)));
        assertThat(template.hasKey(sessionKey("session-1"))).isFalse();
        assertThat(template.opsForZSet().score(INDEX_KEY, "session-1")).isNull();
    }

    @Test
    @DisplayName("한 번도 회전하지 않은 세션에 모르는 jti 가 와도(3) 재사용으로 보고 폐기한다")
    void rotateWithUnknownJtiRevokesSession() {
        save("session-1", "refresh-1", now);

        RefreshRotationResult result = adapter.rotate(rotation("forged-or-old", "refresh-2", now.plusSeconds(1)), GRACE, TTL);

        assertThat(result.outcome()).isEqualTo(Outcome.REUSE_DETECTED);
        assertThat(result.revokedAccessToken().tokenId()).isEqualTo("access-session-1");
        assertThat(template.hasKey(sessionKey("session-1"))).isFalse();
    }

    @Test
    @DisplayName("세션이 없으면(0) 아무것도 만들지 않는다")
    void rotateWithoutSession() {
        RefreshRotationResult result = adapter.rotate(rotation("refresh-1", "refresh-2", now), GRACE, TTL);

        assertThat(result.outcome()).isEqualTo(Outcome.SESSION_NOT_FOUND);
        assertThat(template.hasKey(sessionKey("session-1"))).isFalse();
        assertThat(template.hasKey(INDEX_KEY)).isFalse();
    }

    @Test
    @DisplayName("삭제는 지운 세션의 마지막 access 를 돌려주고 멱등이다")
    void deleteReturnsAccessAndIsIdempotent() {
        save("session-1", "refresh-1", now);

        assertThat(adapter.delete(MEMBER_ID, "session-1")).hasValue(new SessionAccessToken("access-session-1", now.plusSeconds(900)));
        assertThat(adapter.delete(MEMBER_ID, "session-1")).isEmpty();
        assertThat(template.opsForZSet().size(INDEX_KEY)).isZero();
    }

    @Test
    @DisplayName("남길 세션을 뺀 전체 삭제는 나머지의 access 를 돌려준다 · 남길 세션이 없으면 전부 지운다")
    void deleteAllExcept() {
        save("keep", "r-keep", now);
        save("other-1", "r-1", now.minusSeconds(10));
        save("other-2", "r-2", now.minusSeconds(20));

        assertThat(adapter.deleteAllExcept(MEMBER_ID, "keep")).extracting(SessionAccessToken::tokenId)
            .containsExactlyInAnyOrder("access-other-1", "access-other-2");
        assertThat(adapter.findAll(MEMBER_ID)).extracting(RefreshSessionQueryResult::sessionId).containsExactly("keep");

        assertThat(adapter.deleteAllExcept(MEMBER_ID, null)).extracting(SessionAccessToken::tokenId).containsExactly("access-keep");
        assertThat(template.hasKey(INDEX_KEY)).isFalse();
    }

    @Test
    @DisplayName("목록은 마지막 사용 내림차순이고, 해시가 만료된 인덱스 항목은 빼고 인덱스에서 지운다")
    void findAllSkipsAndCleansExpiredEntries() {
        save("s1", "r-1", now.minusSeconds(60));
        save("s2", "r-2", now);
        template.delete(sessionKey("s1"));

        List<RefreshSessionQueryResult> sessions = adapter.findAll(MEMBER_ID);

        assertThat(sessions).extracting(RefreshSessionQueryResult::sessionId).containsExactly("s2");
        assertThat(sessions.getFirst().deviceLabel()).isEqualTo("iPhone · Safari");
        assertThat(sessions.getFirst().lastUsedAt()).isEqualTo(now);
        assertThat(template.opsForZSet().range(INDEX_KEY, 0, -1)).containsExactly("s2");
    }

    @Test
    @DisplayName("다른 회원의 세션 키는 건드리지 않는다 — 키에 회원 ID 가 들어간다")
    void sessionsAreScopedByMember() {
        save("session-1", "refresh-1", now);

        assertThat(adapter.delete(7L, "session-1")).isEmpty();
        assertThat(adapter.deleteAllExcept(7L, null)).isEmpty();
        assertThat(template.hasKey(sessionKey("session-1"))).isTrue();
    }

    @Test
    @DisplayName("로그인 IP 카운터 되돌리기는 1 내리되 0 아래로 가지 않고, 키가 없으면 만들지 않으며, 윈도우 TTL 을 그대로 둔다")
    void loginIpAttemptDecrementNeverGoesBelowZero() {
        RedisLoginAttemptStoreAdapter loginAdapter = new RedisLoginAttemptStoreAdapter(template, new RedisProperties(RedisMode.SENTINEL, null, null,
            "mymaster", null, null, "localhost:26379", "sneezecast", null));
        String key = "sneezecast:auth:loginFailIp:203.0.113.10";

        loginAdapter.decreaseIpAttemptCount("203.0.113.10");
        assertThat(template.hasKey(key)).as("없는 키는 만들지 않는다").isFalse();

        assertThat(loginAdapter.increaseIpAttemptCount("203.0.113.10", Duration.ofHours(1))).isEqualTo(1L);
        loginAdapter.decreaseIpAttemptCount("203.0.113.10");
        loginAdapter.decreaseIpAttemptCount("203.0.113.10");

        assertThat(template.opsForValue().get(key)).isEqualTo("0");
        assertThat(template.getExpire(key)).as("DECR 은 고정 윈도우 TTL 을 지우지 않는다").isPositive();
    }

    @Test
    @DisplayName("재설정 토큰은 해시 키로 저장되고(TTL), 소비는 GET+DEL 이 한 번에 일어난다 — 두 번째 소비 · 없는 토큰은 empty 이고 키가 남지 않는다")
    void passwordResetTokenIsConsumedOnce() {
        RedisPasswordResetTokenStoreAdapter resetAdapter = new RedisPasswordResetTokenStoreAdapter(template, new RedisProperties(RedisMode.SENTINEL, null,
            null, "mymaster", null, null, "localhost:26379", "sneezecast", null));
        String hash = "b".repeat(64);
        String key = "sneezecast:auth:passwordResetToken:" + hash;

        resetAdapter.saveToken(hash, "user@example.com", Duration.ofMinutes(15));
        assertThat(template.getExpire(key)).isPositive();

        assertThat(resetAdapter.consumeToken(hash)).hasValue("user@example.com");
        assertThat(template.hasKey(key)).isFalse();
        assertThat(resetAdapter.consumeToken(hash)).isEmpty();
        assertThat(resetAdapter.consumeToken("c".repeat(64))).isEmpty();
    }

    @Test
    @DisplayName("같은 재설정 토큰으로 동시에 소비해도 이메일을 받는 쪽은 하나뿐이다")
    void concurrentConsumptionYieldsSingleWinner() throws Exception {
        RedisPasswordResetTokenStoreAdapter resetAdapter = new RedisPasswordResetTokenStoreAdapter(template, new RedisProperties(RedisMode.SENTINEL, null,
            null, "mymaster", null, null, "localhost:26379", "sneezecast", null));
        String hash = "d".repeat(64);
        resetAdapter.saveToken(hash, "user@example.com", Duration.ofMinutes(15));

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return resetAdapter.consumeToken(hash).isPresent();
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) {
                winners += result.get(10, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("카카오 state · 가입표 · 연결 확인표는 TTL 로 저장되고 GET+DEL 로 한 번만 꺼낸다 — 두 번째 소비 · 없는 값은 empty, 키가 남지 않는다")
    void oauthValuesAreConsumedOnce() {
        RedisOAuthLoginStoreAdapter oauthAdapter = new RedisOAuthLoginStoreAdapter(template, new RedisProperties(RedisMode.SENTINEL, null, null,
            "mymaster", null, null, "localhost:26379", "sneezecast", null));
        String hash = "e".repeat(64);
        OAuthSignupTicket signupTicket = new OAuthSignupTicket(OAuthProvider.KAKAO, "user@example.com", "재채기😀탐정");

        oauthAdapter.saveState("state-1", OAuthProvider.KAKAO, Duration.ofMinutes(10));
        oauthAdapter.saveSignupTicket(hash, signupTicket, Duration.ofMinutes(30));
        oauthAdapter.saveLinkTicket(hash, new OAuthLinkTicket(MEMBER_ID, OAuthProvider.KAKAO), Duration.ofMinutes(10));
        assertThat(template.getExpire("sneezecast:auth:oauthState:state-1")).isPositive();
        assertThat(template.getExpire("sneezecast:auth:oauthSignupTicket:" + hash)).isPositive();
        assertThat(template.getExpire("sneezecast:auth:oauthLinkTicket:" + hash)).isPositive();

        assertThat(oauthAdapter.consumeState("state-1")).hasValue(OAuthProvider.KAKAO);
        assertThat(oauthAdapter.consumeState("state-1")).isEmpty();
        assertThat(oauthAdapter.consumeSignupTicket(hash)).hasValue(signupTicket);
        assertThat(oauthAdapter.consumeSignupTicket(hash)).isEmpty();
        assertThat(oauthAdapter.consumeLinkTicket(hash)).hasValue(new OAuthLinkTicket(MEMBER_ID, OAuthProvider.KAKAO));
        assertThat(oauthAdapter.consumeLinkTicket(hash)).isEmpty();
        assertThat(oauthAdapter.consumeState("never-issued")).isEmpty();
        assertThat(template.keys("sneezecast:auth:oauth*")).isEmpty();
    }

    @Test
    @DisplayName("카카오 인가 IP 카운터는 1씩 오르고 첫 증가부터 창 길이 TTL 이 걸린다")
    void oauthAuthorizeIpCounterHasTtl() {
        RedisOAuthLoginStoreAdapter oauthAdapter = new RedisOAuthLoginStoreAdapter(template, new RedisProperties(RedisMode.SENTINEL, null, null,
            "mymaster", null, null, "localhost:26379", "sneezecast", null));

        assertThat(oauthAdapter.increaseAuthorizeIpCount("203.0.113.10", Duration.ofMinutes(10))).isEqualTo(1L);
        assertThat(oauthAdapter.increaseAuthorizeIpCount("203.0.113.10", Duration.ofMinutes(10))).isEqualTo(2L);
        assertThat(template.getExpire("sneezecast:auth:oauthAuthorizeIp:203.0.113.10")).isPositive();
    }

    @Test
    @DisplayName("같은 state 로 동시에 소비해도 받는 쪽은 하나뿐이다 (콜백 이중 제출 · 재전송)")
    void concurrentStateConsumptionYieldsSingleWinner() throws Exception {
        RedisOAuthLoginStoreAdapter oauthAdapter = new RedisOAuthLoginStoreAdapter(template, new RedisProperties(RedisMode.SENTINEL, null, null,
            "mymaster", null, null, "localhost:26379", "sneezecast", null));
        oauthAdapter.saveState("state-race", OAuthProvider.KAKAO, Duration.ofMinutes(10));

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return oauthAdapter.consumeState("state-race").isPresent();
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) {
                winners += result.get(10, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private void save(String sessionId, String refreshTokenId, Instant issuedAt) {
        adapter.save(session(sessionId, refreshTokenId, issuedAt), TTL, 5);
    }

    private NewRefreshSession session(String sessionId, String refreshTokenId, Instant issuedAt) {
        return NewRefreshSession.builder().memberId(MEMBER_ID).sessionId(sessionId).refreshTokenId(refreshTokenId).deviceLabel("iPhone · Safari")
            .accessToken(new SessionAccessToken("access-" + sessionId, now.plusSeconds(900))).issuedAt(issuedAt).build();
    }

    private RefreshRotation rotation(String presented, String next, Instant rotatedAt) {
        return RefreshRotation.builder().memberId(MEMBER_ID).sessionId("session-1").presentedTokenId(presented).newTokenId(next)
            .newAccessToken(new SessionAccessToken("access-" + next, now.plusSeconds(900))).rotatedAt(rotatedAt).build();
    }

    private static String sessionKey(String sessionId) {
        return "sneezecast:auth:refreshSession:42:" + sessionId;
    }
}
