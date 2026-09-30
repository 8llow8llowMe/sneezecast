package com.sneezecast.global.config;

import com.sneezecast.security.resourceserver.jwt.JwtResourceServerProperties;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * access token 검증 키를 기동 시점에 검사한다. auth-service {@code JwtAuthPropertiesValidator} 와 대칭이다.
 *
 * <p>키가 비었거나 HS512 최소 길이(64바이트)보다 짧으면 기동 실패 — 잘린 키로 뜨면 health 는 UP 인데 인증 요청이 전부 401 이 된다.
 * 길이는 맞는데 값만 auth · 게이트웨이와 다른 키는 여기서 잡지 못한다 (같은 {@code JWT_ACCESS_KEY} 를 주입하는 배포 규칙에 기댄다).
 */
@Component
@RequiredArgsConstructor
public class JwtResourceServerPropertiesValidator implements InitializingBean {

    static final int MIN_HS512_KEY_BYTES = 64;

    private final JwtResourceServerProperties jwtResourceServerProperties;

    @Override
    public void afterPropertiesSet() {
        validate(jwtResourceServerProperties);
    }

    // 키 값 자체는 메시지에 넣지 않는다.
    static void validate(JwtResourceServerProperties properties) {
        String accessKey = properties.accessKey();
        if (accessKey == null || accessKey.isBlank() || accessKey.getBytes(StandardCharsets.UTF_8).length < MIN_HS512_KEY_BYTES) {
            throw new IllegalStateException(
                "app.security.jwt.resource.access-key (JWT_ACCESS_KEY) 는 공백이 아닌 " + MIN_HS512_KEY_BYTES + "바이트 이상이어야 합니다 (HS512).");
        }
    }
}
