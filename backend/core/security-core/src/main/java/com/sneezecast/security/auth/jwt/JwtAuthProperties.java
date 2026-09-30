package com.sneezecast.security.auth.jwt;

import com.sneezecast.security.common.jwt.JwtSigningKeys;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 발급 · 파싱 설정. 두 키 모두 HS512 라 생성 시점에 UTF-8 64바이트 이상인지 검사한다 ({@link JwtSigningKeys}) —
 * 바인딩에서 실패하므로 짧은 키로는 기동하지 않는다. 만료 정책(상한 등)은 발급 정책이라 auth-service 가 검사한다.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtAuthProperties(
    String accessKey,
    Duration accessExpiration,
    String refreshKey,
    Duration refreshExpiration
) {

    public JwtAuthProperties {
        JwtSigningKeys.requireHs512Key("jwt.access-key", "JWT_ACCESS_KEY", accessKey);
        JwtSigningKeys.requireHs512Key("jwt.refresh-key", "JWT_REFRESH_KEY", refreshKey);
    }

    /** record 기본 toString 은 필드 값을 그대로 찍는다. 설정 덤프 · 디버그 로그로 키가 새지 않게 가린다. */
    @Override
    public String toString() {
        return "JwtAuthProperties[accessKey=" + JwtSigningKeys.MASKED
            + ", accessExpiration=" + accessExpiration
            + ", refreshKey=" + JwtSigningKeys.MASKED
            + ", refreshExpiration=" + refreshExpiration + "]";
    }
}
