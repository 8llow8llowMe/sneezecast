package com.sneezecast.domainlayer.auth.adapter.out.persistence;

import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.model.EmailCodePurpose;
import com.sneezecast.domainlayer.auth.application.port.out.EmailVerificationStorePort;
import com.sneezecast.redis.properties.RedisProperties;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 이메일 인증 상태를 Redis(TTL)에 둔다. 키는 {@code {prefix}:auth:{종류}:{정규화된 이메일 또는 IP}} — prefix 는 redis-core
 * {@link RedisProperties#normalizedKeyPrefix()} 로 읽어 블랙리스트 키와 같은 규칙을 쓴다. 종류 이름은 목적({@link EmailCodePurpose})별로 다르다.
 *
 * <ul>
 *   <li>가입: {@code emailVerificationCode} · {@code emailVerificationFail} · {@code emailVerificationCooldown} · {@code emailSendIp} ·
 *       {@code emailVerifyIp} (+ 인증 완료 {@code emailVerified})</li>
 *   <li>비밀번호 재설정: {@code passwordResetCode} · {@code passwordResetFail} · {@code passwordResetCooldown} · {@code passwordResetSendIp} ·
 *       {@code passwordResetVerifyIp}</li>
 * </ul>
 *
 * <p>장애를 삼키지 않는다 — 코드 저장이 실패했는데 발송이 성공한 것처럼 보이면 사용자가 원인을 알 수 없다. 대신 봉투 없는 500 으로 새지
 * 않게 {@link AuthErrorCode#EMAIL_VERIFICATION_UNAVAILABLE}(503)로 바꾼다. IP 발송 · 검증 카운터만 fail-open 이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisEmailVerificationStoreAdapter implements EmailVerificationStorePort {

    private static final String VERIFIED_VALUE = "verified";
    private static final String COOLDOWN_VALUE = "cooldown";

    private static final KeyTypes SIGNUP_KEYS = new KeyTypes("emailVerificationCode", "emailVerificationFail", "emailVerificationCooldown",
        "emailSendIp", "emailVerifyIp");
    private static final KeyTypes PASSWORD_RESET_KEYS = new KeyTypes("passwordResetCode", "passwordResetFail", "passwordResetCooldown",
        "passwordResetSendIp", "passwordResetVerifyIp");

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisProperties redisProperties;

    @Override
    public void saveCode(EmailCodePurpose purpose, String email, String code, Duration ttl) {
        execute(() -> {
            stringRedisTemplate.opsForValue().set(buildKey(keys(purpose).code(), email), code, ttl);
            return null;
        });
    }

    @Override
    public Optional<String> findCode(EmailCodePurpose purpose, String email) {
        return execute(() -> Optional.ofNullable(stringRedisTemplate.opsForValue().get(buildKey(keys(purpose).code(), email))));
    }

    @Override
    public void deleteCode(EmailCodePurpose purpose, String email) {
        execute(() -> stringRedisTemplate.delete(buildKey(keys(purpose).code(), email)));
    }

    @Override
    public void saveVerified(String email, Duration ttl) {
        execute(() -> {
            stringRedisTemplate.opsForValue().set(buildKey("emailVerified", email), VERIFIED_VALUE, ttl);
            return null;
        });
    }

    @Override
    public boolean isVerified(String email) {
        return execute(() -> Boolean.TRUE.equals(stringRedisTemplate.hasKey(buildKey("emailVerified", email))));
    }

    @Override
    public void deleteVerified(String email) {
        execute(() -> stringRedisTemplate.delete(buildKey("emailVerified", email)));
    }

    @Override
    public boolean tryAcquireCooldown(EmailCodePurpose purpose, String email, Duration ttl) {
        return execute(() -> Boolean.TRUE.equals(
            stringRedisTemplate.opsForValue().setIfAbsent(buildKey(keys(purpose).cooldown(), email), COOLDOWN_VALUE, ttl)));
    }

    @Override
    public long increaseVerifyFailureCount(EmailCodePurpose purpose, String email, Duration ttl) {
        return execute(() -> increaseWithTtl(buildKey(keys(purpose).fail(), email), ttl));
    }

    @Override
    public void clearVerifyFailures(EmailCodePurpose purpose, String email) {
        execute(() -> stringRedisTemplate.delete(buildKey(keys(purpose).fail(), email)));
    }

    @Override
    public long findIpSendCount(EmailCodePurpose purpose, String clientIp) {
        String type = keys(purpose).sendIp();
        return failOpen(type, () -> {
            String count = stringRedisTemplate.opsForValue().get(buildKey(type, clientIp));
            return count == null ? 0L : Long.parseLong(count);
        });
    }

    @Override
    public long increaseIpSendCount(EmailCodePurpose purpose, String clientIp, Duration window) {
        String type = keys(purpose).sendIp();
        return failOpen(type, () -> increaseWithTtl(buildKey(type, clientIp), window));
    }

    @Override
    public long increaseIpVerifyCount(EmailCodePurpose purpose, String clientIp, Duration window) {
        String type = keys(purpose).verifyIp();
        return failOpen(type, () -> increaseWithTtl(buildKey(type, clientIp), window));
    }

    private static KeyTypes keys(EmailCodePurpose purpose) {
        return switch (purpose) {
            case SIGNUP -> SIGNUP_KEYS;
            case PASSWORD_RESET -> PASSWORD_RESET_KEYS;
        };
    }

    /** IP 상한은 보조 방어라 저장소 장애로 요청 자체를 막지 않는다 (fail-open). IP 는 로그에 남기지 않는다. */
    private long failOpen(String counter, Supplier<Long> operation) {
        try {
            return operation.get();
        } catch (DataAccessException | NumberFormatException exception) {
            log.error("email ip counter failed, fail-open counter={} reason={}", counter, exception.getClass().getSimpleName());
            return 0L;
        }
    }

    /**
     * INCR 와 EXPIRE 는 원자적이지 않다 — 첫 증가 직후 장애가 나면 TTL 없는 카운터가 영구히 남아 그 키가 수동 복구 전까지 막힌다.
     * 첫 증가가 아니어도 TTL 이 없으면(과거 유실의 흔적) 다시 걸어 스스로 회복한다.
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

    private <T> T execute(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException exception) {
            // 이메일 · 코드는 남기지 않는다. 장애 종류만 알면 된다.
            log.error("email verification store failed reason={}", exception.getClass().getSimpleName());
            throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_UNAVAILABLE, exception);
        }
    }

    private String buildKey(String type, String value) {
        return redisProperties.normalizedKeyPrefix() + ":auth:" + type + ":" + value;
    }

    /** 목적별 키 종류 이름. */
    private record KeyTypes(String code, String fail, String cooldown, String sendIp, String verifyIp) {
    }
}
