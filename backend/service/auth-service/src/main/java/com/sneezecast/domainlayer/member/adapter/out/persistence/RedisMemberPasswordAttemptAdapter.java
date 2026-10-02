package com.sneezecast.domainlayer.member.adapter.out.persistence;

import com.sneezecast.domainlayer.member.application.port.out.MemberPasswordAttemptPort;
import com.sneezecast.redis.properties.RedisProperties;
import java.time.Duration;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 비밀번호 변경의 현재 비밀번호 확인 실패 카운터를 Redis(TTL)에 둔다. 키는 auth 와 같은 규칙의 {@code {prefix}:auth:passwordChangeFail:{memberId}} 다
 * (auth-service 의 Redis 네임스페이스는 {@code :auth:} 하나다). 수명은 첫 실패부터 잠금 시간이고, 상한에 닿으면 잠금 시간으로 다시 건다.
 *
 * <p><b>장애에는 fail-open 이다</b> (포트 계약). 상한이 꺼진 구간을 나중에 알 수 있게 ERROR 로그를 남긴다. 회원 ID 는 로그에 남기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisMemberPasswordAttemptAdapter implements MemberPasswordAttemptPort {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisProperties redisProperties;

    @Override
    public long increaseFailureCount(long memberId, Duration ttl) {
        return failOpen(0L, () -> increaseWithTtl(buildKey(memberId), ttl));
    }

    @Override
    public void lock(long memberId, Duration lockDuration) {
        failOpen(null, () -> stringRedisTemplate.expire(buildKey(memberId), lockDuration));
    }

    @Override
    public void clearFailures(long memberId) {
        failOpen(null, () -> stringRedisTemplate.delete(buildKey(memberId)));
    }

    /**
     * INCR 와 EXPIRE 는 원자적이지 않다 — 첫 증가 직후 장애가 나면 TTL 없는 카운터가 영구히 남아 그 회원의 비밀번호 변경이 막힌다. 첫 증가가 아니어도
     * TTL 이 없으면 다시 걸어 스스로 회복한다 (auth 의 로그인 카운터와 같은 규칙). 매 실패마다 TTL 을 다시 걸지는 않는다.
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

    private <T> T failOpen(T fallback, Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException exception) {
            log.error("password change attempt store failed, fail-open reason={}", exception.getClass().getSimpleName());
            return fallback;
        }
    }

    private String buildKey(long memberId) {
        return redisProperties.normalizedKeyPrefix() + ":auth:passwordChangeFail:" + memberId;
    }
}
