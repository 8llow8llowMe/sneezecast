package com.sneezecast.domainlayer.auth.adapter.out.persistence;

import com.sneezecast.domainlayer.auth.application.port.out.LoginAttemptStorePort;
import com.sneezecast.redis.properties.RedisProperties;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 로그인 실패 카운터 · 잠금을 Redis(TTL)에 둔다. 키는 이메일 인증과 같은 {@code {prefix}:auth:{종류}:{정규화된 이메일 또는 IP}} 규칙이다.
 *
 * <ul>
 *   <li>{@code loginFail:{email}} — 이메일 시도 횟수(비교 전에 올리고 성공하면 지운다), 수명 = 잠금 시간 (첫 증가부터)</li>
 *   <li>{@code loginLock:{email}} — 잠금 표시, 수명 = 잠금 시간</li>
 *   <li>{@code loginFailIp:{ip}} — IP 시도 횟수(비교 전에 올리고 성공하면 자기 몫만 되돌린다), 수명 = IP 윈도우 (첫 증가부터, 고정 윈도우)</li>
 * </ul>
 *
 * <p><b>장애에는 fail-open 이다</b> (포트 계약). 반대로 access token 블랙리스트 조회는 놓치면 폐기한 토큰이 통하므로 fail-closed 다 — 성격이 다르다.
 * 상한이 꺼진 구간을 나중에 알 수 있게 ERROR 로그를 남긴다. 이메일 · IP 는 로그에 남기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLoginAttemptStoreAdapter implements LoginAttemptStorePort {

    private static final String LOCKED_VALUE = "locked";

    /**
     * 키가 있고 0 보다 클 때만 1 내린다. GET 과 DECR 을 나눠 보내면 그 사이에 윈도우가 만료돼 TTL 없는 -1 키가 생길 수 있어 한 스크립트로 묶는다.
     * DECR 은 TTL 을 그대로 두므로 고정 윈도우가 밀리지 않는다.
     */
    static final RedisScript<Long> DECREASE_IF_POSITIVE_SCRIPT = new DefaultRedisScript<>("""
        local count = tonumber(redis.call('GET', KEYS[1]) or '0')
        if count and count > 0 then
          return redis.call('DECR', KEYS[1])
        end
        return 0
        """, Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisProperties redisProperties;

    @Override
    public boolean isLocked(String email) {
        return failOpen("loginLock", false, () -> Boolean.TRUE.equals(stringRedisTemplate.hasKey(buildKey("loginLock", email))));
    }

    @Override
    public long increaseFailureCount(String email, Duration ttl) {
        return failOpen("loginFail", 0L, () -> increaseWithTtl(buildKey("loginFail", email), ttl));
    }

    @Override
    public void lock(String email, Duration lockDuration) {
        failOpen("loginLock", null, () -> {
            stringRedisTemplate.opsForValue().set(buildKey("loginLock", email), LOCKED_VALUE, lockDuration);
            return null;
        });
    }

    @Override
    public void clearFailures(String email) {
        failOpen("loginFail", null, () -> stringRedisTemplate.delete(List.of(buildKey("loginFail", email), buildKey("loginLock", email))));
    }

    @Override
    public long increaseIpAttemptCount(String clientIp, Duration window) {
        return failOpen("loginFailIp", 0L, () -> increaseWithTtl(buildKey("loginFailIp", clientIp), window));
    }

    @Override
    public void decreaseIpAttemptCount(String clientIp) {
        failOpen("loginFailIp", 0L, () -> stringRedisTemplate.execute(DECREASE_IF_POSITIVE_SCRIPT, List.of(buildKey("loginFailIp", clientIp))));
    }

    /**
     * INCR 와 EXPIRE 는 원자적이지 않다 — 첫 증가 직후 장애가 나면 TTL 없는 카운터가 영구히 남아 그 키가 수동 복구 전까지 막힌다. 첫 증가가
     * 아니어도 TTL 이 없으면(과거 유실의 흔적) 다시 걸어 스스로 회복한다. 매 실패마다 TTL 을 다시 걸지는 않는다 — 공격자가 카운터를 무한히
     * 살려 두지 못하고, 정상 사용자의 오래된 실패는 저절로 사라진다.
     */
    private long increaseWithTtl(String key, Duration ttl) {
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(key, ttl);
        } else {
            Long remaining = stringRedisTemplate.getExpire(key);
            if (remaining != null && remaining < 0) {
                stringRedisTemplate.expire(key, ttl);
            }
        }
        return count == null ? 0L : count;
    }

    private <T> T failOpen(String counter, T fallback, Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException | NumberFormatException exception) {
            log.error("login attempt store failed, fail-open counter={} reason={}", counter, exception.getClass().getSimpleName());
            return fallback;
        }
    }

    private String buildKey(String type, String value) {
        return redisProperties.normalizedKeyPrefix() + ":auth:" + type + ":" + value;
    }
}
