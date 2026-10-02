package com.sneezecast.domainlayer.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sneezecast.redis.properties.RedisProperties;
import com.sneezecast.redis.properties.enums.RedisMode;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisLoginAttemptStoreAdapterTest {

    private static final String EMAIL = "user@example.com";
    private static final String CLIENT_IP = "203.0.113.10";
    private static final String FAIL_KEY = "sneezecast:auth:loginFail:" + EMAIL;
    private static final String LOCK_KEY = "sneezecast:auth:loginLock:" + EMAIL;
    private static final String IP_KEY = "sneezecast:auth:loginFailIp:" + CLIENT_IP;

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private RedisLoginAttemptStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        RedisProperties properties = new RedisProperties(RedisMode.SENTINEL, null, null, "mymaster", null, null, "localhost:26379", "sneezecast", null);
        adapter = new RedisLoginAttemptStoreAdapter(redisTemplate, properties);
    }

    @Test
    @DisplayName("이메일 실패 카운터는 {prefix}:auth:loginFail:{email} 이고 첫 실패에서만 TTL 을 건다")
    void emailFailureCounter() {
        when(valueOperations.increment(FAIL_KEY)).thenReturn(1L, 2L);
        when(redisTemplate.getExpire(FAIL_KEY)).thenReturn(500L);

        assertThat(adapter.increaseFailureCount(EMAIL, Duration.ofMinutes(10))).isEqualTo(1L);
        assertThat(adapter.increaseFailureCount(EMAIL, Duration.ofMinutes(10))).isEqualTo(2L);

        verify(redisTemplate).expire(FAIL_KEY, Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("잠금은 loginLock 키에 잠금 시간 TTL 로 걸고 카운터는 남긴다 · 성공은 카운터와 잠금을 함께 지운다")
    void lockAndClear() {
        when(redisTemplate.hasKey(LOCK_KEY)).thenReturn(true);

        adapter.lock(EMAIL, Duration.ofMinutes(10));
        assertThat(adapter.isLocked(EMAIL)).isTrue();
        adapter.clearFailures(EMAIL);

        verify(valueOperations).set(LOCK_KEY, "locked", Duration.ofMinutes(10));
        verify(redisTemplate, never()).delete(FAIL_KEY);
        verify(redisTemplate).delete(List.of(FAIL_KEY, LOCK_KEY));
    }

    @Test
    @DisplayName("IP 카운터는 loginFailIp 키이고 증가값을 돌려준다 · TTL 이 유실됐으면 다음 증가에서 다시 건다")
    void ipCounter() {
        when(valueOperations.increment(IP_KEY)).thenReturn(8L);
        when(redisTemplate.getExpire(IP_KEY)).thenReturn(-1L);

        assertThat(adapter.increaseIpAttemptCount(CLIENT_IP, Duration.ofHours(1))).isEqualTo(8L);

        verify(redisTemplate).expire(IP_KEY, Duration.ofHours(1));
    }

    @Test
    @DisplayName("성공 때 IP 몫 되돌리기는 0 아래로 내리지 않는 스크립트 하나로 loginFailIp 키에 보낸다")
    void ipDecrementUsesScript() {
        adapter.decreaseIpAttemptCount(CLIENT_IP);

        verify(redisTemplate).execute(RedisLoginAttemptStoreAdapter.DECREASE_IF_POSITIVE_SCRIPT, List.of(IP_KEY));
    }

    @Test
    @DisplayName("저장소 장애는 fail-open 이다 — 잠기지 않음 · 0회를 돌려주고, 쓰기 실패는 예외 없이 지나간다")
    void storeOutageFailsOpen() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        when(valueOperations.increment(anyString())).thenThrow(new QueryTimeoutException("timeout"));
        when(redisTemplate.execute(eq(RedisLoginAttemptStoreAdapter.DECREASE_IF_POSITIVE_SCRIPT), anyList())).thenThrow(new RedisConnectionFailureException("down"));
        when(redisTemplate.delete(any(List.class))).thenThrow(new RedisConnectionFailureException("down"));
        doThrow(new RedisConnectionFailureException("down")).when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        assertThat(adapter.isLocked(EMAIL)).isFalse();
        assertThat(adapter.increaseFailureCount(EMAIL, Duration.ofMinutes(10))).isZero();
        assertThat(adapter.increaseIpAttemptCount(CLIENT_IP, Duration.ofHours(1))).isZero();
        assertThatCode(() -> adapter.decreaseIpAttemptCount(CLIENT_IP)).doesNotThrowAnyException();
        assertThatCode(() -> adapter.lock(EMAIL, Duration.ofMinutes(10))).doesNotThrowAnyException();
        assertThatCode(() -> adapter.clearFailures(EMAIL)).doesNotThrowAnyException();
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }
}
