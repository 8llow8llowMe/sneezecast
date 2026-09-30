package com.sneezecast.global.config;

import com.sneezecast.security.auth.jwt.JwtAuthProperties;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * auth-service 발급 정책 중 만료 값을 기동 시점에 검사한다. 틀린 값으로 떠서 요청 시점에야 드러나는 것보다 기동 실패가 낫다.
 *
 * <ul>
 *   <li>access · refresh 만료는 0 보다 커야 한다.</li>
 *   <li>access token 만료는 {@value #MAX_ACCESS_EXPIRATION_MINUTES}분 이하 — 동의 철회가 scope 에서 빠지기까지의 최대 지연이다.</li>
 * </ul>
 *
 * <p>서명 키 길이(HS512 64바이트)는 여기서 보지 않는다 — 발급 · 검증 공통 규칙이라 security-core {@link JwtAuthProperties} 가
 * 바인딩 시점에 검사한다.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthPropertiesValidator implements InitializingBean {

    static final long MAX_ACCESS_EXPIRATION_MINUTES = 15;
    private static final Duration MAX_ACCESS_EXPIRATION = Duration.ofMinutes(MAX_ACCESS_EXPIRATION_MINUTES);

    private final JwtAuthProperties jwtAuthProperties;

    @Override
    public void afterPropertiesSet() {
        validate(jwtAuthProperties);
    }

    static void validate(JwtAuthProperties properties) {
        requirePositive("jwt.access-expiration", properties.accessExpiration());
        requirePositive("jwt.refresh-expiration", properties.refreshExpiration());

        if (properties.accessExpiration().compareTo(MAX_ACCESS_EXPIRATION) > 0) {
            throw new IllegalStateException(
                "jwt.access-expiration 은 " + MAX_ACCESS_EXPIRATION_MINUTES + "분 이하여야 합니다: " + properties.accessExpiration());
        }
    }

    private static void requirePositive(String name, Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalStateException(name + " 은 0 보다 커야 합니다: " + duration);
        }
    }
}
