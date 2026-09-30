package com.sneezecast.domainlayer.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sneezecast.redis.properties.RedisProperties;
import com.sneezecast.redis.properties.enums.RedisMode;
import com.sneezecast.security.common.exception.SecurityErrorCode;
import com.sneezecast.security.common.exception.SecurityJwtException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

class RedisAccessTokenBlacklistAdapterTest {

    private static final String TOKEN_ID = "0f8c2a4e-8d1b-4c1e-9a55-1f2e3d4c5b6a";
    /** 게이트웨이 AccessTokenBlacklistChecker 가 조립하는 키와 같은 문자열이다. 여기를 바꾸면 게이트웨이도 함께 바꾼다. */
    private static final String GATEWAY_KEY = "sneezecast:auth:accessTokenBlacklist:" + TOKEN_ID;

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @Test
    @DisplayName("조회 키가 게이트웨이와 같은 {prefix}:auth:accessTokenBlacklist:{jti} 이다 — 어긋나면 폐기 토큰이 게이트웨이를 통과한다")
    void lookupKeyMatchesGateway() {
        when(redisTemplate.hasKey(GATEWAY_KEY)).thenReturn(true);

        assertThat(adapter("sneezecast").isRevoked(TOKEN_ID)).isTrue();
        verify(redisTemplate).hasKey(GATEWAY_KEY);
    }

    @ParameterizedTest(name = "prefix=\"{0}\"")
    @ValueSource(strings = {"", "  ", " sneezecast "})
    @DisplayName("prefix 가 비었거나 공백을 품어도 게이트웨이와 같은 규칙(normalizedKeyPrefix)으로 키를 만든다")
    void normalizesPrefixLikeGateway(String prefix) {
        when(redisTemplate.hasKey(GATEWAY_KEY)).thenReturn(true);

        assertThat(adapter(prefix).isRevoked(TOKEN_ID)).isTrue();
    }

    @Test
    @DisplayName("키가 없으면 폐기되지 않은 토큰이다 — Redis 가 null 을 돌려줘도 같다")
    void missingKeyIsNotRevoked() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        assertThat(adapter("sneezecast").isRevoked(TOKEN_ID)).isFalse();

        when(redisTemplate.hasKey(anyString())).thenReturn(null);
        assertThat(adapter("sneezecast").isRevoked(TOKEN_ID)).isFalse();
    }

    @Test
    @DisplayName("Redis 연결 실패는 503 SECURITY_008 로 바뀐다 — 필터가 잡는 예외라 봉투로 나간다")
    void connectionFailureBecomesVerificationUnavailable() {
        RedisConnectionFailureException cause = new RedisConnectionFailureException("down");
        when(redisTemplate.hasKey(anyString())).thenThrow(cause);

        assertThatThrownBy(() -> adapter("sneezecast").isRevoked(TOKEN_ID))
            .isInstanceOfSatisfying(SecurityJwtException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo(SecurityErrorCode.TOKEN_VERIFICATION_UNAVAILABLE);
                assertThat(e.getErrorCode().getCode()).isEqualTo("SECURITY_008");
                assertThat(e.getErrorCode().getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(e.getCause()).isSameAs(cause);
            });
    }

    @Test
    @DisplayName("명령 타임아웃도 같은 SECURITY_008 이다 — 연결 실패만 잡으면 타임아웃이 봉투 없는 500 으로 샌다")
    void commandTimeoutBecomesVerificationUnavailable() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new QueryTimeoutException("timeout"));

        assertThatThrownBy(() -> adapter("sneezecast").isRevoked(TOKEN_ID))
            .isInstanceOfSatisfying(SecurityJwtException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(SecurityErrorCode.TOKEN_VERIFICATION_UNAVAILABLE));
    }

    @Test
    @DisplayName("폐기 등록은 같은 키에 남은 유효 시간만큼 TTL 을 건다")
    @SuppressWarnings("unchecked")
    void revokeStoresKeyWithRemainingValidity() {
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        adapter("sneezecast").revoke(TOKEN_ID, Duration.ofMinutes(7));

        verify(valueOperations).set(GATEWAY_KEY, "revoked", Duration.ofMinutes(7));
    }

    @Test
    @DisplayName("남은 유효 시간이 0 이하면 이미 만료된 토큰이라 저장하지 않는다")
    void revokeSkipsExpiredToken() {
        RedisAccessTokenBlacklistAdapter adapter = adapter("sneezecast");

        adapter.revoke(TOKEN_ID, Duration.ZERO);
        adapter.revoke(TOKEN_ID, Duration.ofSeconds(-1));
        adapter.revoke(TOKEN_ID, null);

        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("jti 가 비면 거부한다 — 빈 jti 키는 어떤 토큰도 막지 못한다")
    void revokeRejectsBlankTokenId() {
        assertThatThrownBy(() -> adapter("sneezecast").revoke(" ", Duration.ofMinutes(1)))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("폐기 저장 실패는 삼키지 않는다 — 삼키면 로그아웃이 성공으로 보이는데 토큰은 계속 통한다")
    @SuppressWarnings("unchecked")
    void revokePropagatesStorageFailure() {
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new RedisConnectionFailureException("down")).when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        assertThatThrownBy(() -> adapter("sneezecast").revoke(TOKEN_ID, Duration.ofMinutes(1)))
            .isInstanceOf(RedisConnectionFailureException.class);
    }

    private RedisAccessTokenBlacklistAdapter adapter(String keyPrefix) {
        RedisProperties properties = new RedisProperties(RedisMode.SENTINEL, null, null, "mymaster", null, null, "localhost:26379", keyPrefix, null);
        return new RedisAccessTokenBlacklistAdapter(redisTemplate, properties);
    }
}
