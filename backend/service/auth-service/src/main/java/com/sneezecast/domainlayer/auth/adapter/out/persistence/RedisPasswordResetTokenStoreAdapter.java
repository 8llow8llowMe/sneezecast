package com.sneezecast.domainlayer.auth.adapter.out.persistence;

import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.port.out.PasswordResetTokenStorePort;
import com.sneezecast.redis.properties.RedisProperties;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 비밀번호 재설정 토큰을 Redis(TTL)에 둔다. 키는 이메일 인증과 같은 {@code {prefix}:auth:{종류}:{값}} 규칙이다.
 *
 * <ul>
 *   <li>{@code passwordResetToken:{토큰 SHA-256 hex}} — 정규화한 이메일, 수명 = {@code auth.password-reset.token-ttl}. 토큰 원문은 저장하지 않는다.</li>
 *   <li>{@code passwordResetIp:{ip}} — 재설정 요청 IP 시도 횟수, 수명 = IP 윈도우 (첫 증가부터, 고정 윈도우)</li>
 * </ul>
 *
 * <p>토큰 저장 · 소비 장애는 {@link AuthErrorCode#EMAIL_VERIFICATION_UNAVAILABLE}(503)로 바꾼다. IP 카운터만 fail-open 이다. 토큰 · 이메일 · IP 는
 * 로그에 남기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisPasswordResetTokenStoreAdapter implements PasswordResetTokenStorePort {

    /**
     * GET 과 DEL 을 한 스크립트로 묶는다. 나눠 보내면 같은 토큰으로 동시에 들어온 두 요청이 모두 GET 에 성공해 비밀번호를 두 번 바꿀 수 있다.
     * 키가 없으면 nil(→ null)이다. 스크립트는 카카오 로그인의 일회용 값과 함께 쓴다({@link RedisScripts#GET_AND_DELETE}).
     */
    static final RedisScript<String> CONSUME_SCRIPT = RedisScripts.GET_AND_DELETE;

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisProperties redisProperties;

    @Override
    public void saveToken(String tokenHash, String email, Duration ttl) {
        execute(() -> {
            stringRedisTemplate.opsForValue().set(buildKey("passwordResetToken", tokenHash), email, ttl);
            return null;
        });
    }

    @Override
    public Optional<String> consumeToken(String tokenHash) {
        return execute(() -> Optional.ofNullable(stringRedisTemplate.execute(CONSUME_SCRIPT, List.of(buildKey("passwordResetToken", tokenHash)))));
    }

    @Override
    public long increaseIpResetCount(String clientIp, Duration window) {
        try {
            return increaseWithTtl(buildKey("passwordResetIp", clientIp), window);
        } catch (DataAccessException exception) {
            log.error("password reset ip counter failed, fail-open reason={}", exception.getClass().getSimpleName());
            return 0L;
        }
    }

    /**
     * INCR 와 EXPIRE 는 원자적이지 않다 — 첫 증가 직후 장애가 나면 TTL 없는 카운터가 영구히 남는다. 첫 증가가 아니어도 TTL 이 없으면 다시 걸어
     * 스스로 회복한다 ({@link RedisEmailVerificationStoreAdapter} 와 같은 규칙).
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
            log.error("password reset token store failed reason={}", exception.getClass().getSimpleName());
            throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_UNAVAILABLE, exception);
        }
    }

    private String buildKey(String type, String value) {
        return redisProperties.normalizedKeyPrefix() + ":auth:" + type + ":" + value;
    }
}
