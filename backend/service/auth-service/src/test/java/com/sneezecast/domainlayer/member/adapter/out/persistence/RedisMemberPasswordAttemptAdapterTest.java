package com.sneezecast.domainlayer.member.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sneezecast.redis.properties.RedisProperties;
import com.sneezecast.redis.properties.enums.RedisMode;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisMemberPasswordAttemptAdapterTest {

    private static final String KEY = "sneezecast:auth:passwordChangeFail:42";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private RedisMemberPasswordAttemptAdapter adapter;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        RedisProperties properties = new RedisProperties(RedisMode.SENTINEL, null, null, "mymaster", null, null, "localhost:26379", "sneezecast", null);
        adapter = new RedisMemberPasswordAttemptAdapter(redisTemplate, properties);
    }

    @Test
    @DisplayName("키는 {prefix}:auth:passwordChangeFail:{memberId} 이고 첫 증가에서만 TTL 을 건다")
    void countsPerMemberWithTtlOnFirstIncrement() {
        when(valueOperations.increment(KEY)).thenReturn(1L, 2L);
        when(redisTemplate.getExpire(KEY)).thenReturn(500L);

        assertThat(adapter.increaseFailureCount(42L, Duration.ofMinutes(10))).isEqualTo(1L);
        assertThat(adapter.increaseFailureCount(42L, Duration.ofMinutes(10))).isEqualTo(2L);

        verify(redisTemplate).expire(KEY, Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("잠금은 카운터 수명을 잠금 시간으로 다시 걸고, 성공은 카운터를 지운다")
    void lockAndClear() {
        adapter.lock(42L, Duration.ofMinutes(10));
        adapter.clearFailures(42L);

        verify(redisTemplate).expire(KEY, Duration.ofMinutes(10));
        verify(redisTemplate).delete(KEY);
    }

    @Test
    @DisplayName("Redis 장애에는 fail-open 이다 — 증가는 0, 잠금 · 삭제는 예외를 올리지 않는다")
    void failsOpen() {
        when(valueOperations.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenThrow(new RedisConnectionFailureException("down"));
        when(redisTemplate.delete(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(adapter.increaseFailureCount(42L, Duration.ofMinutes(10))).isZero();
        assertThatCode(() -> adapter.lock(42L, Duration.ofMinutes(10))).doesNotThrowAnyException();
        assertThatCode(() -> adapter.clearFailures(42L)).doesNotThrowAnyException();
        verify(redisTemplate, never()).getExpire(anyString());
    }
}
