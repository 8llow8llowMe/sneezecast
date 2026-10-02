package com.sneezecast.domainlayer.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.model.NewRefreshSession;
import com.sneezecast.domainlayer.auth.application.model.RefreshRotation;
import com.sneezecast.domainlayer.auth.application.model.RefreshRotationResult;
import com.sneezecast.domainlayer.auth.application.model.RefreshRotationResult.Outcome;
import com.sneezecast.domainlayer.auth.application.model.SessionAccessToken;
import com.sneezecast.domainlayer.auth.application.port.out.query.RefreshSessionQueryResult;
import com.sneezecast.redis.properties.RedisProperties;
import com.sneezecast.redis.properties.enums.RedisMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 스크립트에 넘기는 키 · 인자 조립과 응답 해석을 본다. 스크립트 자체의 동작(원자 회전 · 밀어내기 · 정리)은 실제 Redis 가 필요해
 * {@link RedisRefreshSessionStoreAdapterIntegrationTest} 가 본다.
 */
class RedisRefreshSessionStoreAdapterTest {

    private static final long MEMBER_ID = 42L;
    private static final String SESSION_KEY = "sneezecast:auth:refreshSession:42:session-1";
    private static final String SESSION_KEY_PREFIX = "sneezecast:auth:refreshSession:42:";
    private static final String INDEX_KEY = "sneezecast:auth:refreshSessions:42";
    private static final Instant NOW = Instant.ofEpochMilli(1_790_000_000_000L);
    private static final Instant ACCESS_EXPIRES_AT = Instant.ofEpochMilli(1_790_000_900_000L);

    private RecordingTemplate template;
    private RedisRefreshSessionStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        template = new RecordingTemplate();
        RedisProperties properties = new RedisProperties(RedisMode.SENTINEL, null, null, "mymaster", null, null, "localhost:26379", "sneezecast", null);
        adapter = new RedisRefreshSessionStoreAdapter(template, properties);
    }

    @Test
    @DisplayName("저장은 세션 해시 · 인덱스 키와, refresh jti(원문 아님) · 기기 이름 · 시각(ms) · access · TTL(ms) · 기기 상한 · 해시 접두어를 넘기고, 밀어낸 세션의 access 를 돌려준다")
    void saveArguments() {
        template.nextResult = List.of("evicted-access", "1790000900000", "", "");

        List<SessionAccessToken> evicted = adapter.save(NewRefreshSession.builder().memberId(MEMBER_ID).sessionId("session-1").refreshTokenId("refresh-jti")
            .deviceLabel("iPhone · Safari").accessToken(new SessionAccessToken("access-jti", ACCESS_EXPIRES_AT)).issuedAt(NOW).build(), Duration.ofDays(14), 5);

        assertThat(template.script).isSameAs(RedisRefreshSessionStoreAdapter.SAVE_SCRIPT);
        assertThat(template.keys).containsExactly(SESSION_KEY, INDEX_KEY);
        assertThat(template.args).containsExactly("session-1", "refresh-jti", "iPhone · Safari", "1790000000000", "access-jti", "1790000900000",
            String.valueOf(Duration.ofDays(14).toMillis()), "5", SESSION_KEY_PREFIX);
        assertThat(evicted).containsExactly(new SessionAccessToken("evicted-access", ACCESS_EXPIRES_AT));
    }

    @Test
    @DisplayName("회전은 제시 jti · 새 jti · 지금 · 유예 · TTL · 새 access · sessionId 를 넘긴다")
    void rotateArguments() {
        template.nextResult = List.of("1");

        RefreshRotationResult result = adapter.rotate(rotation(), Duration.ofSeconds(10), Duration.ofDays(14));

        assertThat(result.outcome()).isEqualTo(Outcome.ROTATED);
        assertThat(template.script).isSameAs(RedisRefreshSessionStoreAdapter.ROTATE_SCRIPT);
        assertThat(template.keys).containsExactly(SESSION_KEY, INDEX_KEY);
        assertThat(template.args).containsExactly("presented-jti", "new-jti", "1790000000000", "10000", String.valueOf(Duration.ofDays(14).toMillis()),
            "new-access-jti", "1790000900000", "session-1");
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"0, SESSION_NOT_FOUND", "1, ROTATED", "2, CONCURRENT_ROTATION"})
    @DisplayName("회전 결과 코드를 해석한다")
    void rotateOutcomes(String code, Outcome expected) {
        template.nextResult = List.of(code);

        RefreshRotationResult result = adapter.rotate(rotation(), Duration.ofSeconds(10), Duration.ofDays(14));

        assertThat(result.outcome()).isEqualTo(expected);
        assertThat(result.revokedAccessToken()).isNull();
    }

    @Test
    @DisplayName("재사용 감지(3)는 폐기한 세션의 access jti · 만료를 함께 돌려준다 — 기록이 없으면 null")
    void rotateReuseCarriesRevokedAccess() {
        template.nextResult = List.of("3", "old-access-jti", "1790000900000");
        RefreshRotationResult result = adapter.rotate(rotation(), Duration.ofSeconds(10), Duration.ofDays(14));

        assertThat(result.outcome()).isEqualTo(Outcome.REUSE_DETECTED);
        assertThat(result.revokedAccessToken()).isEqualTo(new SessionAccessToken("old-access-jti", ACCESS_EXPIRES_AT));

        template.nextResult = List.of("3", "", "");
        assertThat(adapter.rotate(rotation(), Duration.ofSeconds(10), Duration.ofDays(14)).revokedAccessToken()).isNull();
    }

    @Test
    @DisplayName("모르는 회전 응답은 503 으로 바꾼다 — 성공으로 오인하지 않는다")
    void rotateUnexpectedReply() {
        template.nextResult = List.of("9");

        assertThatThrownBy(() -> adapter.rotate(rotation(), Duration.ofSeconds(10), Duration.ofDays(14)))
            .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.SESSION_STORE_UNAVAILABLE));
    }

    @Test
    @DisplayName("삭제는 세션 키 · 인덱스 키 · sessionId 를 넘기고, 지운 세션의 access 를 돌려준다 — 없던 세션이면 empty")
    void deleteArgumentsAndResult() {
        template.nextResult = List.of("access-jti", "1790000900000");
        assertThat(adapter.delete(MEMBER_ID, "session-1")).hasValue(new SessionAccessToken("access-jti", ACCESS_EXPIRES_AT));
        assertThat(template.keys).containsExactly(SESSION_KEY, INDEX_KEY);
        assertThat(template.args).containsExactly("session-1");

        template.nextResult = List.of("", "");
        assertThat(adapter.delete(MEMBER_ID, "session-1")).isEmpty();
    }

    @Test
    @DisplayName("전체 삭제는 인덱스 키 · 해시 접두어 · 남길 세션(없으면 빈 문자열)을 넘기고, 기록이 있는 access 만 돌려준다")
    void deleteAllExceptArgumentsAndResult() {
        template.nextResult = List.of("a", "1790000900000", "", "", "b", "1790000900000");

        List<SessionAccessToken> revoked = adapter.deleteAllExcept(MEMBER_ID, null);

        assertThat(template.script).isSameAs(RedisRefreshSessionStoreAdapter.DELETE_ALL_EXCEPT_SCRIPT);
        assertThat(template.keys).containsExactly(INDEX_KEY);
        assertThat(template.args).containsExactly(SESSION_KEY_PREFIX, "");
        assertThat(revoked).extracting(SessionAccessToken::tokenId).containsExactly("a", "b");

        template.nextResult = List.of();
        adapter.deleteAllExcept(MEMBER_ID, "session-1");
        assertThat(template.args).containsExactly(SESSION_KEY_PREFIX, "session-1");
    }

    @Test
    @DisplayName("목록은 넷씩 끊어 세션으로 읽는다")
    void findAllParsesQuadruples() {
        template.nextResult = List.of("s2", "Mac · Chrome", "1790000000000", "1790000500000", "s1", "", "1789990000000", "1789990000000");

        List<RefreshSessionQueryResult> sessions = adapter.findAll(MEMBER_ID);

        assertThat(template.keys).containsExactly(INDEX_KEY);
        assertThat(template.args).containsExactly(SESSION_KEY_PREFIX);
        assertThat(sessions).extracting(RefreshSessionQueryResult::sessionId).containsExactly("s2", "s1");
        assertThat(sessions.getFirst().deviceLabel()).isEqualTo("Mac · Chrome");
        assertThat(sessions.getFirst().createdAt()).isEqualTo(NOW);
        assertThat(sessions.getFirst().lastUsedAt()).isEqualTo(Instant.ofEpochMilli(1_790_000_500_000L));
    }

    @Test
    @DisplayName("Redis 장애는 봉투 없는 500 대신 AUTH_017(503)이다")
    void storeFailureBecomesServiceUnavailable() {
        template.nextFailure = new RedisConnectionFailureException("down");

        assertThatThrownBy(() -> adapter.findAll(MEMBER_ID))
            .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.SESSION_STORE_UNAVAILABLE));
        assertThatThrownBy(() -> adapter.rotate(rotation(), Duration.ofSeconds(10), Duration.ofDays(14))).isInstanceOf(AuthException.class);
    }

    private static RefreshRotation rotation() {
        return RefreshRotation.builder().memberId(MEMBER_ID).sessionId("session-1").presentedTokenId("presented-jti").newTokenId("new-jti")
            .newAccessToken(new SessionAccessToken("new-access-jti", ACCESS_EXPIRES_AT)).rotatedAt(NOW).build();
    }

    /** 스크립트 실행만 가로채 인자를 기록하고 정해 둔 응답을 돌려준다. */
    private static class RecordingTemplate extends StringRedisTemplate {

        RedisScript<?> script;
        List<String> keys;
        List<Object> args;
        Object nextResult;
        RuntimeException nextFailure;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            this.script = script;
            this.keys = keys;
            this.args = List.of(args);
            if (nextFailure != null) {
                throw nextFailure;
            }
            return (T) nextResult;
        }
    }
}
