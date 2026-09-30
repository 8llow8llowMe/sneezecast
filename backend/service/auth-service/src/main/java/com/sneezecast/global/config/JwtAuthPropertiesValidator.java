package com.sneezecast.global.config;

import com.sneezecast.security.auth.jwt.JwtAuthProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * JWT 발급 설정을 기동 시점에 검사한다. 틀린 값으로 떠서 요청 시점에야 드러나는 것보다 기동 실패가 낫다.
 *
 * <ul>
 *   <li>access token 만료는 {@value #MAX_ACCESS_EXPIRATION_MINUTES}분 이하 — 동의 철회가 scope 에서 빠지기까지의 최대 지연이다.</li>
 *   <li>서명 키는 HS512 최소 길이(64바이트) 이상 — 짧으면 jjwt 가 발급 · 파싱마다 키를 거부해, 모든 토큰이 401 로 보인다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class JwtAuthPropertiesValidator implements InitializingBean {

    static final long MAX_ACCESS_EXPIRATION_MINUTES = 15;
    private static final Duration MAX_ACCESS_EXPIRATION = Duration.ofMinutes(MAX_ACCESS_EXPIRATION_MINUTES);
    private static final int MIN_HS512_KEY_BYTES = 64;

    private final JwtAuthProperties jwtAuthProperties;

    @Override
    public void afterPropertiesSet() {
        validate(jwtAuthProperties);
    }

    static void validate(JwtAuthProperties properties) {
        requireKey("jwt.access-key", properties.accessKey());
        requireKey("jwt.refresh-key", properties.refreshKey());
        requirePositive("jwt.access-expiration", properties.accessExpiration());
        requirePositive("jwt.refresh-expiration", properties.refreshExpiration());

        if (properties.accessExpiration().compareTo(MAX_ACCESS_EXPIRATION) > 0) {
            throw new IllegalStateException(
                "jwt.access-expiration 은 " + MAX_ACCESS_EXPIRATION_MINUTES + "분 이하여야 합니다: " + properties.accessExpiration());
        }
    }

    // 키 값 자체는 메시지에 넣지 않는다.
    private static void requireKey(String name, String key) {
        if (key == null || key.getBytes(StandardCharsets.UTF_8).length < MIN_HS512_KEY_BYTES) {
            throw new IllegalStateException(name + " 은 " + MIN_HS512_KEY_BYTES + "바이트 이상이어야 합니다 (HS512).");
        }
    }

    private static void requirePositive(String name, Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalStateException(name + " 은 0 보다 커야 합니다: " + duration);
        }
    }
}
