package com.sneezecast.domainlayer.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.redis.properties.RedisProperties;
import com.sneezecast.redis.properties.enums.RedisMode;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisEmailVerificationStoreAdapterTest {

    private static final String EMAIL = "user@example.com";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private RedisEmailVerificationStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        RedisProperties properties = new RedisProperties(RedisMode.SENTINEL, null, null, "mymaster", null, null, "localhost:26379", "sneezecast", null);
        adapter = new RedisEmailVerificationStoreAdapter(redisTemplate, properties);
    }

    @Test
    @DisplayName("코드 · 인증 완료 · 쿨다운 키는 {prefix}:auth:{종류}:{이메일} 이고 TTL 을 건다")
    void keysFollowPrefixRuleWithTtl() {
        adapter.saveCode(EMAIL, "ABCD2345", Duration.ofMinutes(5));
        adapter.saveVerified(EMAIL, Duration.ofMinutes(30));
        adapter.tryAcquireCooldown(EMAIL, Duration.ofSeconds(60));

        verify(valueOperations).set("sneezecast:auth:emailVerificationCode:" + EMAIL, "ABCD2345", Duration.ofMinutes(5));
        verify(valueOperations).set("sneezecast:auth:emailVerified:" + EMAIL, "verified", Duration.ofMinutes(30));
        verify(valueOperations).setIfAbsent("sneezecast:auth:emailVerificationCooldown:" + EMAIL, "cooldown", Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("카운터는 첫 증가에서만 TTL 을 건다")
    void counterSetsTtlOnFirstIncrement() {
        String key = "sneezecast:auth:emailVerificationFail:" + EMAIL;
        when(valueOperations.increment(key)).thenReturn(1L);

        assertThat(adapter.increaseVerifyFailureCount(EMAIL, Duration.ofMinutes(5))).isEqualTo(1L);

        verify(redisTemplate).expire(key, Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("TTL 이 유실된 카운터는 다음 증가에서 TTL 을 다시 건다 — 영구 차단 키가 남지 않는다")
    void counterHealsLostTtl() {
        String key = "sneezecast:auth:emailSendIp:203.0.113.10";
        when(valueOperations.increment(key)).thenReturn(4L);
        when(redisTemplate.getExpire(key)).thenReturn(-1L);

        assertThat(adapter.increaseIpSendCount("203.0.113.10", Duration.ofHours(1))).isEqualTo(4L);

        verify(redisTemplate).expire(key, Duration.ofHours(1));
    }

    @Test
    @DisplayName("TTL 이 살아 있는 카운터는 다시 걸지 않는다 — 고정 윈도우가 밀리지 않는다")
    void counterKeepsExistingTtl() {
        String key = "sneezecast:auth:emailSendIp:203.0.113.10";
        when(valueOperations.increment(key)).thenReturn(2L);
        when(redisTemplate.getExpire(key)).thenReturn(1200L);

        adapter.increaseIpSendCount("203.0.113.10", Duration.ofHours(1));

        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("IP 카운터는 Redis 장애에 fail-open 이다 — 0 을 돌려주고 발송을 막지 않는다")
    void ipCounterFailsOpen() {
        when(valueOperations.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(adapter.increaseIpSendCount("203.0.113.10", Duration.ofHours(1))).isZero();
    }

    @Test
    @DisplayName("IP 발송 횟수 조회는 증가시키지 않고, 키가 없으면 0 이다")
    void findIpSendCountDoesNotIncrement() {
        String key = "sneezecast:auth:emailSendIp:203.0.113.10";
        when(valueOperations.get(key)).thenReturn("7");
        assertThat(adapter.findIpSendCount("203.0.113.10")).isEqualTo(7L);

        when(valueOperations.get(key)).thenReturn(null);
        assertThat(adapter.findIpSendCount("203.0.113.10")).isZero();
        verify(valueOperations, never()).increment(anyString());
    }

    @Test
    @DisplayName("IP 횟수 조회도 Redis 장애에 fail-open 이다 — 0")
    void findIpSendCountFailsOpen() {
        when(valueOperations.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        assertThat(adapter.findIpSendCount("203.0.113.10")).isZero();
    }

    @Test
    @DisplayName("IP 횟수 값이 숫자가 아니면 0 으로 본다 — 500 으로 새지 않는다")
    void findIpSendCountIgnoresCorruptValue() {
        when(valueOperations.get(anyString())).thenReturn("not-a-number");
        assertThat(adapter.findIpSendCount("203.0.113.10")).isZero();
    }

    @Test
    @DisplayName("IP 검증 카운터는 별도 키 {prefix}:auth:emailVerifyIp:{ip} 에 윈도우 TTL 로 센다")
    void verifyCounterUsesOwnKey() {
        String key = "sneezecast:auth:emailVerifyIp:203.0.113.10";
        when(valueOperations.increment(key)).thenReturn(1L);

        assertThat(adapter.increaseIpVerifyCount("203.0.113.10", Duration.ofHours(1))).isEqualTo(1L);

        verify(redisTemplate).expire(key, Duration.ofHours(1));
    }

    @Test
    @DisplayName("그 밖의 연산은 Redis 장애를 AUTH_006(503) 으로 바꾼다 — 봉투 없는 500 으로 새지 않는다")
    void otherOperationsBecomeUnavailable() {
        when(valueOperations.get(anyString())).thenThrow(new QueryTimeoutException("timeout"));
        when(redisTemplate.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> adapter.findCode(EMAIL))
            .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.EMAIL_VERIFICATION_UNAVAILABLE));
        assertThatThrownBy(() -> adapter.isVerified(EMAIL))
            .isInstanceOfSatisfying(AuthException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.EMAIL_VERIFICATION_UNAVAILABLE);
                assertThat(e.getErrorCode().getHttpStatus().value()).isEqualTo(503);
            });
    }

    @Test
    @DisplayName("인증 완료 표시가 있으면 true, 없거나 Redis 가 null 을 주면 false 다")
    void isVerifiedReadsFlag() {
        when(redisTemplate.hasKey("sneezecast:auth:emailVerified:" + EMAIL)).thenReturn(true);
        assertThat(adapter.isVerified(EMAIL)).isTrue();

        when(redisTemplate.hasKey("sneezecast:auth:emailVerified:" + EMAIL)).thenReturn(null);
        assertThat(adapter.isVerified(EMAIL)).isFalse();
    }
}
