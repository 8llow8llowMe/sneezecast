package com.sneezecast.security.resourceserver.jwt;

import com.sneezecast.security.common.jwt.JwtSigningKeys;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Resource Server 의 access token 검증 키. auth-service · 게이트웨이와 같은 {@code JWT_ACCESS_KEY} 를 받는다.
 * 생성 시점에 HS512 최소 길이(UTF-8 64바이트)를 검사하므로 짧은 키로는 기동하지 않는다 ({@link JwtSigningKeys}).
 */
@ConfigurationProperties(prefix = "app.security.jwt.resource")
public record JwtResourceServerProperties(
    String accessKey
) {

    public JwtResourceServerProperties {
        JwtSigningKeys.requireHs512Key("app.security.jwt.resource.access-key", "JWT_ACCESS_KEY", accessKey);
    }

    /** record 기본 toString 은 필드 값을 그대로 찍는다. 설정 덤프 · 디버그 로그로 키가 새지 않게 가린다. */
    @Override
    public String toString() {
        return "JwtResourceServerProperties[accessKey=" + JwtSigningKeys.MASKED + "]";
    }
}
