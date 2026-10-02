package com.sneezecast.domainlayer.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.redis.properties.RedisProperties;
import com.sneezecast.redis.properties.enums.RedisMode;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 키 규칙 · 장애 처리만 본다. 소비 스크립트가 실제로 원자적인지는 {@code RedisRefreshSessionStoreAdapterIntegrationTest} 가 실제 Redis 로 본다.
 */
class RedisPasswordResetTokenStoreAdapterTest {

    private static final String HASH = "a".repeat(64);
    private static final String TOKEN_KEY = "sneezecast:auth:passwordResetToken:" + HASH;

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private RedisPasswordResetTokenStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        RedisProperties properties = new RedisProperties(RedisMode.SENTINEL, null, null, "mymaster", null, null, "localhost:26379", "sneezecast", null);
        adapter = new RedisPasswordResetTokenStoreAdapter(redisTemplate, properties);
    }

    @Test
    @DisplayName("토큰은 {prefix}:auth:passwordResetToken:{해시} 에 이메일을 토큰 수명으로 저장하고, 소비는 같은 키로 스크립트를 부른다")
    void tokenKeysUseHash() {
        when(redisTemplate.execute(eq(RedisPasswordResetTokenStoreAdapter.CONSUME_SCRIPT), eq(List.of(TOKEN_KEY)))).thenReturn("user@example.com");

        adapter.saveToken(HASH, "user@example.com", Duration.ofMinutes(15));

        verify(valueOperations).set(TOKEN_KEY, "user@example.com", Duration.ofMinutes(15));
        assertThat(adapter.consumeToken(HASH)).hasValue("user@example.com");
    }

    @Test
    @DisplayName("스크립트가 nil 을 주면(없음 · 만료 · 이미 씀) empty 다")
    void missingTokenIsEmpty() {
        assertThat(adapter.consumeToken(HASH)).isEmpty();
    }

    @Test
    @DisplayName("IP 카운터는 {prefix}:auth:passwordResetIp:{ip} 에 첫 증가부터 윈도우 TTL 로 센다 · 장애면 0 (fail-open)")
    void ipCounter() {
        String key = "sneezecast:auth:passwordResetIp:203.0.113.10";
        when(valueOperations.increment(key)).thenReturn(1L);

        assertThat(adapter.increaseIpResetCount("203.0.113.10", Duration.ofHours(1))).isEqualTo(1L);
        verify(redisTemplate).expire(key, Duration.ofHours(1));

        when(valueOperations.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        assertThat(adapter.increaseIpResetCount("203.0.113.10", Duration.ofHours(1))).isZero();
    }

    @Test
    @DisplayName("토큰 저장 · 소비의 Redis 장애는 AUTH_006(503)이다 — 봉투 없는 500 으로 새지 않는다")
    void tokenStoreFailureIsUnavailable() {
        when(redisTemplate.execute(eq(RedisPasswordResetTokenStoreAdapter.CONSUME_SCRIPT), anyList())).thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> adapter.consumeToken(HASH))
            .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.EMAIL_VERIFICATION_UNAVAILABLE));
    }
}
